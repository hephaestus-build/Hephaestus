package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.metrics.GitLabMetrics;
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
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Classifies GitLab API exceptions into actionable categories for retry decisions.
 * <p>
 * Mirrors {@link de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier}
 * with GitLab-specific adaptations:
 * <ul>
 *   <li>GitLab uses {@code RateLimit-Remaining} / {@code RateLimit-Reset} headers</li>
 *   <li>GitLab GraphQL errors use different type strings</li>
 *   <li>GitLab returns 429 with {@code Retry-After} header</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabExceptionClassifier {

    private static final Logger log = LoggerFactory.getLogger(GitLabExceptionClassifier.class);

    /**
     * Error categories for GitLab API exceptions.
     * Matches GitHub's categories for consistent handling across providers.
     */
    public enum Category {
        RETRYABLE,
        RATE_LIMITED,
        NOT_FOUND,
        AUTH_ERROR,
        CLIENT_ERROR,
        UNKNOWN,
    }

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
                wait = Duration.ofSeconds(60);
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

    public GitLabExceptionClassifier(MeterRegistry meterRegistry) {
        this.retryableCounter = Counter.builder(GitLabMetrics.GITLAB_SYNC_ERRORS_TOTAL)
                .tag("category", "retryable")
                .register(meterRegistry);
        this.rateLimitedCounter = Counter.builder(GitLabMetrics.GITLAB_SYNC_ERRORS_TOTAL)
                .tag("category", "rate_limited")
                .register(meterRegistry);
        this.notFoundCounter = Counter.builder(GitLabMetrics.GITLAB_SYNC_ERRORS_TOTAL)
                .tag("category", "not_found")
                .register(meterRegistry);
        this.authErrorCounter = Counter.builder(GitLabMetrics.GITLAB_SYNC_ERRORS_TOTAL)
                .tag("category", "auth_error")
                .register(meterRegistry);
        this.clientErrorCounter = Counter.builder(GitLabMetrics.GITLAB_SYNC_ERRORS_TOTAL)
                .tag("category", "client_error")
                .register(meterRegistry);
        this.unknownCounter = Counter.builder(GitLabMetrics.GITLAB_SYNC_ERRORS_TOTAL)
                .tag("category", "unknown")
                .register(meterRegistry);
    }

    public Category classify(Throwable e) {
        return classifyWithDetails(e).category();
    }

    public ClassificationResult classifyWithDetails(Throwable e) {
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

    public boolean isRetryable(Throwable e) {
        Category category = classify(e);
        return category == Category.RETRYABLE || category == Category.RATE_LIMITED;
    }

    @Nullable
    public ClassificationResult classifyGraphQlResponse(@Nullable ClientGraphQlResponse response) {
        if (response == null) {
            return null;
        }

        var errors = Objects.requireNonNull(response).getErrors();
        if (errors == null || errors.isEmpty()) {
            return null;
        }

        for (ResponseError error : errors) {
            Map<String, Object> extensions = error.getExtensions();
            if (extensions == null) continue;

            Object errorType = extensions.get("type");
            if (errorType == null) {
                // GitLab may use "code" instead of "type"
                errorType = extensions.get("code");
            }
            if (errorType == null) continue;

            String type = errorType.toString().toUpperCase();
            switch (type) {
                case "NOT_FOUND" -> {
                    notFoundCounter.increment();
                    return ClassificationResult.of(Category.NOT_FOUND, "GraphQL NOT_FOUND: " + error.getMessage());
                }
                case "RATE_LIMITED", "RATE_LIMIT", "TOO_MANY_REQUESTS" -> {
                    rateLimitedCounter.increment();
                    return ClassificationResult.rateLimited(
                            Duration.ofMinutes(1), "GraphQL rate limit: " + error.getMessage());
                }
                case "FORBIDDEN" -> {
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
                case "RESOURCE_LIMITS_EXCEEDED", "MAX_COMPLEXITY_EXCEEDED" -> {
                    clientErrorCounter.increment();
                    return ClassificationResult.of(
                            Category.CLIENT_ERROR, "GraphQL resource limit: " + error.getMessage());
                }
                default -> {
                    /* continue */
                }
            }
        }

        unknownCounter.increment();
        return ClassificationResult.of(
                Category.UNKNOWN, "Unclassified GraphQL error: " + errors.get(0).getMessage());
    }

    private ClassificationResult doClassify(Throwable e) {
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

        // GitLabSyncException — invalid GraphQL response
        if (cause instanceof GitLabSyncException) {
            return ClassificationResult.of(Category.RETRYABLE, "GitLab sync error: " + cause.getMessage());
        }

        // Database deadlocks
        if (isDeadlockException(cause)) {
            return ClassificationResult.of(
                    Category.RETRYABLE, "Database deadlock detected - will retry: " + cause.getMessage());
        }

        if (cause instanceof UnexpectedRollbackException) {
            return ClassificationResult.of(
                    Category.RETRYABLE, "Transaction rollback detected - will retry: " + cause.getMessage());
        }

        // HTTP response errors
        if (cause instanceof WebClientResponseException responseException) {
            return classifyHttpStatus(responseException);
        }

        // Network errors before response
        if (cause instanceof WebClientRequestException) {
            return classifyNetworkException(cause.getCause() != null ? cause.getCause() : cause);
        }

        if (isTimeoutException(cause)) {
            return ClassificationResult.of(Category.RETRYABLE, "Timeout: " + cause.getMessage());
        }

        if (isNetworkException(cause)) {
            return classifyNetworkException(cause);
        }

        if (cause instanceof IOException) {
            return ClassificationResult.of(Category.RETRYABLE, "IO error: " + cause.getMessage());
        }

        // FieldAccessException from Spring GraphQL
        if (cause.getClass().getSimpleName().equals("FieldAccessException")) {
            String message = cause.getMessage();
            if (message != null) {
                if (message.contains("NOT_FOUND") || message.contains("Could not resolve")) {
                    return ClassificationResult.of(Category.NOT_FOUND, "GraphQL resource not found: " + message);
                }
                if (message.contains("FORBIDDEN") || message.contains("forbidden")) {
                    return ClassificationResult.of(Category.AUTH_ERROR, "GraphQL forbidden: " + message);
                }
            }
        }

        return ClassificationResult.of(
                Category.UNKNOWN, "Unclassified: " + cause.getClass().getSimpleName() + " - " + cause.getMessage());
    }

    private ClassificationResult classifyHttpStatus(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        String message = "HTTP " + status + ": " + e.getMessage();

        if (status == 400) return ClassificationResult.of(Category.CLIENT_ERROR, message);
        if (status == 401) return ClassificationResult.of(Category.AUTH_ERROR, message);

        if (status == 403) {
            if (isRateLimitResponse(e)) {
                Instant resetAt = extractRateLimitReset(e);
                if (resetAt != null) return ClassificationResult.rateLimited(resetAt, message);
                return ClassificationResult.rateLimited(Duration.ofMinutes(1), message);
            }
            return ClassificationResult.of(Category.AUTH_ERROR, message);
        }

        if (status == 404) return ClassificationResult.of(Category.NOT_FOUND, message);
        if (status == 422) return ClassificationResult.of(Category.CLIENT_ERROR, message);

        if (status == 429) {
            Instant resetAt = extractRateLimitReset(e);
            if (resetAt != null) return ClassificationResult.rateLimited(resetAt, message);
            // GitLab also uses Retry-After header
            Duration retryAfter = extractRetryAfter(e);
            if (retryAfter != null) return ClassificationResult.rateLimited(retryAfter, message);
            return ClassificationResult.rateLimited(Duration.ofMinutes(1), message);
        }

        if (status >= 500 && status < 600) {
            return ClassificationResult.of(Category.RETRYABLE, message);
        }

        if (status >= 400 && status < 500) {
            return ClassificationResult.of(Category.CLIENT_ERROR, message);
        }

        return ClassificationResult.of(Category.UNKNOWN, message);
    }

    private ClassificationResult classifyNetworkException(Throwable e) {
        return ClassificationResult.of(
                Category.RETRYABLE, "Network error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
    }

    private boolean isDeadlockException(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();

            if (className.contains("CannotAcquireLockException")
                    || className.contains("LockAcquisitionException")
                    || className.contains("DeadlockLoserDataAccessException")) {
                return true;
            }

            if ((className.contains("SQLException") || className.contains("PSQLException"))
                    && message != null
                    && (message.contains("deadlock detected") || message.contains("40P01"))) {
                return true;
            }

            if (message != null && message.toLowerCase().contains("deadlock")) return true;

            current = current.getCause();
        }
        return false;
    }

    private boolean isTimeoutException(Throwable e) {
        if (e instanceof TimeoutException || e instanceof SocketTimeoutException) return true;
        String className = e.getClass().getName();
        return (className.contains("TimeoutException")
                || className.contains("ReadTimeoutException")
                || className.contains("WriteTimeoutException"));
    }

    private boolean isNetworkException(Throwable e) {
        if (e instanceof ConnectException || e instanceof SocketException || e instanceof UnknownHostException) {
            return true;
        }
        String className = e.getClass().getName();
        return (className.contains("PrematureCloseException")
                || className.contains("AbortedException")
                || className.contains("ConnectionException")
                || className.contains("ConnectionReset")
                || className.contains("GraphQlTransportException"));
    }

    private boolean isRateLimitResponse(WebClientResponseException e) {
        // GitLab uses RateLimit-Remaining (no X- prefix)
        List<String> remaining = e.getHeaders().get("RateLimit-Remaining");
        if (remaining == null || remaining.isEmpty()) {
            // Also check X- prefixed variant (some versions)
            remaining = e.getHeaders().get("X-RateLimit-Remaining");
        }
        if (remaining != null && !remaining.isEmpty()) {
            try {
                if (Integer.parseInt(remaining.get(0)) == 0) return true;
            } catch (NumberFormatException ignored) {
                /* continue */
            }
        }

        String body = e.getResponseBodyAsString();
        if (body != null) {
            String lower = body.toLowerCase();
            return lower.contains("rate limit") || lower.contains("ratelimit") || lower.contains("throttled");
        }
        return false;
    }

    @Nullable
    private Instant extractRateLimitReset(WebClientResponseException e) {
        // GitLab uses RateLimit-Reset (Unix timestamp)
        List<String> resetHeader = e.getHeaders().get("RateLimit-Reset");
        if (resetHeader == null || resetHeader.isEmpty()) {
            resetHeader = e.getHeaders().get("X-RateLimit-Reset");
        }
        if (resetHeader != null && !resetHeader.isEmpty()) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(resetHeader.get(0)));
            } catch (NumberFormatException ignored) {
                /* fall through */
            }
        }
        return null;
    }

    @Nullable
    private Duration extractRetryAfter(WebClientResponseException e) {
        List<String> retryAfter = e.getHeaders().get("Retry-After");
        if (retryAfter != null && !retryAfter.isEmpty()) {
            try {
                return Duration.ofSeconds(Long.parseLong(retryAfter.get(0)));
            } catch (NumberFormatException ignored) {
                /* fall through */
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
