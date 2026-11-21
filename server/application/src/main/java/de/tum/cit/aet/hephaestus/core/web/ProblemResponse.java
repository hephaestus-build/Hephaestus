package de.tum.cit.aet.hephaestus.core.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a problem from a filter, where no message converter runs, in the shape a controller's problem has: extension
 * members at the top level and the request named ({@link RequestReference}).
 */
public final class ProblemResponse {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private ProblemResponse() {}

    /** Answers with the problem and its status. A header that the caller set first, such as a challenge, stays. */
    public static void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        MAPPER.writeValue(response.getOutputStream(), RequestReference.name(problem, response));
    }
}
