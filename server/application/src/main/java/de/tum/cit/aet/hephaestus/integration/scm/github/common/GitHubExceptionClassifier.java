package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationSuspendedException;
import de.tum.cit.aet.hephaestus.integration.scm.github.metrics.GitHubMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Classifies GitHub API failures for retry decisions. A not-found response does not prove deletion. */
@Component
@Slf4j
public class GitHubExceptionClassifier {

    public enum Category {
        /**
         * Transient failures that should be retried with exponential backoff.
         * Includes: timeouts, network errors, 500, 502, 503, 504.
         */
        RETRYABLE,

        /**
         * Rate limit exceeded - wait for reset time, then retry.
         * Includes: 429 Too Many Requests, 403 with rate limit headers.
         */
        RATE_LIMITED,

        /**
         * Resource not found or not accessible.
         * Includes: 404 Not Found, GraphQL NOT_FOUND errors.
         */
        NOT_FOUND,

        /**
         * Authentication or authorization error - sync should be aborted.
         * Includes: 401 Unauthorized, 403 Forbidden (non-rate-limit).
         */
        AUTH_ERROR,

        /**
         * Client error - bad request that won't succeed on retry.
         * Includes: 400 Bad Request, 422 Unprocessable Entity.
         */
        CLIENT_ERROR,

        /**
         * Unclassified exception - requires investigation.
         */
        UNKNOWN,
    }

    /**
     * Result of exception classification including optional rate limit reset time.
     */
    public record ClassificationResult(
            Category category,
            @Nullable Instant rateLimitResetAt,
            @Nullable Duration suggestedWait,
            String message) {
        public static ClassificationResult of(Category category, String message) {
            return new ClassificationResult(category, null, null, message);
        }

        public static ClassificationResult rateLimited(Instant resetAt, String message) {
            Duration wait = Duration.between(Instant.now(), resetAt);
            if (wait.isNegative()) {
                wait = Duration.ofSeconds(60); // Default wait if reset time is in the past
            }
            return new ClassificationResult(Category.RATE_LIMITED, resetAt, wait, message);
        }

        public static ClassificationResult rateLimited(Duration suggestedWait, String message) {
            return new ClassificationResult(
                    Category.RATE_LIMITED, Instant.now().plus(suggestedWait), suggestedWait, message);
        }
    }

    private final Counter retryableCounter;
    private final Counter rateLimitedCounter;
    private final Counter notFoundCounter;
    private final Counter authErrorCounter;
    private final Counter clientErrorCounter;
    private final Counter unknownCounter;

    public GitHubExceptionClassifier(MeterRegistry meterRegistry) {
        this.retryableCounter = Counter.builder(GitHubMetrics.GITHUB_SYNC_ERRORS_TOTAL)
                .description("Total GitHub sync errors by category")
                .tag("category", "retryable")
                .register(meterRegistry);

        this.rateLimitedCounter = Counter.builder(GitHubMetrics.GITHUB_SYNC_ERRORS_TOTAL)
                .description("Total GitHub sync errors by category")
                .tag("category", "rate_limited")
                .register(meterRegistry);

        this.notFoundCounter = Counter.builder(GitHubMetrics.GITHUB_SYNC_ERRORS_TOTAL)
                .description("Total GitHub sync errors by category")
                .tag("category", "not_found")
                .register(meterRegistry);

        this.authErrorCounter = Counter.builder(GitHubMetrics.GITHUB_SYNC_ERRORS_TOTAL)
                .description("Total GitHub sync errors by category")
                .tag("category", "auth_error")
                .register(meterRegistry);

        this.clientErrorCounter = Counter.builder(GitHubMetrics.GITHUB_SYNC_ERRORS_TOTAL)
                .description("Total GitHub sync errors by category")
                .tag("category", "client_error")
                .register(meterRegistry);

        this.unknownCounter = Counter.builder(GitHubMetrics.GITHUB_SYNC_ERRORS_TOTAL)
                .description("Total GitHub sync errors by category")
                .tag("category", "unknown")
                .register(meterRegistry);
    }

    /**
     * Classifies an exception and returns the category.
     * Also increments the appropriate metrics counter.
     *
     * @param e the exception to classify
     * @return the error category
     */
    public Category classify(@Nullable Throwable e) {
        return classifyWithDetails(e).category();
    }

