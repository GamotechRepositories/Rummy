package com.rummy.gameservice.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates every {@code /api/**} request.
 * Player endpoints need a Bearer JWT; operator endpoints need the {@code X-Admin-Key} header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAuthFilter extends OncePerRequestFilter {

    static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/guest",
            "/api/auth/refresh",
            "/api/auth/verify"
    );

    private static final List<String> ADMIN_PREFIXES = List.of(
            "/api/admin/",
            "/api/fraud/",
            "/api/wallet/platform-revenue"
    );

    private final JwtService jwtService;
    private final boolean enforceJwt;
    private final byte[] adminApiKey;

    public ApiAuthFilter(JwtService jwtService,
                         @Value("${rummy.security.enforce-jwt:true}") boolean enforceJwt,
                         @Value("${rummy.security.admin-api-key:}") String adminApiKey) {
        this.jwtService = jwtService;
        this.enforceJwt = enforceJwt;
        this.adminApiKey = adminApiKey == null || adminApiKey.isBlank()
                ? null
                : adminApiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        if (PUBLIC_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (ADMIN_PREFIXES.stream().anyMatch(path::startsWith)) {
            if (!isValidAdminKey(request.getHeader(ADMIN_KEY_HEADER))) {
                reject(response, HttpStatus.FORBIDDEN, "Admin key required");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        Optional<Claims> claims = bearerToken(request).flatMap(jwtService::validateAndGetClaims);
        if (claims.isPresent()) {
            request.setAttribute(AuthenticatedPlayer.REQUEST_ATTRIBUTE, claims.get().getSubject());
        } else if (enforceJwt) {
            reject(response, HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isValidAdminKey(String provided) {
        if (adminApiKey == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(adminApiKey, provided.getBytes(StandardCharsets.UTF_8));
    }

    static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            return token.isEmpty() ? Optional.empty() : Optional.of(token);
        }
        return Optional.empty();
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"error\":\"" + status.name() + "\",\"message\":\"" + message + "\"}");
    }
}
