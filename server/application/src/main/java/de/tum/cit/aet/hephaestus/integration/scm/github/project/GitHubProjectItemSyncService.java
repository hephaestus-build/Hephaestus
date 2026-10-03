package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.DEFAULT_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ExponentialBackoff;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlErrorUtils;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Item;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedProjectItemsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedProjectItemsDTO.EmbeddedProjectItem;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedProjectItemsDTO.EmbeddedProjectReference;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service for synchronizing GitHub project items from the issue/PR side.
 * <p>
 * This service processes embedded project items that are fetched inline with issues/PRs
 * and handles follow-up pagination for issues/PRs in many projects.
 * <p>
 * <h2>Architecture</h2>
 * Project items are synced FROM the issue/PR side rather than from the project side.
 * This design enables:
 * <ul>
 *   <li>Historical backfill: Items naturally backfilled with their parent issues/PRs</li>
 *   <li>Efficient sync: `filterBy.since` on issues propagates to item updates</li>
 *   <li>Proper pagination: Uses issue's `projectItems(first:, after:)` connection</li>
 * </ul>
 * <p>
 * <b>Note:</b> Draft Issues have no parent Issue and must still be synced from the project side.
 *
 * @see GitHubProjectSyncService#syncProjectItems(Long, Project)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubProjectItemSyncService {

    private static final String ISSUE_PROJECT_ITEMS_QUERY = "GetIssueProjectItems";
    private static final String PR_PROJECT_ITEMS_QUERY = "GetPullRequestProjectItems";
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final ProjectRepository projectRepository;
    private final GitHubProjectItemProcessor projectItemProcessor;
    private final GitHubProjectItemFieldValueSyncService fieldValueSyncService;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubSyncProperties syncProperties;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final TransactionTemplate transactionTemplate;

    /**
     * Processes embedded project items from an issue/PR sync.
     * <p>
     * For each embedded item, looks up the project by node ID and creates the item link.
     * Items referencing projects not yet synced are skipped with a debug log.
     * <p>
     * The {@code parentIssueId} is critical: embedded project items are fetched inline with
     * their parent issue/PR, so the GraphQL query does NOT include the {@code content { ... }} selection
     * (which would redundantly return the parent's own ID). Instead, we propagate the parent's
     * database ID so the processor can set both {@code issue_id} and {@code content_database_id}.
     *
     * @param embeddedItems the embedded project items from the issue query
     * @param context the processing context
     * @param parentIssueId the database ID of the parent issue/PR that owns these project items
     * @return number of items successfully processed
     */
    @Transactional
    public int processEmbeddedItems(
            @Nullable EmbeddedProjectItemsDTO embeddedItems, ProcessingContext context, Long parentIssueId) {
        if (!syncSchedulerProperties.projects().enabled()) {
            return 0;
        }
        if (embeddedItems == null || embeddedItems.items().isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (EmbeddedProjectItem embeddedItem : embeddedItems.items()) {
            if (processEmbeddedItem(embeddedItem, context, parentIssueId)) {
                processed++;
            }
        }

        return processed;
    }

    /**
     * Synchronizes remaining project items for an issue/PR, starting from the given cursor.
     * <p>
     * Called when an issue/PR has more than 5 project items (the embedded limit).
     * Continues pagination from where the embedded items left off.
     *
     * @param scopeId the scope ID for authentication
     * @param issueNodeId the GitHub GraphQL node ID of the issue/PR
     * @param isPullRequest true if this is a pull request, false for a regular issue
     * @param repository the repository containing the issue/PR
     * @param startCursor the pagination cursor to start from
     * @param parentIssueId the database ID of the parent issue/PR that owns these project items
     * @return number of additional items synced
     */
    public int syncRemainingProjectItems(
            Long scopeId,
            @Nullable String issueNodeId,
            boolean isPullRequest,
            Repository repository,
            String startCursor,
            Long parentIssueId) {
        if (!syncSchedulerProperties.projects().enabled()) {
            return 0;
        }
        if (issueNodeId == null || issueNodeId.isBlank()) {
            log.warn("Skipped project item pagination: reason=missingNodeId");
            return 0;
        }

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

        int totalSynced = 0;
        int itemsReceived = 0;
        int reportedTotalCount = -1;
        String cursor = startCursor;
        boolean hasMore = true;
        int pageCount = 0;
        int retryAttempt = 0;

        // Determine query based on issue type (PR vs Issue)
        String queryDocument = isPullRequest ? PR_PROJECT_ITEMS_QUERY : ISSUE_PROJECT_ITEMS_QUERY;
        String variableName = isPullRequest ? "pullRequestId" : "issueId";

        log.debug(
                "Starting project item pagination: nodeId={}, isPR={}, startCursor={}...",
                issueNodeId,
                isPullRequest,
                startCursor != null ? startCursor.substring(0, Math.min(20, startCursor.length())) : "null");

        while (hasMore) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for project items: nodeId={}, limit={}",
                        issueNodeId,
                        MAX_PAGINATION_PAGES);
                break;
            }

            try {
                final String currentCursor = cursor;
                final int currentPage = pageCount;

                ClientGraphQlResponse response = Mono.defer(() -> client.documentName(queryDocument)
                                .variable(variableName, issueNodeId)
                                .variable(
                                        "first",
                                        adaptPageSize(
                                                DEFAULT_PAGE_SIZE,
                                                graphQlClientProvider.getRateLimitRemaining(scopeId)))
                                .variable("after", currentCursor)
                                .execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying project item pagination after transport error: nodeId={}, page={}, attempt={}, error={}",
                                        issueNodeId,
                                        currentPage,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(syncProperties.graphqlTimeout());

                if (response == null || !response.isValid()) {
                    ClassificationResult classification = classifyGraphQlErrors(response);
                    if (classification != null) {
                        if (handleClassification(
                                classification, "project item pagination", "nodeId", issueNodeId, retryAttempt)) {
                            retryAttempt++;
                            continue;
                        }
                        break;
                    }
                    log.warn("Invalid GraphQL response for project item pagination: nodeId={}", issueNodeId);
                    break;
                }

                graphQlClientProvider.trackRateLimit(scopeId, response);

                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!waitForRateLimitIfNeeded(scopeId, "project item pagination", "nodeId", issueNodeId)) {
                        break;
                    }
                }

                GHProjectV2ItemConnection connection =
                        response.field("node.projectItems").toEntity(GHProjectV2ItemConnection.class);

                if (connection == null
                        || connection.getNodes() == null
                        || connection.getNodes().isEmpty()) {
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = connection.getTotalCount();
                }
                itemsReceived += connection.getNodes().size();

                // Process this page of items in its own transaction
                Integer pageSynced = transactionTemplate.execute(status -> {
                    ProcessingContext context = ProcessingContext.forSync(scopeId, repository);
                    int synced = 0;
                    for (GHProjectV2Item graphQlItem : connection.getNodes()) {
                        EmbeddedProjectItem embeddedItem = EmbeddedProjectItem.fromProjectV2Item(graphQlItem);
                        if (embeddedItem != null && processEmbeddedItem(embeddedItem, context, parentIssueId)) {
                            synced++;
                        }
                    }
                    return synced;
                });

                if (pageSynced != null) {
                    totalSynced += pageSynced;
                }

                var pageInfo = connection.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
                retryAttempt = 0;
            } catch (InstallationNotFoundException e) {
                throw e;
            } catch (Exception e) {
                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                if (!handleClassification(
                        classification, "project item pagination", "nodeId", issueNodeId, retryAttempt)) {
                    break;
                }
                retryAttempt++;
            }
        }

        // Raw nodes received vs items.totalCount (totalSynced is post-process).
        if (reportedTotalCount >= 0) {
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "projectItems", itemsReceived, reportedTotalCount, hasMore, "itemNodeId=" + issueNodeId);
        }

        log.debug("Completed project item pagination: nodeId={}, additionalItems={}", issueNodeId, totalSynced);
        return totalSynced;
    }

    /**
     * Processes a single embedded project item.
     * <p>
     * If the item's DTO has no {@code issueId} set (because the GraphQL query omitted
     * the {@code content { ... }} selection), the {@code parentIssueId} is injected as both
     * {@code issueId} and {@code contentDatabaseId} via
     * {@link GitHubProjectItemDTO#withIssueId(Long)}.
     *
     * @param embeddedItem the embedded item with project reference
     * @param context the processing context
     * @param parentIssueId the database ID of the parent issue/PR
     * @return true if the item was successfully processed
     */
    private boolean processEmbeddedItem(
            EmbeddedProjectItem embeddedItem, ProcessingContext context, Long parentIssueId) {
        if (embeddedItem == null || embeddedItem.item() == null) {
            return false;
        }

        Project project = resolveProject(embeddedItem.project());
        if (project == null) {
            log.debug(
                    "Skipped project item: reason=projectNotSynced, projectNodeId={}, itemNodeId={}",
                    embeddedItem.project() != null ? embeddedItem.project().nodeId() : "null",
                    embeddedItem.item().nodeId());
            return false;
        }

        try {
            GitHubProjectItemDTO itemDto = embeddedItem.item().withIssueId(parentIssueId);
            var result = projectItemProcessor.process(itemDto, project, context);
            if (result == null) {
                return false;
            }

            // Process inline field values for this item
            fieldValueSyncService.processFieldValues(
                    result.getId(),
                    itemDto.fieldValues(),
                    itemDto.fieldValuesTruncated(),
                    itemDto.fieldValuesEndCursor());

            return true;
        } catch (Exception e) {
            log.warn(
                    "Failed to process embedded project item: projectId={}, itemNodeId={}, error={}",
                    project.getId(),
                    embeddedItem.item().nodeId(),
                    e.getMessage(),
                    e);
            return false;
        }
    }

    /**
     * Resolves a Project entity from the embedded project reference.
     * <p>
     * Projects should be synced before issues/PRs in the sync order, so the project
     * should already exist. If it doesn't, we return null and the item is skipped.
     *
     * @param projectRef the embedded project reference (may be null)
     * @return the Project entity if found, null otherwise
     */
    @Nullable
    private Project resolveProject(@Nullable EmbeddedProjectReference projectRef) {
        if (projectRef == null || projectRef.nodeId() == null) {
            return null;
        }

        return projectRepository.findByNodeId(projectRef.nodeId()).orElse(null);
    }

    private @Nullable ClassificationResult classifyGraphQlErrors(@Nullable ClientGraphQlResponse response) {
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

    private boolean handleClassification(
            ClassificationResult classification, String phase, String scopeLabel, Object scopeValue, int retryAttempt) {
        Category category = classification.category();

        return switch (category) {
            case RETRYABLE -> {
                if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                    log.warn(
                            "Retrying {} after transient error: {}={}, attempt={}, error={}",
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
                log.warn(
                        "Aborting {} after {} retries: {}={}, error={}",
                        phase,
                        MAX_RETRY_ATTEMPTS,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case RATE_LIMITED -> {
                if (retryAttempt < MAX_RETRY_ATTEMPTS && classification.suggestedWait() != null) {
                    long waitMs = Math.min(classification.suggestedWait().toMillis(), 300_000);
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
                log.warn(
                        "Aborting {} due to rate limit: {}={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case NOT_FOUND -> {
                log.warn(
                        "Resource not found during {}: {}={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case AUTH_ERROR -> {
                log.warn(
                        "Authentication error during {}: {}={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case CLIENT_ERROR -> {
                log.warn(
                        "Client error during {}: {}={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            default -> {
                log.warn(
                        "Aborting {} due to error: {}={}, category={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        category,
                        classification.message());
                yield false;
            }
        };
    }

    private boolean waitForRateLimitIfNeeded(Long scopeId, String phase, String scopeLabel, Object scopeValue) {
        try {
            boolean waited = graphQlClientProvider.waitIfRateLimitLow(scopeId);
            if (waited) {
                log.info("Paused due to critical rate limit: phase={}, {}={}", phase, scopeLabel, scopeValue);
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for rate limit reset: phase={}, {}={}", phase, scopeLabel, scopeValue);
            return false;
        }
    }
}