    /**
     * Classifies an exception with full details including rate limit reset time.
     * Also increments the appropriate metrics counter.
     *
     * @param e the exception to classify
     * @return classification result with category and additional details
     */
    public ClassificationResult classifyWithDetails(@Nullable Throwable e) {
        ClassificationResult result = doClassify(e);
        incrementCounter(result.category());

        if (log.isDebugEnabled()) {
            log.debug(
                    "Classified exception: category={}, type={}, message={}",
                    result.category(),
                    e != null ? e.getClass().getSimpleName() : "null",
                    result.message());
        }

        return result;
    }

    /**
     * Checks if an exception is retryable (either RETRYABLE or RATE_LIMITED).
     *
     * @param e the exception to check
     * @return true if the operation should be retried
     */
    public boolean isRetryable(Throwable e) {
        Category category = classify(e);
        return category == Category.RETRYABLE || category == Category.RATE_LIMITED;
    }

    /**
     * Classifies a GraphQL response for error handling.
     * Checks for NOT_FOUND errors in the response errors.
     *
     * @param response the GraphQL response to check
     * @return classification result, or null if no errors
     */
    @Nullable
    public ClassificationResult classifyGraphQlResponse(@Nullable ClientGraphQlResponse response) {
        if (response == null
                || response.getErrors() == null
                || response.getErrors().isEmpty()) {
            return null;
        }

        List<ResponseError> errors = response.getErrors();

        // Check for specific error types
        for (ResponseError error : errors) {
            Map<String, Object> extensions = error.getExtensions();
            if (extensions == null) {
                continue;
            }

            Object errorType = extensions.get("type");
            if (errorType == null) {
                continue;
            }

            String type = errorType.toString();
            switch (type) {
                case "NOT_FOUND" -> {
                    notFoundCounter.increment();
                    return ClassificationResult.of(Category.NOT_FOUND, "GraphQL NOT_FOUND: " + error.getMessage());
                }
                case "RATE_LIMIT", "RATE_LIMITED" -> {
                    rateLimitedCounter.increment();
                    return ClassificationResult.rateLimited(
                            Duration.ofMinutes(1), "GraphQL rate limit: " + error.getMessage());
                }
                case "FORBIDDEN" -> {
                    // Check if it's a rate limit error
                    String message = error.getMessage();
                    if (message != null && message.toLowerCase().contains("rate limit")) {
                        rateLimitedCounter.increment();
                        return ClassificationResult.rateLimited(
                                Duration.ofMinutes(1), "GraphQL rate limit: " + message);
                    }
                    authErrorCounter.increment();
                    return ClassificationResult.of(Category.AUTH_ERROR, "GraphQL FORBIDDEN: " + message);
                }
                case "UNAUTHORIZED" -> {
                    authErrorCounter.increment();
                    return ClassificationResult.of(Category.AUTH_ERROR, "GraphQL UNAUTHORIZED: " + error.getMessage());
                }
                case "MAX_NODE_LIMIT_EXCEEDED", "RESOURCE_LIMITS_EXCEEDED" -> {
                    clientErrorCounter.increment();
                    return ClassificationResult.of(
                            Category.CLIENT_ERROR, "GraphQL resource limit: " + error.getMessage());
                }
                default -> {
                    // Continue checking other errors
                }
            }
        }

        // Fallback: check all error messages for rate limit text (covers null extensions/type)
        for (ResponseError error : errors) {
            String message = error.getMessage();
            if (message != null && message.toLowerCase().contains("rate limit")) {
                rateLimitedCounter.increment();
                return ClassificationResult.rateLimited(Duration.ofMinutes(1), "GraphQL rate limit: " + message);
            }
        }

        // Default for unrecognized errors
        unknownCounter.increment();
        return ClassificationResult.of(
                Category.UNKNOWN, "Unclassified GraphQL error: " + errors.get(0).getMessage());
    }

