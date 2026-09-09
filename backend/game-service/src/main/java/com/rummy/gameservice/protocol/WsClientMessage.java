package com.rummy.gameservice.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.Serializable;

/**
 * Inbound message frame sent by client over WebSocket.
 * Conforms to Section 16 & 57 of the master specification.
 */
public record WsClientMessage(
        String type,
        String requestId,
        String tableId,
        JsonNode payload
) implements Serializable {}
