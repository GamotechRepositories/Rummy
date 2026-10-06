package com.rummy.gameservice.operator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * HMAC-SHA256 request signing shared with operators, in both directions:
 * {@code X-Rummy-Signature = hex(HMAC_SHA256(secret, X-Rummy-Timestamp + "." + rawBody))}.
 */
public final class RequestSignature {

    public static final String OPERATOR_HEADER = "X-Rummy-Operator";
    public static final String TIMESTAMP_HEADER = "X-Rummy-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Rummy-Signature";
    /** Requests older or newer than this are rejected, so a captured request cannot be replayed later. */
    public static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private RequestSignature() {
    }

    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static boolean verify(String secret, String timestamp, String body, String signature, Instant now) {
        if (secret == null || timestamp == null || signature == null || body == null) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(seconds), now).abs().compareTo(MAX_CLOCK_SKEW) > 0) {
            return false;
        }
        byte[] expected = sign(secret, timestamp.trim(), body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    public static String timestamp(Instant now) {
        return Long.toString(now.getEpochSecond());
    }
}