    private ClassificationResult doClassify(@Nullable Throwable e) {
        if (e == null) {
            return ClassificationResult.of(Category.UNKNOWN, "Null exception");
        }

        // Unwrap common wrapper exceptions
        Throwable cause = e;
        if (e.getCause() != null
                && (e instanceof RuntimeException
                        || e.getClass().getName().contains("CompletionException")
                        || e.getClass().getName().contains("ExecutionException"))) {
            cause = e.getCause();
        }

        // Suspended installation - this is an auth error, not retryable
        if (cause instanceof InstallationSuspendedException suspended) {
            return ClassificationResult.of(
                    Category.AUTH_ERROR, "Installation " + suspended.getInstallationId() + " is suspended");
        }

        // Database deadlocks are transient and should be retried with backoff
        // These occur when multiple threads try to upsert the same user concurrently
        if (isDeadlockException(cause)) {
            return ClassificationResult.of(
                    Category.RETRYABLE, "Database deadlock detected - will retry: " + cause.getMessage());
        }

        // UnexpectedRollbackException is transient — typically caused by a nested
        // transaction that was rolled back (e.g., deadlock in a sub-transaction)
        if (cause instanceof UnexpectedRollbackException) {
            return ClassificationResult.of(
                    Category.RETRYABLE, "Transaction rollback detected - will retry: " + cause.getMessage());
        }

        // Check for WebClient response exceptions (HTTP errors)
        if (cause instanceof WebClientResponseException responseException) {
            return classifyHttpStatus(responseException);
        }

        // Check for WebClient request exceptions (network errors before response)
        if (cause instanceof WebClientRequestException) {
            return classifyNetworkException(cause.getCause() != null ? cause.getCause() : cause);
        }

        // Check for timeout exceptions
        if (isTimeoutException(cause)) {
            return ClassificationResult.of(Category.RETRYABLE, "Timeout: " + cause.getMessage());
        }

        // Check for network/IO exceptions
        if (isNetworkException(cause)) {
            return classifyNetworkException(cause);
        }

        // Check for IOException (may indicate network issues)
        if (cause instanceof IOException) {
            return ClassificationResult.of(Category.RETRYABLE, "IO error: " + cause.getMessage());
        }

        String message = cause.getMessage();

        // Check for FieldAccessException from Spring GraphQL - extract error type from message
        if (cause.getClass().getSimpleName().equals("FieldAccessException") && message != null) {
            // GraphQL NOT_FOUND errors (e.g., "Could not resolve to a Repository")
            if (message.contains("NOT_FOUND") || message.contains("Could not resolve")) {
                return ClassificationResult.of(Category.NOT_FOUND, "GraphQL resource not found: " + message);
            }
            // GraphQL FORBIDDEN errors
            if (message.contains("FORBIDDEN") || message.contains("forbidden")) {
                return ClassificationResult.of(Category.AUTH_ERROR, "GraphQL forbidden: " + message);
            }
        }

        // Default: unknown
        return ClassificationResult.of(
                Category.UNKNOWN, "Unclassified: " + cause.getClass().getSimpleName() + " - " + cause.getMessage());
    }

    /**
     * Classifies based on HTTP status code from WebClientResponseException.
     */
    private ClassificationResult classifyHttpStatus(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        String message = "HTTP " + status + ": " + e.getMessage();

        // 4xx Client Errors
        if (status == 400) {
            return ClassificationResult.of(Category.CLIENT_ERROR, message);
        }
        if (status == 401) {
            return ClassificationResult.of(Category.AUTH_ERROR, message);
        }
        if (status == 403) {
            // Check for rate limit
            if (isRateLimitResponse(e)) {
                Instant resetAt = extractRateLimitReset(e);
                if (resetAt != null) {
                    return ClassificationResult.rateLimited(resetAt, message);
                }
                return ClassificationResult.rateLimited(Duration.ofMinutes(1), message);
            }
            return ClassificationResult.of(Category.AUTH_ERROR, message);
        }
        if (status == 404) {
            return ClassificationResult.of(Category.NOT_FOUND, message);
        }
        if (status == 422) {
            return ClassificationResult.of(Category.CLIENT_ERROR, message);
        }
        if (status == 429) {
            Instant resetAt = extractRateLimitReset(e);
            if (resetAt != null) {
                return ClassificationResult.rateLimited(resetAt, message);
            }
            // Default wait for 429 without reset header
            return ClassificationResult.rateLimited(Duration.ofMinutes(1), message);
        }

        // 5xx Server Errors - all retryable
        if (status >= 500 && status < 600) {
            return ClassificationResult.of(Category.RETRYABLE, message);
        }

        // Other 4xx errors
        if (status >= 400 && status < 500) {
            return ClassificationResult.of(Category.CLIENT_ERROR, message);
        }

        return ClassificationResult.of(Category.UNKNOWN, message);
    }

    private ClassificationResult classifyNetworkException(Throwable e) {
        String message = "Network error: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        return ClassificationResult.of(Category.RETRYABLE, message);
    }

