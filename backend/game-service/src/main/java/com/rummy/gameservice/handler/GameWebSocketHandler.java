package com.rummy.gameservice.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.engine.command.*;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.rules.CardGroup;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.protocol.WsClientMessage;
import com.rummy.gameservice.protocol.WsErrorMessage;
import com.rummy.gameservice.protocol.WsServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public GameWebSocketHandler(TableManager tableManager, ObjectMapper objectMapper) {
        this.tableManager = Objects.requireNonNull(tableManager);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("[WS] New connection established: {}", session.getId());
        WsServerMessage connectedMsg = WsServerMessage.of("CONNECTED", UUID.randomUUID().toString(), null,
                Map.of("sessionId", session.getId(), "serverTime", Instant.now().toString()));
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(connectedMsg)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
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

        TableActor tableActor = tableManager.getOrCreateTable(tableId, null);
        tableActor.registerSession(playerId, session);
        session.getAttributes().put("tableId", tableId);

        Instant now = Instant.now();
        String gameId = tableActor.getState().getGameId();
        JsonNode data = clientMsg.payload();

        switch (type.toUpperCase()) {
            case "JOIN_TABLE" -> {
                String name = data != null && data.has("displayName") ? data.get("displayName").asText() : playerId;
                int seat = data != null && data.has("seatIndex") ? data.get("seatIndex").asInt() : 0;
                boolean isBot = data != null && data.has("isBot") && data.get("isBot").asBoolean();
                JoinCommand cmd = new JoinCommand(reqId, gameId, playerId, name, seat, isBot, now);
                tableActor.processCommand(cmd, reqId);
            }
            case "READY" -> {
                ReadyCommand cmd = new ReadyCommand(reqId, gameId, playerId, now);
                tableActor.processCommand(cmd, reqId);
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
