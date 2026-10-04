package de.tum.cit.aet.hephaestus.core.auth.ratelimit;

import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

/**
 * The one 429 every token bucket in the instance answers with: an RFC 9457
 * {@code application/problem+json} body and the {@code Retry-After} a caller needs to back off.
 * Shared so a browser and a sandbox implement the same retry, and so a new limiter cannot ship
 * without the header. The blocked-request metric stays with each limiter, because a meter name
 * belongs to the module that emits it.
 */
public final class RateLimitResponse {

    private RateLimitResponse() {}

    /**
     * Seconds until the bucket next holds a token, floored at one: a sub-second wait truncates to
     * {@code 0}, which a client reads as "retry now" and turns into a hot loop.
     */
    public static long retryAfterSeconds(ConsumptionProbe probe) {
        return Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
    }

    public static void writeTooManyRequests(
            HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds, ObjectMapper objectMapper)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "You sent too many requests. Try again after " + retryAfterSeconds + " seconds.");
        problem.setTitle("Too Many Requests");
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
