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
import com.rummy.gameservice.matchmaking.MatchmakingService;
import com.rummy.gameservice.protocol.WsClientMessage;
import com.rummy.gameservice.protocol.WsErrorMessage;
import com.rummy.gameservice.protocol.WsServerMessage;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.security.RateLimitingService;
import com.rummy.gameservice.session.PlayerSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
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

    private final TableManager tableManager;
    private final ObjectMapper objectMapper;
    private final RateLimitingService rateLimitingService;
    private final TableRoutingRegistry routingRegistry;
    private final PlayerSessionService sessionService;

    private MatchmakingService matchmakingService;
    public GameWebSocketHandler(
            TableManager tableManager,
            ObjectMapper objectMapper,
            RateLimitingService rateLimitingService,
            TableRoutingRegistry routingRegistry,
            PlayerSessionService sessionService) {
        this.tableManager = Objects.requireNonNull(tableManager);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.rateLimitingService = Objects.requireNonNull(rateLimitingService);
        this.routingRegistry = Objects.requireNonNull(routingRegistry);
        this.sessionService = Objects.requireNonNull(sessionService);
    }

    @Autowired(required = false)
    public void setMatchmakingService(MatchmakingService matchmakingService) {
        this.matchmakingService = matchmakingService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("[WS] New connection established: {}", session.getId());
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
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(connectedMsg)));
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

        if ("PING".equalsIgnoreCase(type)) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    WsServerMessage.of("PONG", reqId, tableId, Map.of("timestamp", Instant.now().toEpochMilli())))));
            return;
        }

        if ("AUTH".equalsIgnoreCase(type)) {
            String playerId = clientMsg.payload() != null && clientMsg.payload().has("playerId")
                    ? clientMsg.payload().get("playerId").asText()
                    : "P_" + session.getId().substring(0, 6);
            session.getAttributes().put("playerId", playerId);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    WsServerMessage.of("AUTH_SUCCESS", reqId, null, Map.of("playerId", playerId)))));
            return;
        }

        String playerId = (String) session.getAttributes().get("playerId");
        if (playerId == null && clientMsg.payload() != null && clientMsg.payload().has("playerId")) {
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
        if (routingRegistry.isOwnedByRemoteServer(tableId)) {
            String owner = routingRegistry.getServerForTable(tableId).orElse("unknown");
            log.warn("[WS] Table {} owned by remote server {} — rejecting on {}", tableId, owner, routingRegistry.getServerInstanceId());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    WsServerMessage.of("ERROR", reqId, tableId, Map.of(
                            "code", "TABLE_NOT_ON_THIS_SERVER",
                            "message", "This table is hosted on another game server. Reconnect using matchedServerId.",
                            "ownerServerId", owner,
                            "localServerId", routingRegistry.getServerInstanceId()
                    )))));
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
                int seat = data != null && data.has("seatIndex") ? data.get("seatIndex").asInt() : 0;
                boolean isBot = false;
                String targetPlayerId = playerId;
                // Reconnection: If player already seated, re-register session and view
                if (tableActor.getState().getPlayer(targetPlayerId).isPresent()) {
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
                    tableActor.registerBot(targetPlayerId, name, com.rummy.engine.bot.BotDifficulty.MEDIUM);
                }

                JoinCommand cmd = new JoinCommand(reqId, gameId, targetPlayerId, name, finalSeat, isBot, now);
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
                // Voluntary leave — clear resume binding; drop if still active in hand
                var playerOpt = tableActor.getState().getPlayer(playerId);
                if (playerOpt.isPresent() && playerOpt.get().getStatus() == PlayerStatus.ACTIVE
                        && tableActor.getState().getStatus() == GameStatus.IN_PROGRESS) {
                    tableActor.processCommand(new DropCommand(reqId, gameId, playerId, now), "LEAVE_DROP");
                } else if (tableActor.getState().getStatus() == GameStatus.WAITING_FOR_PLAYERS) {
                    tableActor.removeWaitingHuman(playerId);
                    if (matchmakingService != null) {
                        matchmakingService.onWaitingHumanLeft(playerId, tableId);
                    }
                }
                sessionService.clearPlayerBinding(playerId);
                tableActor.unregisterSession(playerId);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                        WsServerMessage.of("LEFT_TABLE", reqId, tableId, Map.of(
                                "playerId", playerId,
                                "tableId", tableId
                        )))));
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
            default -> sendError(session, "UNKNOWN_COMMAND", "Unknown command type: " + type, reqId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String playerId = (String) session.getAttributes().get("playerId");
        String tableId = (String) session.getAttributes().get("tableId");
        log.info("[WS] Connection closed: {} (player: {}, table: {})", session.getId(), playerId, tableId);

        if (tableId != null && playerId != null) {
            tableManager.getTable(tableId).ifPresent(actor -> actor.unregisterSession(playerId));
        }
    }

    private void sendError(WebSocketSession session, String code, String message, String reqId) {
        try {
            if (session.isOpen()) {
                WsErrorMessage err = new WsErrorMessage(code, message, reqId);
                WsServerMessage msg = WsServerMessage.of("ERROR", reqId, null, err);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
            }
        } catch (IOException e) {
            log.error("[WS] Failed to send error frame: {}", e.getMessage());
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
            if (gNode.isArray()) {
                for (JsonNode cNode : gNode) {
                    String id = cNode.isTextual() ? cNode.asText() : cNode.get("instanceId").asText();
                    CardInstance ci = handMap.get(id);
                    if (ci != null) {
                        groupCards.add(ci);
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
