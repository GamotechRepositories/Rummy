package com.rummy.gameservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiAuthFilterTest {

    private static final String SECRET = "SecretSigningKeyForTestingPurposesMustBeAtLeast32BytesLong!";

    private JwtService jwtService;
    private ApiAuthFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 1);
        filter = new ApiAuthFilter(jwtService, true, "admin-key-123");
    }

    @Test
    void rejectsPlayerEndpointWithoutToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallet/balance");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void validTokenSetsAuthenticatedPlayer() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallet/balance");
        request.addHeader("Authorization", "Bearer " + jwtService.generateToken("USR_ALICE", "Alice"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(AuthenticatedPlayer.REQUEST_ATTRIBUTE)).isEqualTo("USR_ALICE");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        JwtService attacker = new JwtService("AttackerControlledSigningKeyThatIsAlso32BytesLong", 1);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallet/balance");
        request.addHeader("Authorization", "Bearer " + attacker.generateToken("USR_ALICE", "Alice"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void guestEndpointIsPublic() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/guest");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void adminEndpointNeedsAdminKeyEvenWithPlayerToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/drain");
        request.addHeader("Authorization", "Bearer " + jwtService.generateToken("USR_ALICE", "Alice"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void adminEndpointAcceptsCorrectKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/diagnostics");
        request.addHeader(ApiAuthFilter.ADMIN_KEY_HEADER, "admin-key-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void adminEndpointsDisabledWhenNoKeyConfigured() throws Exception {
        ApiAuthFilter noAdmin = new ApiAuthFilter(jwtService, true, "");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/diagnostics");
        request.addHeader(ApiAuthFilter.ADMIN_KEY_HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        noAdmin.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void claimedPlayerIdMustMatchToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedPlayer.REQUEST_ATTRIBUTE, "USR_ALICE");

        assertThat(AuthenticatedPlayer.resolve(request, null)).isEqualTo("USR_ALICE");
        assertThat(AuthenticatedPlayer.resolve(request, "USR_ALICE")).isEqualTo("USR_ALICE");
        assertThatThrownBy(() -> AuthenticatedPlayer.resolve(request, "USR_BOB"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void shortSecretIsRefused() {
        assertThatThrownBy(() -> new JwtService("too-short", 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingSecretIsRefusedWhenRequired() {
        assertThatThrownBy(() -> new JwtService("", 1, true))
                .isInstanceOf(IllegalStateException.class);
    }
}
