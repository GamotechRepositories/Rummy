package com.rummy.gameservice.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.engine.command.ReadyCommand;
import com.rummy.engine.rules.RulesetRegistry;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.security.JwtHandshakeInterceptor;
import com.rummy.gameservice.security.JwtService;
import com.rummy.gameservice.security.RateLimitingService;
import com.rummy.gameservice.security.VariantAccessPolicy;
import com.rummy.gameservice.session.PlayerSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("GameWebSocketHandler Integration Tests")
class GameWebSocketHandlerTest {

    private static final long SEND_WAIT_MS = 2000;

    private ObjectMapper objectMapper;
    private TableManager tableManager;
    private GameWebSocketHandler handler;
    private WebSocketSession session;
    private Map<String, Object> sessionAttributes;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        tableManager = new TableManager(objectMapper);
        RateLimitingService rateLimitingService = new RateLimitingService(100, 100.0);
        TableRoutingRegistry routingRegistry = new TableRoutingRegistry(null, "test-server");
        PlayerSessionService sessionService = new PlayerSessionService(routingRegistry, tableManager);
        handler = new GameWebSocketHandler(tableManager, objectMapper, rateLimitingService, routingRegistry, sessionService);

        session = mock(WebSocketSession.class);
        sessionAttributes = new HashMap<>();
        when(session.getId()).thenReturn("test-session-1234");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(sessionAttributes);
    }

    @Test
    @DisplayName("afterConnectionEstablished sends CONNECTED message with session details")
    void testConnectionEstablished() throws Exception {
        handler.afterConnectionEstablished(session);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(SEND_WAIT_MS).times(1)).sendMessage(captor.capture());

        String payload = captor.getValue().getPayload();
        JsonNode root = objectMapper.readTree(payload);

        assertThat(root.get("type").asText()).isEqualTo("CONNECTED");
        assertThat(root.get("payload").get("sessionId").asText()).isEqualTo("test-session-1234");
    }

    @Test
    @DisplayName("PING request responds with PONG and timestamp")
    void testPingPong() throws Exception {
        String pingJson = """
                {
                    "type": "PING",
                    "requestId": "req_ping_1"
                }
                """;

        handler.handleTextMessage(session, new TextMessage(pingJson));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(SEND_WAIT_MS).times(1)).sendMessage(captor.capture());

        JsonNode root = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(root.get("type").asText()).isEqualTo("PONG");
        assertThat(root.get("requestId").asText()).isEqualTo("req_ping_1");
        assertThat(root.get("payload").has("timestamp")).isTrue();
    }

    @Test
    @DisplayName("AUTH sets playerId in session and responds with AUTH_SUCCESS")
    void testAuth() throws Exception {
        String authJson = """
                {
                    "type": "AUTH",
                    "requestId": "req_auth_1",
                    "payload": {
                        "playerId": "USR_AUS_007"
                    }
                }
                """;

        handler.handleTextMessage(session, new TextMessage(authJson));

        assertThat(sessionAttributes.get("playerId")).isEqualTo("USR_AUS_007");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(SEND_WAIT_MS).times(1)).sendMessage(captor.capture());

        JsonNode root = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(root.get("type").asText()).isEqualTo("AUTH_SUCCESS");
        assertThat(root.get("payload").get("playerId").asText()).isEqualTo("USR_AUS_007");
    }

    @Test
    @DisplayName("Action without authentication returns UNAUTHORIZED error")
    void testUnauthorizedAction() throws Exception {
        String joinJson = """
                {
                    "type": "JOIN_TABLE",
                    "tableId": "TBL_TEST_01",
                    "requestId": "req_join_unauth"
                }
                """;

        handler.handleTextMessage(session, new TextMessage(joinJson));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(SEND_WAIT_MS).times(1)).sendMessage(captor.capture());

        JsonNode root = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(root.get("type").asText()).isEqualTo("ERROR");
        assertThat(root.get("payload").get("errorCode").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("Authenticated player joins table and receives TABLE_SNAPSHOT and COMMAND_RESULT")
    void testAuthenticatedJoinTable() throws Exception {
        sessionAttributes.put("playerId", "USR_MATT");

        String joinJson = """
                {
                    "type": "JOIN_TABLE",
                    "tableId": "TBL_GOLD_01",
                    "requestId": "req_join_1",
                    "payload": {
                        "displayName": "Matt Smith",
                        "seatIndex": 0,
                        "isBot": false
                    }
                }
                """;

        handler.handleTextMessage(session, new TextMessage(joinJson));

        TableActor table = tableManager.getTable("TBL_GOLD_01").orElse(null);
        assertThat(table).isNotNull();
        assertThat(table.getState().getPlayers()).hasSize(1);
        assertThat(table.getState().getPlayers().get(0).getPlayerId()).isEqualTo("USR_MATT");

        // Frames are delivered asynchronously but in order: the join event, then the player's view
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(SEND_WAIT_MS).atLeast(2)).sendMessage(captor.capture());

        java.util.List<String> types = new java.util.ArrayList<>();
        for (TextMessage msg : captor.getAllValues()) {
            types.add(objectMapper.readTree(msg.getPayload()).get("type").asText());
        }
        assertThat(types.subList(0, 2)).containsExactly("GAME_EVENT", "GAME_VIEW");
    }

    @Test
    @DisplayName("With JWT enforced, AUTH cannot override the token identity")
    void testAuthCannotImpersonateWhenJwtEnforced() throws Exception {
        GameWebSocketHandler secured = securedHandler();
        sessionAttributes.put("authenticatedPlayerId", "USR_REAL");

        secured.handleTextMessage(session, new TextMessage("""
                {"type": "AUTH", "requestId": "r1", "payload": {"playerId": "USR_VICTIM"}}
                """));

        assertThat(sessionAttributes.get("playerId")).isEqualTo("USR_REAL");
    }

    @Test
    @DisplayName("With JWT enforced, an unauthenticated AUTH is rejected")
    void testUnauthenticatedAuthRejectedWhenJwtEnforced() throws Exception {
        GameWebSocketHandler secured = securedHandler();

        secured.handleTextMessage(session, new TextMessage("""
                {"type": "AUTH", "requestId": "r1", "payload": {"playerId": "USR_VICTIM"}}
                """));

        assertThat(sessionAttributes.get("playerId")).isNull();
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(SEND_WAIT_MS)).sendMessage(captor.capture());
        assertThat(objectMapper.readTree(captor.getValue().getPayload()).get("payload").get("errorCode").asText())
                .isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("A player cannot join a matchmade table they were not routed to")
    void testCannotJoinForeignMatchmadeTable() throws Exception {
        tableManager.getOrCreateTable("TBL_MM_paid0001", null);
        sessionAttributes.put("playerId", "USR_INTRUDER");

        handler.handleTextMessage(session, new TextMessage("""
                {"type": "JOIN_TABLE", "tableId": "TBL_MM_paid0001", "requestId": "r1",
                 "payload": {"displayName": "Intruder", "seatIndex": 0}}
                """));

        assertThat(tableManager.getTable("TBL_MM_paid0001").orElseThrow().getState().getPlayers()).isEmpty();
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(SEND_WAIT_MS)).sendMessage(captor.capture());
        assertThat(objectMapper.readTree(captor.getValue().getPayload()).get("payload").get("errorCode").asText())
                .isEqualTo("FORBIDDEN");
    }

    private GameWebSocketHandler securedHandler() {
        TableRoutingRegistry routingRegistry = new TableRoutingRegistry(null, "test-server");
        return new GameWebSocketHandler(tableManager, objectMapper, new RateLimitingService(100, 100.0),
                routingRegistry, new PlayerSessionService(routingRegistry, tableManager), true);
    }

    @Test
    @DisplayName("Disconnecting session unregisters from TableActor")
    void testDisconnection() throws Exception {
        sessionAttributes.put("playerId", "USR_DISC");
        sessionAttributes.put("tableId", "TBL_DISC_01");

        TableActor table = tableManager.getOrCreateTable("TBL_DISC_01", null);
        table.registerSession("USR_DISC", session);

        reset(session); // clear previous invocations

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // Process a dummy command on the table, session should not receive messages anymore
        table.processCommand(new ReadyCommand("req_dummy", table.getState().getGameId(), "USR_DISC", java.time.Instant.now()), "req_dummy");
        verify(session, after(300).never()).sendMessage(any());
    }

    @Test
    @DisplayName("Private and tournament tables reject unseated intruders and missing ghost tables")
    void testPrivateAndTournamentTableGuards() throws Exception {
        tableManager.getOrCreateTable("TBL_PRV_ABC123", RulesetRegistry.requireRuleset("POOL_101"));
        sessionAttributes.put("playerId", "USR_INTRUDER");

        handler.handleTextMessage(session, new TextMessage("""
                {"type": "JOIN_TABLE", "tableId": "TBL_PRV_ABC123", "requestId": "r_prv_intruder",
                 "payload": {"displayName": "Intruder", "seatIndex": 0}}
                """));

        ArgumentCaptor<TextMessage> firstCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(SEND_WAIT_MS).times(1)).sendMessage(firstCaptor.capture());
        assertThat(objectMapper.readTree(firstCaptor.getValue().getPayload()).get("payload").get("errorCode").asText())
                .isEqualTo("FORBIDDEN");
        assertThat(tableManager.getTable("TBL_PRV_ABC123").orElseThrow().getState().getPlayers()).isEmpty();

        handler.handleTextMessage(session, new TextMessage("""
                {"type": "JOIN_TABLE", "tableId": "TBL_TRN_MISSING_R1_1", "requestId": "r_trn_missing",
                 "payload": {"displayName": "Ghost", "seatIndex": 0}}
                """));

        ArgumentCaptor<TextMessage> allCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(SEND_WAIT_MS).times(2)).sendMessage(allCaptor.capture());
        assertThat(objectMapper.readTree(allCaptor.getAllValues().get(1).getPayload()).get("type").asText())
                .isEqualTo("TABLE_CLOSED");
        assertThat(tableManager.getTable("TBL_TRN_MISSING_R1_1")).isEmpty();
    }

    @Test
    @DisplayName("VariantAccessPolicy blocks cross-variant WebSocket JOIN_TABLE while allowing matching origin/gameCode")
    void testWebSocketVariantIsolation() throws Exception {
        VariantAccessPolicy policy = new VariantAccessPolicy(
                "https://points.example.com=POINTS_13;https://pool.example.com=POOL_101,POOL_201",
                "POINTS=POINTS_13;POOL=POOL_101,POOL_201"
        );
        TableRoutingRegistry routingRegistry = new TableRoutingRegistry(null, "test-server");
        GameWebSocketHandler isolated = new GameWebSocketHandler(
                tableManager, objectMapper, new RateLimitingService(100, 100.0),
                routingRegistry, new PlayerSessionService(routingRegistry, tableManager), true);
        isolated.setVariantAccessPolicy(policy);

        tableManager.getOrCreateTable("TBL_MM_POOL1", RulesetRegistry.requireRuleset("POOL_101"));
        routingRegistry.registerPlayerTable("USR_P1", "TBL_MM_POOL1");

        // Wrong frontend Origin + gameCode tries to join Pool table -> FORBIDDEN
        sessionAttributes.put("authenticatedPlayerId", "USR_P1");
        sessionAttributes.put("playerId", "USR_P1");
        sessionAttributes.put(JwtHandshakeInterceptor.HANDSHAKE_ORIGIN_ATTRIBUTE, "https://points.example.com");
        sessionAttributes.put(JwtHandshakeInterceptor.AUTH_GAME_CODE_ATTRIBUTE, "POINTS");

        isolated.handleTextMessage(session, new TextMessage("""
                {"type": "JOIN_TABLE", "tableId": "TBL_MM_POOL1", "requestId": "r_cross",
                 "payload": {"displayName": "Player One", "seatIndex": 0}}
                """));

        ArgumentCaptor<TextMessage> deniedCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(SEND_WAIT_MS).times(1)).sendMessage(deniedCaptor.capture());
        assertThat(objectMapper.readTree(deniedCaptor.getValue().getPayload()).get("payload").get("errorCode").asText())
                .isEqualTo("FORBIDDEN");
        assertThat(tableManager.getTable("TBL_MM_POOL1").orElseThrow().getState().getPlayers()).isEmpty();

        // Matching Pool Origin + gameCode joins cleanly
        sessionAttributes.put(JwtHandshakeInterceptor.HANDSHAKE_ORIGIN_ATTRIBUTE, "https://pool.example.com");
        sessionAttributes.put(JwtHandshakeInterceptor.AUTH_GAME_CODE_ATTRIBUTE, "POOL");

        isolated.handleTextMessage(session, new TextMessage("""
                {"type": "JOIN_TABLE", "tableId": "TBL_MM_POOL1", "requestId": "r_ok",
                 "payload": {"displayName": "Player One", "seatIndex": 0}}
                """));

        assertThat(tableManager.getTable("TBL_MM_POOL1").orElseThrow().getState().getPlayers()).hasSize(1);
    }

    @Test
    @DisplayName("JwtHandshakeInterceptor captures normalized Origin and JWT gameCode into WebSocket attributes")
    void testHandshakeCapturesOriginAndGameCode() {
        JwtService jwtService = new JwtService("SecretSigningKeyForTestingPurposesMustBeAtLeast32BytesLong!", 1);
        String token = jwtService.generateToken("USR_42", "Riya", "OP1", "DEALS", java.time.Duration.ofHours(1));
        JwtHandshakeInterceptor interceptor = new JwtHandshakeInterceptor(jwtService, true);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/game");
        req.setQueryString("token=" + token);
        req.addHeader("Origin", "https://deals.example.com");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        Map<String, Object> attrs = new HashMap<>();
        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(req), new ServletServerHttpResponse(resp), handler, attrs);

        assertThat(allowed).isTrue();
        assertThat(attrs.get("authenticatedPlayerId")).isEqualTo("USR_42");
        assertThat(attrs.get(JwtHandshakeInterceptor.HANDSHAKE_ORIGIN_ATTRIBUTE)).isEqualTo("https://deals.example.com");
        assertThat(attrs.get(JwtHandshakeInterceptor.AUTH_GAME_CODE_ATTRIBUTE)).isEqualTo("DEALS");
    }
}
