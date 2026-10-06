package com.rummy.gameservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the caller's identity from the JWT validated by {@link ApiAuthFilter}.
 * A client-supplied playerId is only accepted when it matches the token subject.
 */
public final class AuthenticatedPlayer {

    public static final String REQUEST_ATTRIBUTE = "rummy.authenticatedPlayerId";

    private AuthenticatedPlayer() {
    }

    public static String resolve(HttpServletRequest request, String claimedPlayerId) {
        String authenticated = (String) request.getAttribute(REQUEST_ATTRIBUTE);
        if (authenticated == null) {
            // Only reachable when rummy.security.enforce-jwt=false (local development).
            if (claimedPlayerId == null || claimedPlayerId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
            }
            return claimedPlayerId;
        }
        if (claimedPlayerId != null && !claimedPlayerId.isBlank() && !claimedPlayerId.equals(authenticated)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "playerId does not match the authenticated player");
        }
        return authenticated;
    }
}
