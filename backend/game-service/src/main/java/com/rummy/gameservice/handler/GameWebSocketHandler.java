package com.rummy.gameservice.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.engine.command.*;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.GameStatus;
import com.rummy.engine.model.PlayerState;
import com.rummy.engine.model.PlayerStatus;
import com.rummy.engine.rules.CardGroup;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.lifecycle.NodeDrainState;
import com.rummy.gameservice.matchmaking.MatchmakingService;
import com.rummy.gameservice.protocol.WsClientMessage;
import com.rummy.gameservice.protocol.WsErrorMessage;
import com.rummy.gameservice.protocol.WsServerMessage;
import com.rummy.gameservice.recovery.TableRecoveryService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.security.RateLimitingService;
import com.rummy.gameservice.session.PlayerSessionService;
import com.rummy.gameservice.websocket.SessionOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Real-time WebSocket protocol gateway handling client sessions and routing to TableActors.
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);
    private static final String MATCHMADE_TABLE_PREFIX = "TBL_MM_";
    private static final int MAX_INBOUND_FRAME_BYTES = 64 * 1024;
    private static final String TOMCAT_BLOCKING_SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT";

    @Value("${rummy.websocket.idle-timeout-ms:120000}")
    private long idleTimeoutMs = 120_000;

    @Value("${rummy.websocket.send-timeout-ms:10000}")
    private long sendTimeoutMs = 10_000;

    private final TableManager tableManager;
    private final ObjectMapper objectMapper;
    private final RateLimitingService rateLimitingService;
    private final TableRoutingRegistry routingRegistry;
    private final PlayerSessionService sessionService;

    /** When true, identity comes only from the handshake JWT; client-sent playerIds are ignored. */
    private final boolean enforceJwt;

    private MatchmakingService matchmakingService;
    private NodeDrainState drainState;
    private ClusterNodeService clusterNodes;
    private TableRecoveryService recovery;
    private TableRelay relay;
    private final Map<String, WebSocketSession> openSessions = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public GameWebSocketHandler(
            TableManager tableManager,
            ObjectMapper objectMapper,
            RateLimitingService rateLimitingService,
            TableRoutingRegistry routingRegistry,
            PlayerSessionService sessionService,
            @Value("${rummy.security.enforce-jwt:true}") boolean enforceJwt) {
        this.tableManager = Objects.requireNonNull(tableManager);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.rateLimitingService = Objects.requireNonNull(rateLimitingService);
        this.routingRegistry = Objects.requireNonNull(routingRegistry);
        this.sessionService = Objects.requireNonNull(sessionService);
        this.enforceJwt = enforceJwt;
    }

    public GameWebSocketHandler(
            TableManager tableManager,
            ObjectMapper objectMapper,
            RateLimitingService rateLimitingService,
            TableRoutingRegistry routingRegistry,
            PlayerSessionService sessionService) {
        this(tableManager, objectMapper, rateLimitingService, routingRegistry, sessionService, false);
    }

    @Autowired(required = false)
    public void setMatchmakingService(MatchmakingService matchmakingService) {
        this.matchmakingService = matchmakingService;
    }

    @Autowired(required = false)
    public void setDrainState(NodeDrainState drainState) {
        this.drainState = drainState;
    }

    /**
     * While draining, disconnects sockets that are not attached to a table still hosted here,
     * so those clients reconnect through the load balancer to a node that is accepting players.
     */
    public int closeSessionsWithoutTable() {
        int closed = 0;
        for (WebSocketSession session : openSessions.values()) {
            String tableId = (String) session.getAttributes().get("tableId");
            if (tableId != null && tableManager.getTable(tableId).isPresent()) {
                continue;
            }
            try {
                session.close(CloseStatus.SERVICE_RESTARTED);
                closed++;
            } catch (IOException | RuntimeException e) {
                log.debug("[WS:{}] Close during drain failed: {}", session.getId(), e.getMessage());
            }
        }
        return closed;
    }

    public int openSessionCount() {
        return openSessions.size();
    }

    @Autowired(required = false)
    public void setClusterNodes(ClusterNodeService clusterNodes) {
        this.clusterNodes = clusterNodes;
    }

    @Autowired(required = false)
    public void setRecovery(TableRecoveryService recovery) {
        this.recovery = recovery;
    }

    @Autowired(required = false)
    public void setRelay(TableRelay relay) {
        this.relay = relay;
    }

    /**
     * Connects the player through this node to the node hosting their table. True when the frame was
     * handled: relayed, or the owner was unreachable and the client was told to retry.
     */
    private boolean relayToOwner(WebSocketSession session, String tableId, String owner, String playerId,
                                 String frame, String reqId) {
        if (relay == null || owner == null) {
            return false;
        }
        return switch (relay.open(session, tableId, owner, playerId, frame)) {
            case CONNECTED -> true;
            case UNREACHABLE -> {
                sendError(session, "TABLE_RECOVERING", "Reconnecting you to your game…", reqId);
                yield true;
            }
            case UNAVAILABLE -> false;
        };
    }

    /** The table no longer exists anywhere (finished and cleaned up, or its server crashed). */
    private void sendTableGone(WebSocketSession session, String playerId, String tableId, String reqId) {
        if (routingRegistry.getTableForPlayer(playerId).map(tableId::equals).orElse(false)) {
            sessionService.clearPlayerBinding(playerId);
        }
        send(session, WsServerMessage.of("TABLE_CLOSED", reqId, tableId, Map.of(
                "reason", "table no longer available",
                "message", "This game has ended. If it was cut short by a server restart, your entry fee is refunded automatically within a few minutes."
        )));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.debug("[WS] New connection established: {}", session.getId());
        applyTransportLimits(session);
        SessionOutbox.attach(session);
        TableRelay.markIfRelayed(session);
        openSessions.put(session.getId(), session);
        String authPlayerId = (String) session.getAttributes().get("authenticatedPlayerId");
        if (authPlayerId != null) {
            session.getAttributes().put("playerId", authPlayerId);
        }
        WsServerMessage connectedMsg = WsServerMessage.of("CONNECTED", UUID.randomUUID().toString(), null,
                Map.of(
                        "sessionId", session.getId(),
                        "serverTime", Instant.now().toString(),
                        "serverInstanceId", routingRegistry.getServerInstanceId()
                ));
        send(session, connectedMsg);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!rateLimitingService.tryAcquire(session.getId())) {
            log.warn("[WS:{}] Rate limit exceeded for session", session.getId());
            sendError(session, "RATE_LIMIT_EXCEEDED", "Too many requests. Please slow down.", null);
            return;
        }

        String payload = message.getPayload();
        log.debug("[WS:{}] Inbound payload: {}", session.getId(), payload);

        WsClientMessage clientMsg;
        try {
            clientMsg = objectMapper.readValue(payload, WsClientMessage.class);
        } catch (Exception e) {
            sendError(session, "MALFORMED_JSON", "Invalid JSON message format: " + e.getMessage(), null);
            return;
        }

        String type = clientMsg.type();
        String reqId = clientMsg.requestId() != null ? clientMsg.requestId() : UUID.randomUUID().toString();
        String tableId = clientMsg.tableId();

        if (relay != null && relay.forward(session, tableId, payload)) {
            return;
        }

        if ("PING".equalsIgnoreCase(type)) {
            send(session, WsServerMessage.of("PONG", reqId, tableId, Map.of("timestamp", Instant.now().toEpochMilli())));
            return;
        }

        String authenticatedPlayerId = (String) session.getAttributes().get("authenticatedPlayerId");

        if ("AUTH".equalsIgnoreCase(type)) {
            String playerId;
            if (authenticatedPlayerId != null) {
                playerId = authenticatedPlayerId;
            } else if (enforceJwt) {
                sendError(session, "UNAUTHORIZED", "A valid token is required", reqId);
                return;
            } else {
                playerId = clientMsg.payload() != null && clientMsg.payload().has("playerId")
                        ? clientMsg.payload().get("playerId").asText()
                        : "P_" + session.getId().substring(0, 6);
            }
            session.getAttributes().put("playerId", playerId);
            send(session, WsServerMessage.of("AUTH_SUCCESS", reqId, null, Map.of("playerId", playerId)));
            return;
        }

        String playerId = authenticatedPlayerId != null
                ? authenticatedPlayerId
                : (String) session.getAttributes().get("playerId");
        if (playerId == null && !enforceJwt && clientMsg.payload() != null && clientMsg.payload().has("playerId")) {
            playerId = clientMsg.payload().get("playerId").asText();
            session.getAttributes().put("playerId", playerId);
        }
        if (playerId == null) {
            sendError(session, "UNAUTHORIZED", "Must authenticate or provide playerId before game actions", reqId);
            return;
        }

        if (tableId == null || tableId.isBlank()) {
            sendError(session, "MISSING_TABLE_ID", "tableId is required for game action " + type, reqId);
            return;
        }

        // Multi-node: refuse to create a ghost table when Redis says another server owns it
        boolean ownerDied = false;
        if (routingRegistry.isOwnedByRemoteServer(tableId)) {
            String owner = routingRegistry.getServerForTable(tableId).orElse("unknown");
            if (clusterNodes == null || clusterNodes.isAlive(owner)) {
                if (relayToOwner(session, tableId, owner, playerId, payload, reqId)) {
                    return;
                }
                log.warn("[WS] Table {} owned by remote server {} — redirecting client from {}", tableId, owner, routingRegistry.getServerInstanceId());
                send(session, WsServerMessage.of("REDIRECT", reqId, tableId, Map.of(
                        "targetServerId", owner,
                        "reason", "Table is hosted on another node. Please reconnect using this targetServerId."
                )));
                return;
            }
            log.warn("[WS] Table {} died with node {}", tableId, owner);
            routingRegistry.unregisterTableIfOwnedBy(tableId, owner);
            ownerDied = true;
        }

        if (tableManager.getTable(tableId).isEmpty()) {
            if (drainState != null && drainState.isDraining()) {
                sendError(session, "SERVER_DRAINING", "This server is restarting. Please reconnect.", reqId);
                return;
            }
            if (!tableManager.hasCapacityForNewTable()) {
                sendError(session, "SERVER_BUSY", "This server is full. Please try again shortly.", reqId);
                return;
            }
            TableRecoveryService.RestoreResult restore = recovery != null ? recovery.tryRestore(tableId) : null;
            if (restore != null && restore.outcome() == TableRecoveryService.Outcome.OWNER_ALIVE) {
                // Its host is alive (this node just had no routing entry), or stopped answering moments ago
                // and is declared dead after a few missed heartbeats.
                if (!relayToOwner(session, tableId, restore.owner(), playerId, payload, reqId)) {
                    sendError(session, "TABLE_RECOVERING", "Reconnecting you to your game…", reqId);
                }
                return;
            }
            if (tableManager.getTable(tableId).isEmpty() && (ownerDied || tableId.startsWith(MATCHMADE_TABLE_PREFIX))) {
                sendTableGone(session, playerId, tableId, reqId);
                return;
            }
        }

        if (tableId.startsWith(MATCHMADE_TABLE_PREFIX) && !isAllowedAtMatchmadeTable(playerId, tableId)) {
            sendError(session, "FORBIDDEN", "You are not seated at this table", reqId);
            return;
        }

        TableActor tableActor = tableManager.getOrCreateTable(tableId, null);
        if (!routingRegistry.isOwnedByThisServer(tableId)) {
            // First touch on this node for a brand-new private table — claim ownership
            routingRegistry.registerTableOwnership(tableId);
        }
        tableActor.registerSession(playerId, session);
        session.getAttributes().put("tableId", tableId);

        Instant now = Instant.now();
        String gameId = tableActor.getState().getGameId();
        JsonNode data = clientMsg.payload();

        switch (type.toUpperCase()) {
            case "JOIN_TABLE" -> {
                if (data != null && data.has("isBot") && data.get("isBot").asBoolean()) {
                    sendError(session, "FORBIDDEN", "Clients cannot seat bots", reqId);
                    return;
                }
                String name = data != null && data.has("displayName") ? data.get("displayName").asText() : playerId;
                String avatarId = data != null && data.has("avatarId") && !data.get("avatarId").isNull() ? data.get("avatarId").asText() : null;
                int seat = data != null && data.has("seatIndex") ? data.get("seatIndex").asInt() : 0;
                boolean isBot = false;
                String targetPlayerId = playerId;
                // Reconnection: If player already seated, re-register session and view
                var existingPlayerOpt = tableActor.getState().getPlayer(targetPlayerId);
                if (existingPlayerOpt.isPresent()) {
                    if (avatarId != null && !avatarId.isBlank()) {
                        existingPlayerOpt.get().setAvatarId(avatarId);
                    }
                    tableActor.registerSession(targetPlayerId, session);
                    if (!isBot) {
                        sessionService.bindPlayerToTable(targetPlayerId, tableId);
                    }
                    return;
                }

                // If requested seat is occupied, assign first unoccupied seat
                Set<Integer> occupiedSeats = new HashSet<>();
                for (PlayerState p : tableActor.getState().getPlayers()) {
                    occupiedSeats.add(p.getSeatIndex());
                }
                int finalSeat = seat;
                if (occupiedSeats.contains(finalSeat)) {
                    for (int s = 0; s < 6; s++) {
                        if (!occupiedSeats.contains(s)) {
                            finalSeat = s;
                            break;
                        }
                    }
                }

                if (isBot) {
                    tableActor.registerBot(targetPlayerId, name, com.rummy.engine.bot.BotDifficulty.HARD);
                }

                JoinCommand cmd = new JoinCommand(reqId, gameId, targetPlayerId, name, finalSeat, isBot, now, avatarId);
                tableActor.processCommand(cmd, reqId);

                if (!isBot) {
                    sessionService.bindPlayerToTable(targetPlayerId, tableId);
                }

                // Auto-mark joined player as READY
                tableActor.processCommand(new ReadyCommand(UUID.randomUUID().toString(), gameId, targetPlayerId, now), "AUTO_READY");

                if (tableActor.shouldAutoStart()) {
                    tableActor.processCommand(new StartGameCommand(UUID.randomUUID().toString(), gameId, targetPlayerId, now), "AUTO_START");
                    log.info("[GameWebSocketHandler] Auto-started table {} with {} players", tableId, tableActor.getState().getPlayers().size());
                }
            }
            case "LEAVE_TABLE" -> {
                // Voluntary leave — clear resume binding; drop if active and mark eliminated to prevent ghost deal stalls
                if (tableActor.getState().getStatus() == GameStatus.IN_PROGRESS || tableActor.getState().getStatus() == GameStatus.COMPLETED) {
                    tableActor.handleVoluntaryLeave(playerId, reqId);
                } else if (tableActor.getState().getStatus() == GameStatus.WAITING_FOR_PLAYERS) {
                    tableActor.removeWaitingHuman(playerId);
                    if (matchmakingService != null) {
                        matchmakingService.onWaitingHumanLeft(playerId, tableId);
                    }
                }
                sessionService.clearPlayerBinding(playerId);
                tableActor.unregisterSession(playerId);
                send(session, WsServerMessage.of("LEFT_TABLE", reqId, tableId, Map.of(
                        "playerId", playerId,
                        "tableId", tableId
                )));
                log.info("[WS] Player {} voluntarily left table {}", playerId, tableId);
            }
            case "READY" -> {
                ReadyCommand cmd = new ReadyCommand(reqId, gameId, playerId, now);
                tableActor.processCommand(cmd, reqId);

                if (tableActor.shouldAutoStart()) {
                    tableActor.processCommand(new StartGameCommand(UUID.randomUUID().toString(), gameId, playerId, now), "AUTO_START");
                    log.info("[GameWebSocketHandler] Auto-started table {} on READY", tableId);
                }
            }
            case "START_GAME" -> {
                // Next deals are dealt by the server; a finished match must never be restarted by a client.
                if (tableActor.getState().getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
                    sendError(session, "GAME_ALREADY_STARTED", "This game has already been dealt", reqId);
                    return;
                }
                StartGameCommand cmd = new StartGameCommand(reqId, gameId, playerId, now);
                tableActor.processCommand(cmd, reqId);
            }
            case "DRAW" -> {
                String srcStr = data != null && data.has("source") ? data.get("source").asText() : "CLOSED_DECK";
                DrawSource src = "DISCARD_PILE".equalsIgnoreCase(srcStr) ? DrawSource.DISCARD_PILE : DrawSource.CLOSED_DECK;
                DrawCommand cmd = new DrawCommand(reqId, gameId, playerId, src, now);
                tableActor.processCommand(cmd, reqId);
            }
            case "DISCARD" -> {
                if (data == null || !data.has("cardInstanceId")) {
                    sendError(session, "MISSING_CARD_ID", "cardInstanceId is required for DISCARD", reqId);
                    return;
                }
                String cardId = data.get("cardInstanceId").asText();
                DiscardCommand cmd = new DiscardCommand(reqId, gameId, playerId, cardId, now);
                tableActor.processCommand(cmd, reqId);
            }
            case "DECLARE" -> {
                if (data == null || !data.has("finishCardInstanceId")) {
                    sendError(session, "MISSING_FINISH_CARD", "finishCardInstanceId is required for DECLARE", reqId);
                    return;
                }
                String finishId = data.get("finishCardInstanceId").asText();
                List<CardGroup> groups = parseCardGroups(data.get("groups"), tableActor.getState().requirePlayer(playerId).getHandSnapshot());
                DeclareCommand cmd = new DeclareCommand(reqId, gameId, playerId, finishId, groups, now);
                tableActor.processCommand(cmd, reqId);
            }
            case "DROP" -> {
                DropCommand cmd = new DropCommand(reqId, gameId, playerId, now);
                tableActor.processCommand(cmd, reqId);
            }
            case "REJOIN" -> {
                tableActor.handleRejoin(playerId, reqId);
            }
            case "SPLIT_REQUEST" -> tableActor.handleSplitRequest(playerId, reqId);
            case "SPLIT_RESPONSE" -> {
                boolean accept = data != null && data.has("accept") && data.get("accept").asBoolean();
                tableActor.handleSplitResponse(playerId, accept, reqId);
            }
            default -> sendError(session, "UNKNOWN_COMMAND", "Unknown command type: " + type, reqId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String playerId = (String) session.getAttributes().get("playerId");
        String tableId = (String) session.getAttributes().get("tableId");
        log.debug("[WS] Connection closed: {} (player: {}, table: {})", session.getId(), playerId, tableId);
        rateLimitingService.reset(session.getId());
        if (session.getId() != null) {
            openSessions.remove(session.getId());
        }
        if (relay != null) {
            relay.close(session);
        }

        if (tableId != null && playerId != null) {
            tableManager.getTable(tableId).ifPresent(actor -> actor.unregisterSession(playerId, session));
        }
    }

    /**
     * Paid tables are created only by matchmaking. A player may act there only if matchmaking
     * routed them to it or they already hold a seat (reconnect after the binding was cleared).
     */
    private boolean isAllowedAtMatchmadeTable(String playerId, String tableId) {
        Optional<TableActor> actor = tableManager.getTable(tableId);
        if (actor.isEmpty()) {
            return false;
        }
        if (actor.get().getState().getPlayer(playerId).isPresent()) {
            return true;
        }
        return routingRegistry.getTableForPlayer(playerId).map(tableId::equals).orElse(false);
    }

    private void sendError(WebSocketSession session, String code, String message, String reqId) {
        if (session.isOpen()) {
            send(session, WsServerMessage.of("ERROR", reqId, null, new WsErrorMessage(code, message, reqId)));
        }
    }

    /**
     * Closes sockets that stop pinging (client pings every 15s; background tabs are throttled to ~1/min),
     * bounds inbound frame size, and caps how long one blocked write can hold the session's sender.
     */
    private void applyTransportLimits(WebSocketSession session) {
        if (!(session instanceof NativeWebSocketSession nativeSession)) {
            return;
        }
        jakarta.websocket.Session container = nativeSession.getNativeSession(jakarta.websocket.Session.class);
        if (container == null) {
            return;
        }
        container.setMaxIdleTimeout(idleTimeoutMs);
        container.setMaxTextMessageBufferSize(MAX_INBOUND_FRAME_BYTES);
        container.getUserProperties().put(TOMCAT_BLOCKING_SEND_TIMEOUT, sendTimeoutMs);
    }

    private void send(WebSocketSession session, WsServerMessage message) {
        try {
            SessionOutbox.send(session, objectMapper.writeValueAsString(message));
        } catch (IOException e) {
            log.error("[WS] Failed to serialize {} frame: {}", message.type(), e.getMessage());
        }
    }

    private List<CardGroup> parseCardGroups(JsonNode groupsNode, List<CardInstance> playerHand) {
        if (groupsNode == null || !groupsNode.isArray()) {
            return List.of(CardGroup.of(playerHand));
        }
        Map<String, CardInstance> handMap = new HashMap<>();
        for (CardInstance c : playerHand) {
            handMap.put(c.getInstanceId(), c);
        }

        List<CardGroup> result = new ArrayList<>();
        for (JsonNode gNode : groupsNode) {
            List<CardInstance> groupCards = new ArrayList<>();
            JsonNode cardsArray = gNode.isArray() ? gNode : (gNode.has("cards") ? gNode.get("cards") : null);
            if (cardsArray != null && cardsArray.isArray()) {
                for (JsonNode cNode : cardsArray) {
                    String id = cNode.isTextual() ? cNode.asText() : (cNode.has("instanceId") ? cNode.get("instanceId").asText() : null);
                    if (id != null) {
                        CardInstance ci = handMap.get(id);
                        if (ci != null) {
                            groupCards.add(ci);
                        }
                    }
                }
            }
            if (!groupCards.isEmpty()) {
                result.add(CardGroup.of(groupCards));
            }
        }
        return result;
    }
}
