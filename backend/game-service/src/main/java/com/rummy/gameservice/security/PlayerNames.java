package com.rummy.gameservice.security;

import java.security.SecureRandom;

/** Cleans display names before they are put in tokens and shown to other players. */
public final class PlayerNames {

    private static final int MAX_NAME_LENGTH = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PlayerNames() {
    }

    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return fallback();
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}<>\"'&]", "").trim();
        if (cleaned.isEmpty()) {
            return fallback();
        }
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }

    private static String fallback() {
        return "Player_" + Integer.toString(1000 + RANDOM.nextInt(9000));
    }
}
