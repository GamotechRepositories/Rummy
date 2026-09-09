package com.rummy.gameservice.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WsProtocolSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("Should serialize and deserialize WsClientMessage correctly")
    void testClientMessageSerialization() throws Exception {
        String json = """
                {
                  "type": "JOIN_TABLE",
                  "requestId": "req-123",
                  "tableId": "T1",
                  "payload": {
                    "displayName": "Alice",
                    "seatIndex": 0
                  }
                }
                """;

        WsClientMessage msg = objectMapper.readValue(json, WsClientMessage.class);

        assertThat(msg.type()).isEqualTo("JOIN_TABLE");
        assertThat(msg.requestId()).isEqualTo("req-123");
        assertThat(msg.tableId()).isEqualTo("T1");
        assertThat(msg.payload().get("displayName").asText()).isEqualTo("Alice");
        assertThat(msg.payload().get("seatIndex").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should serialize WsServerMessage correctly")
    void testServerMessageSerialization() throws Exception {
        WsServerMessage msg = WsServerMessage.of("CONNECTED", "req-1", "T1", 42L, Map.of("status", "OK"));
        String json = objectMapper.writeValueAsString(msg);

        assertThat(json).contains("\"type\":\"CONNECTED\"");
        assertThat(json).contains("\"sequence\":42");
        assertThat(json).contains("\"status\":\"OK\"");
    }

    @Test
    @DisplayName("Should serialize WsErrorMessage correctly")
    void testErrorMessageSerialization() throws Exception {
        WsErrorMessage err = new WsErrorMessage("INVALID_TURN", "It is not your turn.", "req-99");
        String json = objectMapper.writeValueAsString(err);

        assertThat(json).contains("\"errorCode\":\"INVALID_TURN\"");
        assertThat(json).contains("\"message\":\"It is not your turn.\"");
        assertThat(json).contains("\"requestId\":\"req-99\"");
    }
}
