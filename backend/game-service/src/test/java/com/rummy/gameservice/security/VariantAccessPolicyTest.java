package com.rummy.gameservice.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class VariantAccessPolicyTest {

    @Test
    void enforcesOriginAndTamperedQueryScope() {
        VariantAccessPolicy policy = new VariantAccessPolicy(
                "https://points.example.com=POINTS_13;https://pool.example.com=POOL_101,POOL_201",
                ""
        );

        MockHttpServletRequest pointsReq = new MockHttpServletRequest("POST", "/api/matchmaking/join");
        pointsReq.addHeader("Origin", "https://points.example.com");

        assertThat(policy.isRulesetAllowed(pointsReq, "POINTS_13")).isTrue();
        assertThat(policy.isRulesetAllowed(pointsReq, "POOL_101")).isFalse();

        VariantAccessPolicy.FilterScope tampered = policy.resolveScope(pointsReq, "POOL_101");
        assertThat(tampered.restricted()).isTrue();
        assertThat(tampered.rulesets()).isEmpty();
        assertThat(tampered.allows("POINTS_13")).isFalse();
        assertThat(tampered.allows("POOL_101")).isFalse();

        VariantAccessPolicy.FilterScope normal = policy.resolveScope(pointsReq, null);
        assertThat(normal.restricted()).isTrue();
        assertThat(normal.rulesets()).containsExactly("POINTS_13");
    }

    @Test
    void enforcesJwtGameCodeMappingAndFallsBackCleanlyWhenUnconfigured() {
        VariantAccessPolicy unconfigured = new VariantAccessPolicy("", "");
        MockHttpServletRequest plainReq = new MockHttpServletRequest();
        assertThat(unconfigured.isRulesetAllowed(plainReq, "POOL_101")).isTrue();
        assertThat(unconfigured.resolveScope(plainReq, null).restricted()).isFalse();

        JwtService jwt = new JwtService("SecretSigningKeyForTestingPurposesMustBeAtLeast32BytesLong!", 1);
        String token = jwt.generateToken("P1", "Alice", "OP1", "POINTS", java.time.Duration.ofHours(1));
        Claims claims = jwt.validateAndGetClaims(token).orElseThrow();

        VariantAccessPolicy byCode = new VariantAccessPolicy("", "POINTS=POINTS_13");
        MockHttpServletRequest codeReq = new MockHttpServletRequest();
        codeReq.setAttribute(ApiAuthFilter.AUTHENTICATED_CLAIMS_ATTRIBUTE, claims);

        assertThat(byCode.isRulesetAllowed(codeReq, "POINTS_13")).isTrue();
        assertThat(byCode.isRulesetAllowed(codeReq, "DEALS_3")).isFalse();

        // Direct WebSocket attribute overload (origin + gameCode intersection)
        VariantAccessPolicy combined = new VariantAccessPolicy(
                "https://points.example.com=POINTS_13;https://pool.example.com=POOL_101,POOL_201",
                "POINTS=POINTS_13;POOL=POOL_101,POOL_201"
        );
        assertThat(combined.isRulesetAllowed("https://pool.example.com/lobby", "POOL", "POOL_101")).isTrue();
        assertThat(combined.isRulesetAllowed("https://points.example.com", "POOL", "POOL_101")).isFalse();
        assertThat(combined.isRulesetAllowed("https://points.example.com", "POINTS", "POOL_101")).isFalse();
    }
}
