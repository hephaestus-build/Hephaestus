package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GitHubGraphQlSyncCoordinator {

    private static final long MAX_RATE_LIMIT_WAIT_MS = 300_000;

    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubExceptionClassifier exceptionClassifier;

    /**
     * Context for handling a GraphQL classification result.
     *
     * @param classification   the classification result to handle
     * @param retryAttempt     current retry attempt number
     * @param maxRetryAttempts maximum number of retry attempts
     * @param phase            description of the sync phase (e.g. "pull request sync")
     * @param scopeLabel       label for the scope identifier (e.g. "repoName")
     * @param scopeValue       value of the scope identifier
     * @param log              logger to use for messages
     */
    public record GraphQlClassificationContext(
            ClassificationResult classification,
            int retryAttempt,
            int maxRetryAttempts,
            String phase,
            String scopeLabel,
            Object scopeValue,
            Logger log) {}

    @Nullable
    public ClassificationResult classifyGraphQlErrors(@Nullable ClientGraphQlResponse response) {
        ClassificationResult classification = exceptionClassifier.classifyGraphQlResponse(response);
        if (classification != null) {
            return classification;
        }

        GitHubGraphQlErrorUtils.TransientError transientError = GitHubGraphQlErrorUtils.detectTransientError(response);
        if (transientError == null) {
            return null;
        }

        return switch (transientError.type()) {
            case RATE_LIMIT ->
                ClassificationResult.rateLimited(
                        transientError.getRecommendedWait(), "GraphQL rate limit: " + transientError.message());
            case TIMEOUT, SERVER_ERROR ->
                ClassificationResult.of(Category.RETRYABLE, "GraphQL transient error: " + transientError.message());
            case RESOURCE_LIMIT ->
                ClassificationResult.of(Category.CLIENT_ERROR, "GraphQL resource limit: " + transientError.message());
        };
    }

    /**
     * Handles a GraphQL classification result, performing retries or logging errors as appropriate.
     *
     * @param ctx the classification context containing all parameters
     * @return {@code true} if the caller should retry the operation, {@code false} to abort
     */
    public boolean handleGraphQlClassification(GraphQlClassificationContext ctx) {
        ClassificationResult classification = ctx.classification();
        int retryAttempt = ctx.retryAttempt();
        int maxRetryAttempts = ctx.maxRetryAttempts();
        String phase = ctx.phase();
        String scopeLabel = ctx.scopeLabel();
        Object scopeValue = ctx.scopeValue();
        Logger log = ctx.log();

        Category category = classification.category();

        return switch (category) {
            case RETRYABLE -> {
                if (retryAttempt < maxRetryAttempts) {
                    log.warn(
                            "Retrying {} after transient GraphQL error: {}={}, attempt={}, error={}",
                            phase,
                            scopeLabel,
                            scopeValue,
                            retryAttempt + 1,
                            classification.message());
                    try {
                        ExponentialBackoff.sleep(retryAttempt + 1);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        yield false;
                    }
                    yield true;
                }
                log.error(
                        "Failed {} after {} retries due to GraphQL error: {}={}, error={}",
                        phase,
                        maxRetryAttempts,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case RATE_LIMITED -> {
                if (retryAttempt < maxRetryAttempts && classification.suggestedWait() != null) {
                    long waitMs = Math.min(classification.suggestedWait().toMillis(), MAX_RATE_LIMIT_WAIT_MS);
                    log.warn(
                            "Rate limited during {}, waiting: {}={}, waitMs={}", phase, scopeLabel, scopeValue, waitMs);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        yield false;
                    }
                    yield true;
                }
                log.error(
                        "Aborting {} due to GraphQL rate limit: {}={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case NOT_FOUND -> {
                log.warn(
                        "Resource not found during {} GraphQL response: {}={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case AUTH_ERROR -> {
                log.error(
                        "Authentication error during {} GraphQL response: {}={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case CLIENT_ERROR -> {
                boolean isResourceLimit = classification.message() != null
                        && classification.message().contains("resource limit");
                if (isResourceLimit) {
                    log.warn(
                            "Resource limit exceeded during {} GraphQL response (reduce page size): {}={}, error={}",
                            phase,
                            scopeLabel,
                            scopeValue,
                            classification.message());
                } else {
                    log.error(
                            "Client error during {} GraphQL response: {}={}, error={}",
                            phase,
                            scopeLabel,
                            scopeValue,
                            classification.message());
                }
                yield false;
            }
            default -> {
                log.error(
                        "Aborting {} due to GraphQL error: {}={}, category={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        category,
                        classification.message());
                yield false;
            }
        };
    }

    public boolean waitForRateLimitIfNeeded(
            Long scopeId, String phase, String scopeLabel, Object scopeValue, Logger log) {
        try {
            boolean waited = graphQlClientProvider.waitIfRateLimitLow(scopeId);
            if (waited) {
                log.info("Paused due to critical rate limit: phase={}, {}={}", phase, scopeLabel, scopeValue);
            }
            // Both waited=true ("waited, now continue") and waited=false
            // ("not critical / reset passed, continue") mean the sync should proceed.
            // The tracker optimistically resets remaining when the reset time has
            // passed, so isRateLimitCritical() will return false on the next check.
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for rate limit reset: phase={}, {}={}", phase, scopeLabel, scopeValue);
            return false;
        }
    }
}
