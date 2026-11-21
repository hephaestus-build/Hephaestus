package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ProblemSecurityResponsesTest {

    /** Spring's problem mixin, as the application's mapper has it: extension members at the top level. */
    private final JsonMapper mapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private final ProblemSecurityResponses refusals = new ProblemSecurityResponses(mapper);

    @Test
    void shouldAnswerAMissingSignInWithTheBearerChallengeAndAProblem() throws Exception {
        var response = new MockHttpServletResponse();
        response.setHeader("X-Request-Id", "0123456789abcdef0123456789abcdef");

        refusals.commence(
                new MockHttpServletRequest("GET", "/api/user"),
                response,
                new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
        JsonNode problem = problemOf(response);
        assertThat(problem.path("status").asInt()).isEqualTo(401);
        assertThat(problem.path("title").asString()).isEqualTo("Unauthorized");
        assertThat(problem.path("detail").asString()).isEqualTo("Sign in, then try again.");
        assertThat(problem.path("requestId").asString()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(problem.has("type")).isFalse();
    }

    @Test
    void shouldAnswerADeniedRequestWithAProblem() throws Exception {
        var response = new MockHttpServletResponse();

        refusals.handle(new MockHttpServletRequest("GET", "/api/admin"), response, new AccessDeniedException("no"));

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode problem = problemOf(response);
        assertThat(problem.path("status").asInt()).isEqualTo(403);
        assertThat(problem.path("title").asString()).isEqualTo("Forbidden");
        assertThat(problem.path("detail").asString()).isEqualTo("Your account does not have access to this.");
    }

    private JsonNode problemOf(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        return mapper.readTree(response.getContentAsString());
    }
}