    /**
     * Checks if the exception indicates a database deadlock.
     * <p>
     * Database deadlocks are transient failures that occur when:
     * <ul>
     *   <li>Multiple threads try to INSERT/UPDATE the same rows in different order</li>
     *   <li>PostgreSQL detects a lock cycle and aborts one transaction</li>
     * </ul>
     * <p>
     * Common in sync operations when parallel threads process shared entities (e.g., users).
     * These should always be retried since the deadlock is resolved by PostgreSQL.
     */
    private boolean isDeadlockException(Throwable e) {
        // Walk the cause chain to find deadlock indicators
        Throwable current = e;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();

            // Spring/Hibernate deadlock exceptions
            if (className.contains("CannotAcquireLockException")
                    || className.contains("LockAcquisitionException")
                    || className.contains("DeadlockLoserDataAccessException")) {
                return true;
            }

            // PostgreSQL deadlock SQLState 40P01
            if (className.contains("SQLException") || className.contains("PSQLException")) {
                if (message != null && (message.contains("deadlock detected") || message.contains("40P01"))) {
                    return true;
                }
            }

            // Check message for deadlock indicators
            if (message != null && message.toLowerCase().contains("deadlock")) {
                return true;
            }

            current = current.getCause();
        }
        return false;
    }

    private boolean isTimeoutException(Throwable e) {
        if (e instanceof TimeoutException || e instanceof SocketTimeoutException) {
            return true;
        }
        // Check for reactor/netty timeout exceptions
        String className = e.getClass().getName();
        return (className.contains("TimeoutException")
                || className.contains("ReadTimeoutException")
                || className.contains("WriteTimeoutException"));
    }

    /**
     * Checks if the exception indicates a network error.
     * <p>
     * Includes transport-level errors that occur during response streaming:
     * <ul>
     *   <li>PrematureCloseException: Connection closed mid-response (GitHub infra issues)</li>
     *   <li>Connection reset/abort errors</li>
     *   <li>Standard socket exceptions</li>
     * </ul>
     */
    private boolean isNetworkException(Throwable e) {
        if (e instanceof ConnectException || e instanceof SocketException || e instanceof UnknownHostException) {
            return true;
        }

        String className = e.getClass().getName();

        // Check for reactor-netty transport errors (PrematureCloseException, etc.)
        if (className.contains("PrematureCloseException")
                || className.contains("AbortedException")
                || className.contains("ConnectionException")
                || className.contains("ConnectionReset")) {
            return true;
        }

        // Check for Spring GraphQL transport exceptions
        return className.contains("GraphQlTransportException");
    }

    /**
     * Checks if a 403 response is due to rate limiting.
     */
    private boolean isRateLimitResponse(WebClientResponseException e) {
        // Check for rate limit headers
        List<String> rateLimitRemaining = e.getHeaders().get("X-RateLimit-Remaining");
        if (rateLimitRemaining != null && !rateLimitRemaining.isEmpty()) {
            try {
                int remaining = Integer.parseInt(rateLimitRemaining.get(0));
                if (remaining == 0) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Continue with other checks
            }
        }

        // Check response body for rate limit message
        String body = e.getResponseBodyAsString();
        if (body != null) {
            String lowerBody = body.toLowerCase();
            return (lowerBody.contains("rate limit")
                    || lowerBody.contains("ratelimit")
                    || lowerBody.contains("abuse")
                    || lowerBody.contains("secondary rate"));
        }

        return false;
    }

    /**
     * Extracts rate limit reset time from response headers.
     */
    @Nullable
    private Instant extractRateLimitReset(WebClientResponseException e) {
        // Try X-RateLimit-Reset header (Unix timestamp)
        List<String> resetHeader = e.getHeaders().get("X-RateLimit-Reset");
        if (resetHeader != null && !resetHeader.isEmpty()) {
            try {
                long epochSeconds = Long.parseLong(resetHeader.get(0));
                return Instant.ofEpochSecond(epochSeconds);
            } catch (NumberFormatException ignored) {
                // Fall through to other methods
            }
        }

        // Try Retry-After header (seconds to wait)
        List<String> retryAfter = e.getHeaders().get("Retry-After");
        if (retryAfter != null && !retryAfter.isEmpty()) {
            try {
                long seconds = Long.parseLong(retryAfter.get(0));
                return Instant.now().plusSeconds(seconds);
            } catch (NumberFormatException ignored) {
                // Retry-After might be a date, but we'll ignore that for simplicity
            }
        }

        return null;
    }

    private void incrementCounter(Category category) {
        switch (category) {
            case RETRYABLE -> retryableCounter.increment();
            case RATE_LIMITED -> rateLimitedCounter.increment();
            case NOT_FOUND -> notFoundCounter.increment();
            case AUTH_ERROR -> authErrorCounter.increment();
            case CLIENT_ERROR -> clientErrorCounter.increment();
            case UNKNOWN -> unknownCounter.increment();
        }
    }
}
