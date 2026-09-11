package de.tum.cit.aet.hephaestus.integration.outline.collection;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiException;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineRateLimitedException;
import java.net.URI;
import java.time.Duration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error mapper for the Outline admin controllers. Scoped to the {@code integration.outline} package so
 * every Outline REST route returns RFC-7807 {@code ProblemDetail} for its domain violations, following
 * the {@code docs/contributor/api-error-handling.md} convention (mirrors
 * {@code SlackChannelControllerAdvice}). Not-found / validation / auth flow through the global advice.
 *
 * <p>The live-proxy endpoints double as the connectivity probe, so the wire failures carry precise
 * semantics: Outline throttling → {@code 503} with a {@code Retry-After} hint; any other Outline API
 * failure → {@code 502} (the upstream, not this server, failed); a registration naming a collection
 * Outline does not know → {@code 422}. Each problem carries a stable machine-readable {@code type}
 * URI so clients can branch on the failure class instead of parsing titles.
 */
@RestControllerAdvice(basePackages = "de.tum.cit.aet.hephaestus.integration.outline")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OutlineCollectionControllerAdvice {

    @ExceptionHandler(UnknownOutlineCollectionException.class)
    ProblemDetail handleUnknownCollection(UnknownOutlineCollectionException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setType(URI.create("/problems/unknown-outline-collection"));
        problem.setTitle("Collection not found in Outline");
        problem.setDetail(LoggingUtils.sanitizeForLog(exception.getMessage()));
        return problem;
    }

    @ExceptionHandler(OutlineRateLimitedException.class)
    ResponseEntity<ProblemDetail> handleRateLimited(OutlineRateLimitedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(URI.create("/problems/outline-rate-limited"));
        problem.setTitle("Outline is rate-limiting requests");
        problem.setDetail("The Outline server is throttling requests. Try again shortly.");
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        Duration retryAfter = exception.getRetryAfter();
        if (retryAfter != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfter.toSeconds())));
        }
        return response.body(problem);
    }

    @ExceptionHandler(OutlineApiException.class)
    ProblemDetail handleApiFailure(OutlineApiException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setType(URI.create("/problems/outline-unreachable"));
        problem.setTitle("The Outline server could not be reached");
        problem.setDetail(LoggingUtils.sanitizeForLog(exception.getMessage()));
        return problem;
    }
}
