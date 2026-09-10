package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Decision-matrix unit test for {@link ImpersonationGuard}. Pins exactly which requests the
 * read-only write-block applies to. The escape carve-out cases here are the regression guard for the
 * "operator trapped in impersonation" defect: {@code POST /auth/impersonate:exit|logout|refresh} must
 * pass even with an {@code act} claim and no allow-writes header, while {@code POST /auth/impersonate}
 * (begin) must stay blocked.
 */
class ImpersonationGuardTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ImpersonationGuard guard = new ImpersonationGuard(objectMapper);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(boolean impersonating) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "ES256")
                .subject("1")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(900));
        if (impersonating) {
            builder.claim("act", Map.of("sub", "99"));
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build()));
    }

    private record Outcome(
            boolean proceeded, int status, @Nullable String contentType, String body) {}

    private Outcome run(String method, String path, boolean impersonating, boolean allowWrites) throws Exception {
        authenticate(impersonating);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        if (allowWrites) {
            request.addHeader(ImpersonationGuard.ALLOW_WRITES_HEADER, "true");
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] proceeded = {false};
        guard.doFilter(request, response, (req, res) -> proceeded[0] = true);
        return new Outcome(
                proceeded[0], response.getStatus(), response.getContentType(), response.getContentAsString());
    }

    @Test
    void blocksNonSafeWriteUnderImpersonationWithoutHeader() throws Exception {
        Outcome o = run("POST", "/user", true, false);
        assertThat(o.proceeded()).isFalse();
        assertThat(o.status()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(o.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = objectMapper.readTree(o.body());
        assertThat(problem.get("title").asString()).isEqualTo("Forbidden");
        assertThat(problem.get("instance").asString()).isEqualTo("/user");
        assertThat(problem.findValue("code").asString()).isEqualTo("impersonation_read_only");
    }

    @Test
    void allowsWriteUnderImpersonationWithConfirmationHeader() throws Exception {
        assertThat(run("POST", "/user", true, true).proceeded()).isTrue();
    }

    @Test
    void allowsSafeMethodUnderImpersonation() throws Exception {
        assertThat(run("GET", "/user", true, false).proceeded()).isTrue();
    }

    @Test
    void allowsWriteWhenNotImpersonating() throws Exception {
        assertThat(run("POST", "/user", false, false).proceeded()).isTrue();
    }

    @Test
    void allowsLifecycleEscapeEndpointsUnderImpersonationWithoutHeader() throws Exception {
        // The fix: an operator must always be able to escape a read-only impersonation session.
        assertThat(run("POST", "/auth/impersonate:exit", true, false).proceeded())
                .isTrue();
        assertThat(run("POST", "/auth/logout", true, false).proceeded()).isTrue();
        assertThat(run("POST", "/auth/refresh", true, false).proceeded()).isTrue();
    }

    @Test
    void stillBlocksImpersonateBeginUnderImpersonation() throws Exception {
        // Begin is privilege-granting and is deliberately NOT an escape path — it must stay blocked.
        assertThat(run("POST", "/auth/impersonate", true, false).proceeded()).isFalse();
    }
}
