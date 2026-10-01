package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier.ClassificationResult;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * Handles GraphQL response validation, error classification, and rate-limit
 * back-off for GitLab sync services.  Drop-in replacement for the repeated
 * {@code if (response == null || !response.isValid())} blocks that previously
 * treated every failure as an opaque error.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabGraphQlResponseHandler {

    private static final long MAX_RATE_LIMIT_WAIT_MS = 120_000;

    private final GitLabExceptionClassifier exceptionClassifier;

    public GitLabGraphQlResponseHandler(GitLabExceptionClassifier exceptionClassifier) {
        this.exceptionClassifier = exceptionClassifier;
    }

    /**
     * Result of handling a GraphQL response.
     *
     * @param action what the caller should do next
     * @param classification classified error details (null when CONTINUE)
     */
    public record HandleResult(Action action, @Nullable ClassificationResult classification) {
        public enum Action {
            /** Response is valid — proceed with data processing. */
            CONTINUE,
            /** Rate-limited — back-off was performed, caller should retry the same page. */
            RETRY,
            /** Unrecoverable error — caller should abort the pagination loop. */
            ABORT,
        }
    }

    /**
     * Validates a GraphQL response and classifies errors.
     * <p>
     * If the response is rate-limited, this method sleeps for the suggested duration
     * (up to 120 s) and returns {@link HandleResult.Action#RETRY}.
     * For all other errors it returns {@link HandleResult.Action#ABORT}.
     *
     * @param response the raw GraphQL response (may be null)
     * @param context human-readable description for log messages (e.g. "issues for project X")
     * @param log the caller's logger
     * @return action to take, or {@code CONTINUE} if response is valid
     */
    public HandleResult handle(@Nullable ClientGraphQlResponse response, String context, Logger log) {
        if (response != null && Objects.requireNonNull(response).isValid()) {
            // Check for partial errors even on valid responses
            var errors = Objects.requireNonNull(response).getErrors();
            if (errors != null && !errors.isEmpty()) {
                var classification = exceptionClassifier.classifyGraphQlResponse(response);
                if (classification != null) {
                    log.warn(
                            "Partial GraphQL errors (data still valid): context={}, category={}, message={}",
                            context,
                            classification.category(),
                            classification.message());
                }
            }
            return new HandleResult(HandleResult.Action.CONTINUE, null);
        }

        // Response is null or invalid — classify the error
        var classification = response != null ? exceptionClassifier.classifyGraphQlResponse(response) : null;

        if (classification != null) {
            log.warn(
                    "GraphQL error classified: context={}, category={}, message={}",
                    context,
                    classification.category(),
                    classification.message());

            if (classification.category() == Category.RATE_LIMITED && classification.suggestedWait() != null) {
                long waitMs = Math.min(classification.suggestedWait().toMillis(), MAX_RATE_LIMIT_WAIT_MS);
                log.info("Rate-limited, waiting {}ms before retry: context={}", waitMs, context);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new HandleResult(HandleResult.Action.ABORT, classification);
                }
                return new HandleResult(HandleResult.Action.RETRY, classification);
            }

            // CLIENT_ERROR (complexity), AUTH_ERROR, NOT_FOUND, UNKNOWN -> abort
            return new HandleResult(HandleResult.Action.ABORT, classification);
        }

        // No classification possible — generic failure
        var errors = response == null
                ? "null response"
                : Objects.requireNonNull(response).getErrors();
        log.warn("GraphQL request failed (unclassified): context={}, errors={}", context, errors);
        return new HandleResult(HandleResult.Action.ABORT, null);
    }

    /**
     * Whether one page of the connection at {@code connectionPath} is whole: its {@code nodes} is a list and its
     * {@code pageInfo.hasNextPage} a boolean. A group the token cannot read comes back as {@code group: null} with no
     * error, and a null or omitted field decodes as an empty list or {@code false}, so without this check such a page
     * reads as an empty or last page. A real {@code nodes: []} is whole.
     *
     * @param response       a response {@link #handle} let through
     * @param connectionPath the connection's path in the response, such as {@code group.groupMembers}
     * @return true if the page says both what it lists and whether more follows
     */
    public boolean isWholePage(ClientGraphQlResponse response, String connectionPath) {
        Object nodes = response.field(connectionPath + ".nodes").getValue();
        Object hasNextPage =
                response.field(connectionPath + ".pageInfo.hasNextPage").getValue();
        return nodes instanceof List<?> && hasNextPage instanceof Boolean;
    }

    /**
     * Checks whether a pagination cursor is stuck in a loop (same value as previous page).
     *
     * @param currentCursor  the cursor returned by the current page
     * @param previousCursor the cursor from the previous page
     * @param context        human-readable description for log messages
     * @param log            the caller's logger
     * @return true if a loop is detected (caller should break)
     */
    public boolean isPaginationLoop(
            @Nullable String currentCursor, @Nullable String previousCursor, String context, Logger log) {
        if (currentCursor != null && currentCursor.equals(previousCursor)) {
            log.error(
                    "Pagination loop detected (same cursor returned twice): context={}, cursor={}",
                    context,
                    currentCursor);
            return true;
        }
        return false;
    }
}
