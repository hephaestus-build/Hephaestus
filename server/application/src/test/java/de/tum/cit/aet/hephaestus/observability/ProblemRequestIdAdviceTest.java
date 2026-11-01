package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.web.RequestReference;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;

class ProblemRequestIdAdviceTest extends BaseUnitTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static @Nullable Object write(Object body, MockHttpServletResponse response) {
        return new ProblemRequestIdAdvice()
                .beforeBodyWrite(
                        body,
                        mock(MethodParameter.class),
                        MediaType.APPLICATION_PROBLEM_JSON,
                        StringHttpMessageConverter.class,
                        mock(ServerHttpRequest.class),
                        new ServletServerHttpResponse(response));
    }

    private static MockHttpServletResponse respondingAs(String requestId) {
        var response = new MockHttpServletResponse();
        response.setHeader(RequestReference.HEADER, requestId);
        return response;
    }

    @Test
    void shouldNameTheRequestInAProblemWithTheIdOfItsResponse() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);

        write(problem, respondingAs(TRACE));

        assertThat(problem.getProperties()).containsEntry("requestId", TRACE);
    }

    @Test
    void shouldLeaveOtherBodiesAndAResponseWithoutAValidIdUnchanged() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        Map<String, String> body = Map.of("name", "acme");

        assertThat(write(body, respondingAs(TRACE))).isSameAs(body);
        write(problem, new MockHttpServletResponse());
        write(problem, respondingAs("not-a-trace-id"));

        assertThat(problem.getProperties()).isNull();
    }
}
