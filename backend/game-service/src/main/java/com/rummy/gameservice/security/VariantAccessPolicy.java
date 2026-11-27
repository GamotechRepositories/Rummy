package com.rummy.gameservice.security;

import com.rummy.engine.rules.RulesetRegistry;
import com.rummy.engine.rules.RummyRules;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;

/**
 * Optional origin- and launch-gameCode-based ruleset isolation when one backend serves
 * multiple single-variant frontends (e.g. Points, Pool, Deals, 21-Card).
 * When no mapping matches the request, all offered rulesets remain enabled.
 */
@Service
public class VariantAccessPolicy {

    public record FilterScope(boolean restricted, Set<String> rulesets) {
        public static final FilterScope UNRESTRICTED = new FilterScope(false, Set.of());

        public boolean allows(String rulesetId) {
            if (!restricted) return true;
            if (rulesetId == null || rulesetId.isBlank()) return false;
            return rulesets.contains(canonicalUpper(rulesetId));
        }
    }

    private final Map<String, Set<String>> originRulesets;
    private final Map<String, Set<String>> gameCodeRulesets;

    @Autowired
    public VariantAccessPolicy(
            @Value("${rummy.security.origin-rulesets:}") String originRulesetsRaw,
            @Value("${rummy.provider.game-code-rulesets:}") String gameCodeRulesetsRaw) {
        this.originRulesets = parseMapping(originRulesetsRaw, true);
        this.gameCodeRulesets = parseMapping(gameCodeRulesetsRaw, false);
    }

    public VariantAccessPolicy() {
        this("", "");
    }

    public Optional<Set<String>> allowedRulesetsFor(HttpServletRequest request) {
        if (request == null || (originRulesets.isEmpty() && gameCodeRulesets.isEmpty())) {
            return Optional.empty();
        }
        String rawOrigin = request.getHeader("Origin");
        if (rawOrigin == null || rawOrigin.isBlank()) {
            rawOrigin = request.getHeader("Referer");
        }
        String rawGameCode = null;
        Object rawClaims = request.getAttribute(ApiAuthFilter.AUTHENTICATED_CLAIMS_ATTRIBUTE);
        if (rawClaims instanceof Claims claims) {
            rawGameCode = claims.get(JwtService.GAME_CODE_CLAIM, String.class);
        }
        return allowedRulesetsFor(rawOrigin, rawGameCode);
    }

    /**
     * Resolves allowed rulesets directly from normalized/raw Origin/Referer and JWT {@code gameCode}.
     * Used by WebSocket handlers where a {@link HttpServletRequest} is no longer active.
     */
    public Optional<Set<String>> allowedRulesetsFor(String rawOriginOrReferer, String rawGameCode) {
        if (originRulesets.isEmpty() && gameCodeRulesets.isEmpty()) {
            return Optional.empty();
        }

        Set<String> resolved = null;

        if (!gameCodeRulesets.isEmpty() && rawGameCode != null && !rawGameCode.isBlank()) {
            Set<String> mapped = gameCodeRulesets.get(rawGameCode.trim().toUpperCase(Locale.ROOT));
            if (mapped != null) {
                resolved = new LinkedHashSet<>(mapped);
            }
        }

        if (!originRulesets.isEmpty()) {
            String origin = normalizeOrigin(rawOriginOrReferer);
            if (origin != null) {
                Set<String> mapped = originRulesets.get(origin);
                if (mapped != null) {
                    if (resolved == null) {
                        resolved = new LinkedHashSet<>(mapped);
                    } else {
                        resolved.retainAll(mapped);
                    }
                }
            }
        }

        return resolved == null ? Optional.empty() : Optional.of(Collections.unmodifiableSet(resolved));
    }

    public boolean isRulesetAllowed(HttpServletRequest request, String rulesetId) {
        return isRulesetAllowed(allowedRulesetsFor(request), rulesetId);
    }

    public boolean isRulesetAllowed(String rawOriginOrReferer, String rawGameCode, String rulesetId) {
        return isRulesetAllowed(allowedRulesetsFor(rawOriginOrReferer, rawGameCode), rulesetId);
    }

    private static boolean isRulesetAllowed(Optional<Set<String>> allowed, String rulesetId) {
        if (allowed.isEmpty()) {
            return true;
        }
        String canonical = canonicalUpper(rulesetId);
        return !canonical.isEmpty() && allowed.get().contains(canonical);
    }

    /**
     * Resolves an optional comma-separated query filter intersected with the request's policy scope.
     * Distinguishes "unrestricted" from "restricted to empty" so tampered query params never widen access.
     */
    public FilterScope resolveScope(HttpServletRequest request, String requestedCsv) {
        Set<String> requested = parseCsv(requestedCsv);
        Optional<Set<String>> policy = allowedRulesetsFor(request);

        if (policy.isEmpty()) {
            return requested.isEmpty()
                    ? FilterScope.UNRESTRICTED
                    : new FilterScope(true, Collections.unmodifiableSet(requested));
        }

        Set<String> allowed = new LinkedHashSet<>(policy.get());
        if (!requested.isEmpty()) {
            allowed.retainAll(requested);
        }
        return new FilterScope(true, Collections.unmodifiableSet(allowed));
    }

    public static Set<String> parseCsv(String csv) {
        Set<String> parsed = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return parsed;
        }
        for (String token : csv.split(",")) {
            String canonical = canonicalUpper(token);
            if (!canonical.isEmpty()) {
                parsed.add(canonical);
            }
        }
        return parsed;
    }

    public static String canonicalUpper(String rawRuleset) {
        if (rawRuleset == null || rawRuleset.isBlank()) {
            return "";
        }
        String trimmed = rawRuleset.trim().toUpperCase(Locale.ROOT);
        return RulesetRegistry.getRuleset(trimmed)
                .map(RummyRules::getRulesetId)
                .map(id -> id.toUpperCase(Locale.ROOT))
                .orElse(trimmed);
    }

    private static Map<String, Set<String>> parseMapping(String raw, boolean normalizeAsOrigin) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (String entry : raw.split("[;\\n]")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq >= trimmed.length() - 1) continue;
            String rawKey = trimmed.substring(0, eq).trim();
            String key = normalizeAsOrigin
                    ? normalizeOrigin(rawKey)
                    : rawKey.toUpperCase(Locale.ROOT);
            if (key == null || key.isBlank()) continue;
            Set<String> values = parseCsv(trimmed.substring(eq + 1));
            if (!values.isEmpty()) {
                map.put(key, Collections.unmodifiableSet(values));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    static String normalizeOrigin(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            return port > 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
