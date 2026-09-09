package com.rummy.gameservice.security;

import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 28: WebSocket Handshake Security Interceptor.
 * Validates cryptographic JWT tokens before allowing WebSocket connection establishment.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);

    private final JwtService jwtService;
    private final boolean enforceJwt;

    @Autowired
    public JwtHandshakeInterceptor(
            JwtService jwtService,
            @Value("${rummy.security.enforce-jwt:false}") boolean enforceJwt) {
        this.jwtService = Objects.requireNonNull(jwtService);
        this.enforceJwt = enforceJwt;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        URI uri = request.getURI();
        String query = uri.getQuery();
        String token = extractQueryParam(query, "token");

        if (token == null && request.getHeaders().containsKey("Authorization")) {
            String authHeader = request.getHeaders().getFirst("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7);
            }
        }

        if (token != null) {
            Optional<Claims> claimsOpt = jwtService.validateAndGetClaims(token);
            if (claimsOpt.isPresent()) {
                Claims claims = claimsOpt.get();
                attributes.put("authenticatedPlayerId", claims.getSubject());
                attributes.put("authenticatedPlayerName", claims.get("name", String.class));
                log.info("[WS-Auth] Authenticated WebSocket handshake for player: {}", claims.getSubject());
                return true;
            } else if (enforceJwt) {
                log.warn("[WS-Auth] Rejected WebSocket connection: Invalid JWT token");
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
        } else if (enforceJwt) {
            log.warn("[WS-Auth] Rejected WebSocket connection: Missing JWT token in handshake");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No-op
    }

    private String extractQueryParam(String query, String paramName) {
        if (query == null || query.isBlank()) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=");
            if (parts.length == 2 && parts[0].equalsIgnoreCase(paramName)) {
                return parts[1];
            }
        }
        return null;
    }
}
