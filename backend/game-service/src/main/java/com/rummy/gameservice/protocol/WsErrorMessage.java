package com.rummy.gameservice.protocol;

import java.io.Serializable;

/**
 * Error envelope conforming to Section 58 of the master specification.
 */
public record WsErrorMessage(
        String errorCode,
        String message,
        String requestId
) implements Serializable {}
