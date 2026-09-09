package com.rummy.gameservice.protocol;

import java.io.Serializable;

/**
 * Outbound message frame dispatched from game server to connected WebSocket client.
 */
public record WsServerMessage(
        String type,
        String requestId,
        String tableId,
        Long sequence,
        Object payload
) implements Serializable {

    public static WsServerMessage of(String type, String requestId, String tableId, Long sequence, Object payload) {
        return new WsServerMessage(type, requestId, tableId, sequence, payload);
    }

    public static WsServerMessage of(String type, String requestId, String tableId, Object payload) {
        return new WsServerMessage(type, requestId, tableId, null, payload);
    }
}
