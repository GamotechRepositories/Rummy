package com.rummy.gameservice.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Authenticates every {@code /api/**} request and rate-limits sensitive REST endpoints.
 * Player endpoints need a Bearer JWT; admin endpoints need the {@code X-Admin-Key} header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAuthFilter extends OncePerRequestFilter {

    static final String ADMIN_KEY_HEADER = "X-Admin-Key";
    public static final String AUTHENTICATED_CLAIMS_ATTRIBUTE = "rummy.authenticatedClaims";

    /** Launch and token-validation routes only; all player/room/tournament routes require JWT. */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/refresh",
            "/api/auth/verify",
            "/api/auth/config",
            "/api/operator/launch",
            "/api/operator/session"
    );

    private static final List<String> ADMIN_PREFIXES = List.of(
            "/api/admin/",
            "/api/fraud/",
            "/api/wallet/platform-revenue"
    );

    private final JwtService jwtService;
    private final boolean enforceJwt;
    private final byte[] adminApiKey;
    private final RateLimitingService httpRateLimiter;

    public ApiAuthFilter(JwtService jwtService,
                         boolean enforceJwt,
                         String adminApiKey) {
        this(jwtService, enforceJwt, adminApiKey, 30, 10.0);
    }

    @Autowired
    public ApiAuthFilter(JwtService jwtService,
                         @Value("${rummy.security.enforce-jwt:true}") boolean enforceJwt,
                         @Value("${rummy.security.admin-api-key:}") String adminApiKey,
                         @Value("${rummy.security.http-rate-limit.capacity:30}") int httpRateCapacity,
                         @Value("${rummy.security.http-rate-limit.refill-rate:10.0}") double httpRateRefill) {
        this.jwtService = jwtService;
        this.enforceJwt = enforceJwt;
        this.adminApiKey = adminApiKey == null || adminApiKey.isBlank()
                ? null
                : adminApiKey.getBytes(StandardCharsets.UTF_8);
        this.httpRateLimiter = httpRateCapacity > 0 && httpRateRefill > 0
                ? new RateLimitingService(httpRateCapacity, httpRateRefill)
                : null;
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
            request.setAttribute(AUTHENTICATED_CLAIMS_ATTRIBUTE, claims.get());
        } else if (enforceJwt && !PUBLIC_PATHS.contains(path)) {
            reject(response, HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token");
            return;
        }

        if (httpRateLimiter != null && isRateLimitedMutation(request.getMethod(), path)) {
            String key = claims.map(c -> "player:" + c.getSubject())
                    .orElseGet(() -> "ip:" + resolveClientIp(request));
            if (!httpRateLimiter.tryAcquire(key)) {
                reject(response, HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please slow down.");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private static boolean isRateLimitedMutation(String method, String path) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return path.equals("/api/operator/session")
                || path.equals("/api/auth/verify")
                || path.equals("/api/auth/refresh")
                || path.equals("/api/matchmaking/join")
                || path.equals("/api/rooms/create")
                || path.startsWith("/api/rooms/join/")
                || (path.startsWith("/api/tournaments/") && (path.endsWith("/register") || path.endsWith("/checkin")));
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
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
