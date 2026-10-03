package de.tum.cit.aet.hephaestus.integration.scm.github.issue;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.ISSUE_SYNC_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncCursorKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ExponentialBackoff;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser.RepositoryOwnerAndName;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedCommentsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedProjectItemsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.IssueWithComments;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO.GitHubCommentDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.GitHubProjectItemSyncService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service for synchronizing GitHub issues via GraphQL API.
 * <p>
 * Uses typed GraphQL models for type-safe deserialization and delegates
 * persistence to GitHubIssueProcessor.
 * <p>
 * Comments are fetched inline with issues (first 10 per issue) to avoid N+1 queries.
 * Only issues with more than 10 comments require additional API calls for pagination.
 * <p>
 * Supports checkpoint/cursor persistence for resumable sync operations.
 * When a sync target ID is provided, the pagination cursor is persisted after
 * each page, allowing sync to resume from where it left off if interrupted.
 */
@Service
public class GitHubIssueSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssueSyncService.class);
    private static final String QUERY_DOCUMENT = "GetRepositoryIssues";

    private final RepositoryRepository repositoryRepository;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubIssueProcessor issueProcessor;
    private final GitHubIssueCommentProcessor commentProcessor;
    private final GitHubIssueCommentSyncService commentSyncService;
    private final GitHubProjectItemSyncService projectItemSyncService;
    private final BackfillStateProvider backfillStateProvider;
    private final TransactionTemplate transactionTemplate;
    private final GitHubSyncProperties syncProperties;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;

    /** Maximum number of retry attempts for transient failures. */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * Container for issues that need additional comment pagination.
     */
    private record IssueWithCommentCursor(Issue issue, String commentCursor) {}

    /**
     * Container for issues that need additional project item pagination.
     *
     * @param issue the processed Issue entity
     * @param nodeId the GitHub GraphQL node ID (needed for pagination query)
     * @param projectItemCursor the pagination cursor to start from
     */
    private record IssueWithProjectItemCursor(Issue issue, String nodeId, String projectItemCursor) {}

    public GitHubIssueSyncService(
            RepositoryRepository repositoryRepository,
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubIssueProcessor issueProcessor,
            GitHubIssueCommentProcessor commentProcessor,
            GitHubIssueCommentSyncService commentSyncService,
            GitHubProjectItemSyncService projectItemSyncService,
            BackfillStateProvider backfillStateProvider,
            TransactionTemplate transactionTemplate,
            GitHubSyncProperties syncProperties,
            SyncSchedulerProperties syncSchedulerProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper) {
        this.repositoryRepository = repositoryRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.issueProcessor = issueProcessor;
        this.commentProcessor = commentProcessor;
        this.commentSyncService = commentSyncService;
        this.projectItemSyncService = projectItemSyncService;
        this.backfillStateProvider = backfillStateProvider;
        this.transactionTemplate = transactionTemplate;
        this.syncProperties = syncProperties;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.graphQlSyncHelper = graphQlSyncHelper;
    }

    /**
     * Synchronizes all issues for a repository without cursor persistence.
     * <p>
     * This method is kept for backward compatibility. For resumable sync with
     * cursor persistence, use {@link #syncForRepository(Long, Long, Long, String)}.
     * <p>
     * Note: This method intentionally does NOT use @Transactional to avoid long-running
     * transactions. Each page of issues is processed in its own transaction.
     *
     * @param scopeId      the scope ID for authentication
     * @param repositoryId the repository ID to sync issues for
     * @return sync result containing status and count of issues synced
     */
    public SyncResult syncForRepository(Long scopeId, Long repositoryId) {
        return syncForRepository(scopeId, repositoryId, null, null, null);
    }

    /**
     * Synchronizes all issues for a repository with cursor persistence support.
     * <p>
     * When {@code syncTargetId} is provided, the pagination cursor is persisted after
     * each successfully processed page. This allows sync to resume from where it left
     * off if the process is interrupted (e.g., crash, timeout, deployment).
     * <p>
     * On successful completion, the cursor is cleared to indicate sync finished.
     *
     * @param scopeId       the scope ID for authentication
     * @param repositoryId  the repository ID to sync issues for
     * @param syncTargetId  the sync target ID for cursor persistence (null to disable)
     * @param initialCursor the cursor to resume from (null to start from beginning)
     * @return sync result containing status and count of issues synced
     */
    public SyncResult syncForRepository(
            Long scopeId, Long repositoryId, @Nullable Long syncTargetId, @Nullable String initialCursor) {
        return syncForRepository(scopeId, repositoryId, syncTargetId, initialCursor, null);
    }

    /**
     * Synchronizes issues for a repository with incremental sync support.
     * <p>
     * When {@code lastSyncTimestamp} is provided and incremental sync is enabled,
     * only issues updated after that timestamp are fetched using GitHub's filterBy.since parameter.
     * <p>
     * When {@code syncTargetId} is provided, the pagination cursor is persisted after
     * each successfully processed page. This allows sync to resume from where it left
     * off if the process is interrupted (e.g., crash, timeout, deployment).
     * <p>
     * On successful completion, the cursor is cleared to indicate sync finished.
     * <p>
     * Note: This method intentionally does NOT use @Transactional to avoid long-running
     * transactions. Each page of issues is processed in its own transaction to keep
     * individual transactions short (seconds, not minutes) while maintaining data
     * consistency within each page.
     *
     * @param scopeId           the scope ID for authentication
     * @param repositoryId      the repository ID to sync issues for
     * @param syncTargetId      the sync target ID for cursor persistence (null to disable)
     * @param initialCursor     the cursor to resume from (null to start from beginning)
     * @param lastSyncTimestamp the timestamp of the last sync for incremental sync (null for full sync)
     * @return sync result containing status and count of issues synced
     */
    public SyncResult syncForRepository(
            Long scopeId,
            Long repositoryId,
            @Nullable Long syncTargetId,
            @Nullable String initialCursor,
            @Nullable Instant lastSyncTimestamp) {
        // Fetch repository outside of transaction to avoid holding locks during API calls
        Optional<Repository> repositoryResult =
                transactionTemplate.execute(status -> repositoryRepository.findById(repositoryId));
        @Nullable Repository repository = repositoryResult == null ? null : repositoryResult.orElse(null);
        if (repository == null) {
            log.debug("Skipped issue sync: reason=repositoryNotFound, repoId={}", repositoryId);
            return SyncResult.completed(0);
        }

        String nameWithOwner = repository.getNameWithOwner();
        String safeNameWithOwner = Objects.requireNonNullElse(sanitizeForLog(nameWithOwner), "");
        Optional<RepositoryOwnerAndName> parsedName = GitHubRepositoryNameParser.parse(nameWithOwner);
        if (parsedName.isEmpty()) {
            log.warn("Skipped issue sync: reason=invalidRepoNameFormat, repoName={}", safeNameWithOwner);
            return SyncResult.completed(0);
        }
        RepositoryOwnerAndName ownerAndName = parsedName.get();

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
        Duration timeout = syncProperties.graphqlTimeout();

        // Determine the 'since' parameter for incremental sync
        // Apply safety buffer to handle clock skew between server and GitHub
        OffsetDateTime sinceDateTime = null;
        boolean isIncrementalSync = false;
        if (syncProperties.incrementalSyncEnabled()) {
            if (lastSyncTimestamp != null) {
                // Subtract buffer to ensure items updated just before recorded timestamp are still fetched
                Instant bufferedTimestamp = lastSyncTimestamp.minus(syncProperties.incrementalSyncBuffer());
                sinceDateTime = bufferedTimestamp.atOffset(ZoneOffset.UTC);
                log.info(
                        "Starting incremental issue sync: repoName={}, since={}, buffer={}",
                        safeNameWithOwner,
                        sinceDateTime,
                        syncProperties.incrementalSyncBuffer());
            } else {
                // First sync - use configured timeframe as fallback to limit initial data fetch
                sinceDateTime = OffsetDateTime.now(ZoneOffset.UTC).minusDays(syncSchedulerProperties.timeframeDays());
                log.info(
                        "Starting first issue sync with timeframe fallback: repoName={}, timeframeDays={}, since={}",
                        safeNameWithOwner,
                        syncSchedulerProperties.timeframeDays(),
                        sinceDateTime);
            }
            isIncrementalSync = true;
        }

        // P0 optimization: probe totalCount before running the expensive full query.
        // The full GetRepositoryIssues query costs ~59 rate limit points due to deeply nested
        // embedded data (comments, projectItems, assignees, labels). In our audit, 119 of 122
        // incremental issue syncs returned 0 issues — wasting ~7,000 points per run.
        // This lightweight probe costs ~1 point and lets us skip the expensive query entirely.
        if (isIncrementalSync && sinceDateTime != null && initialCursor == null) {
            int updatedCount = probeIssueCount(client, ownerAndName, sinceDateTime, timeout, safeNameWithOwner);
            if (updatedCount == 0) {
                log.info(
                        "Skipped issue sync: reason=noUpdatedIssues, repoName={}, since={}",
                        safeNameWithOwner,
                        sinceDateTime);
                return SyncResult.completed(0);
            }
            log.info(
                    "Issue count probe found {} updated issues, proceeding with full sync: repoName={}",
                    updatedCount,
                    safeNameWithOwner);
        }

        int totalIssuesSynced = 0;
        // Raw nodes received across all pages — the apples-to-apples count for issues.totalCount.
        // (totalIssuesSynced is post-filter: it omits skipped/unparseable nodes, so it would
        // report a phantom gap even on a fully-paginated sync.)
        int issuesReceived = 0;
        int totalCommentsSynced = 0;
        int totalProjectItemsSynced = 0;
        int reportedTotalCount = -1;
        List<IssueWithCommentCursor> issuesNeedingCommentPagination = new ArrayList<>();
        List<IssueWithProjectItemCursor> issuesNeedingProjectItemPagination = new ArrayList<>();
        String cursor = initialCursor;
        boolean hasMore = true;
        int pageCount = 0;
        int retryAttempt = 0;
        boolean resuming = initialCursor != null;
        final boolean incrementalSync = isIncrementalSync;
        SyncResult.Status abortReason = null; // null means completed successfully

        if (initialCursor != null) {
            log.info(
                    "Resuming issue sync from checkpoint: repoName={}, cursor={}",
                    safeNameWithOwner,
                    initialCursor.substring(0, Math.min(20, initialCursor.length())) + "...");
        }

        while (hasMore) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for issue sync: repoName={}, limit={}",
                        safeNameWithOwner,
                        MAX_PAGINATION_PAGES);
                break;
            }

            try {
                // Build the GraphQL request with optional 'since' parameter for incremental sync.
                // Use Mono.defer() to wrap the entire execute() call so retries cover body streaming.
                // This is CRITICAL: WebClient ExchangeFilterFunction retries only cover the HTTP exchange,
                // not body consumption. PrematureCloseException occurs DURING body streaming.
                final String currentCursor = cursor;
                final int currentPage = pageCount;
                final OffsetDateTime sinceDt = sinceDateTime;

                ClientGraphQlResponse response = Mono.defer(() -> {
                            var requestBuilder = client.documentName(QUERY_DOCUMENT)
                                    .variable("owner", ownerAndName.owner())
                                    .variable("name", ownerAndName.name())
                                    .variable(
                                            "first",
                                            adaptPageSize(
                                                    ISSUE_SYNC_PAGE_SIZE,
                                                    graphQlClientProvider.getRateLimitRemaining(scopeId)))
                                    .variable("after", currentCursor);

                            // Add 'since' parameter for incremental sync (filters by updatedAt >= since)
                            if (sinceDt != null) {
                                requestBuilder = requestBuilder.variable("since", sinceDt.toString());
                            }

                            return requestBuilder.execute();
                        })
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying issue sync after transport error: repoName={}, page={}, attempt={}, error={}",
                                        safeNameWithOwner,
                                        currentPage,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(timeout);

                if (response == null || !response.isValid()) {
                    ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(response);
                    if (classification != null) {
                        if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                                classification,
                                retryAttempt,
                                MAX_RETRY_ATTEMPTS,
                                "issue sync",
                                "repoName",
                                safeNameWithOwner,
                                log))) {
                            retryAttempt++;
                            continue;
                        }
                        abortReason = classification.category() == Category.RATE_LIMITED
                                ? SyncResult.Status.ABORTED_RATE_LIMIT
                                : SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    log.warn(
                            "Received invalid GraphQL response: repoName={}, errors={}",
                            safeNameWithOwner,
                            response != null ? response.getErrors() : "null");
                    abortReason = SyncResult.Status.ABORTED_ERROR;
                    break;
                }

                // Track rate limit from response (per-scope tracking)
                graphQlClientProvider.trackRateLimit(scopeId, response);

                // Check if we should pause due to rate limiting
                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                            scopeId, "issue sync", "repoName", safeNameWithOwner, log)) {
                        abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                        break;
                    }
                }

                GHIssueConnection connection =
                        response.field("repository.issues").toEntity(GHIssueConnection.class);

                if (connection == null
                        || connection.getNodes() == null
                        || connection.getNodes().isEmpty()) {
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = connection.getTotalCount();
                }

                issuesReceived += connection.getNodes().size();

                // Process the page within its own transaction to keep transactions short
                final Long repoId = repositoryId;
                PageResult pageResult = transactionTemplate.execute(status -> {
                    Repository repo = repositoryRepository
                            .findByIdWithOrganization(repoId)
                            .orElse(null);
                    if (repo == null) {
                        return new PageResult(0, 0, 0);
                    }
                    ProcessingContext context = ProcessingContext.forSync(scopeId, repo);
                    return processIssuePage(
                            connection, context, issuesNeedingCommentPagination, issuesNeedingProjectItemPagination);
                });

                if (pageResult != null) {
                    totalIssuesSynced += pageResult.issueCount;
                    totalCommentsSynced += pageResult.commentCount;
                    totalProjectItemsSynced += pageResult.projectItemCount;
                }

                GHPageInfo pageInfo = connection.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;

                // Persist cursor checkpoint after each successful page (uses REQUIRES_NEW)
                if (syncTargetId != null && cursor != null && hasMore) {
                    persistCursorCheckpoint(syncTargetId, cursor);
                }

                // Reset retry counter after successful page
                retryAttempt = 0;
            } catch (InstallationNotFoundException e) {
                // Re-throw to abort the entire sync operation
                throw e;
            } catch (Exception e) {
                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                Category category = classification.category();

                switch (category) {
                    case RETRYABLE -> {
                        // Transient error - retry with exponential backoff
                        if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                            retryAttempt++;
                            log.warn(
                                    "Retrying issue sync after transient error: repoName={}, attempt={}, error={}",
                                    safeNameWithOwner,
                                    retryAttempt,
                                    classification.message());
                            try {
                                ExponentialBackoff.sleep(retryAttempt);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                log.warn("Issue sync interrupted during backoff: repoName={}", safeNameWithOwner);
                                abortReason = SyncResult.Status.ABORTED_ERROR;
                                break;
                            }
                            continue; // Retry the same page
                        }
                        log.error(
                                "Failed to sync issues after {} retries: repoName={}, error={}",
                                MAX_RETRY_ATTEMPTS,
                                safeNameWithOwner,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                    }
                    case RATE_LIMITED -> {
                        // Rate limited - wait for reset time if available, then retry
                        if (retryAttempt < MAX_RETRY_ATTEMPTS && classification.suggestedWait() != null) {
                            retryAttempt++;
                            long waitMs = Math.min(
                                    classification.suggestedWait().toMillis(), 300_000 // Cap at 5 minutes
                                    );
                            log.warn(
                                    "Rate limited during issue sync, waiting: repoName={}, waitMs={}, attempt={}",
                                    safeNameWithOwner,
                                    waitMs,
                                    retryAttempt);
                            try {
                                Thread.sleep(waitMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                log.warn(
                                        "Issue sync interrupted during rate limit wait: repoName={}",
                                        safeNameWithOwner);
                                abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                                break;
                            }
                            continue; // Retry the same page
                        }
                        log.error(
                                "Aborting issue sync due to rate limiting: repoName={}, error={}",
                                safeNameWithOwner,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                    }
                    case NOT_FOUND -> {
                        // Resource not found - skip and continue
                        log.warn(
                                "Resource not found during issue sync, skipping: repoName={}, error={}",
                                safeNameWithOwner,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                    }
                    case AUTH_ERROR -> {
                        // Authentication error - abort sync
                        log.error(
                                "Aborting issue sync due to auth error: repoName={}, error={}",
                                safeNameWithOwner,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                    }
                    case CLIENT_ERROR -> {
                        // Client error - abort sync
                        log.error(
                                "Aborting issue sync due to client error: repoName={}, error={}",
                                safeNameWithOwner,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                    }
                    default -> {
                        // Unknown error - log and abort
                        log.error(
                                "Aborting issue sync due to unknown error: repoName={}, error={}",
                                safeNameWithOwner,
                                classification.message(),
                                e);
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                    }
                }
                break;
            }
        }

        // Full-sync only; compare raw issue nodes received (not the post-filter count) vs totalCount.
        // hasMore stays true on every early break (error/rate-limit, page cap, empty/null page) and is
        // false only after clean exhaustion — so it is the complete early-stop signal.
        if (reportedTotalCount >= 0 && !incrementalSync) {
            boolean stoppedEarly = abortReason != null || hasMore;
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "issues", issuesReceived, reportedTotalCount, stoppedEarly, safeNameWithOwner);
        }

        // Fetch remaining comments for issues with >10 comments (using cursor for efficient continuation)
        // Each call to syncRemainingComments handles its own transactions
        if (!issuesNeedingCommentPagination.isEmpty()) {
            log.debug(
                    "Starting additional comment fetch for issues with pagination: repoName={}, issueCount={}",
                    safeNameWithOwner,
                    issuesNeedingCommentPagination.size());
            for (IssueWithCommentCursor issueWithCursor : issuesNeedingCommentPagination) {
                int additionalComments = commentSyncService.syncRemainingComments(
                        scopeId, issueWithCursor.issue(), issueWithCursor.commentCursor());
                totalCommentsSynced += additionalComments;
            }
        }

        // Fetch remaining project items for issues in >5 projects (using cursor for efficient continuation)
        // Each call to syncRemainingProjectItems handles its own transactions
        if (!issuesNeedingProjectItemPagination.isEmpty()) {
            log.debug(
                    "Starting additional project item fetch for issues with pagination: repoName={}, issueCount={}",
                    safeNameWithOwner,
                    issuesNeedingProjectItemPagination.size());
            for (IssueWithProjectItemCursor issueWithCursor : issuesNeedingProjectItemPagination) {
                int additionalItems = projectItemSyncService.syncRemainingProjectItems(
                        scopeId,
                        issueWithCursor.nodeId(),
                        false, // Not a pull request
                        issueWithCursor.issue().requireRepository(),
                        issueWithCursor.projectItemCursor(),
                        issueWithCursor.issue().getId());
                totalProjectItemsSynced += additionalItems;
            }
        }

        // Clear cursor on successful completion (uses REQUIRES_NEW)
        // Only clear if sync completed without abort
        if (syncTargetId != null && !hasMore && abortReason == null) {
            clearCursorCheckpoint(syncTargetId);
        }

        // Determine the final result status
        SyncResult.Status finalStatus = abortReason != null ? abortReason : SyncResult.Status.COMPLETED;

        log.info(
                "Completed issue sync: repoName={}, issueCount={}, commentCount={}, projectItemCount={}, issuesWithCommentPagination={}, issuesWithProjectItemPagination={}, scopeId={}, resumed={}, incremental={}, status={}",
                safeNameWithOwner,
                totalIssuesSynced,
                totalCommentsSynced,
                totalProjectItemsSynced,
                issuesNeedingCommentPagination.size(),
                issuesNeedingProjectItemPagination.size(),
                scopeId,
                resuming,
                incrementalSync,
                finalStatus);
        return new SyncResult(finalStatus, totalIssuesSynced);
    }

    /**
     * Result container for page processing.
     */
    private record PageResult(int issueCount, int commentCount, int projectItemCount) {}

    /**
     * Processes a page of issues with embedded comments and project items.
     * <p>
     * Also populates lists of issues that need additional pagination for:
     * - Comments (when issue has more than 10 embedded comments)
     * - Project items (when issue is in more than 5 projects)
     */
    private PageResult processIssuePage(
            GHIssueConnection connection,
            ProcessingContext context,
            List<IssueWithCommentCursor> issuesNeedingCommentPagination,
            List<IssueWithProjectItemCursor> issuesNeedingProjectItemPagination) {
        int issuesSynced = 0;
        int commentsSynced = 0;
        int projectItemsSynced = 0;

        for (var graphQlIssue : connection.getNodes()) {
            IssueWithComments issueWithComments = IssueWithComments.fromIssue(graphQlIssue);
            if (issueWithComments == null || issueWithComments.issue() == null) {
                continue;
            }

            // Process the issue
            Issue entity = issueProcessor.process(issueWithComments.issue(), context);
            if (entity == null) {
                continue;
            }
            issuesSynced++;

            // Process embedded comments
            EmbeddedCommentsDTO embeddedComments = issueWithComments.embeddedComments();
            for (GitHubCommentDTO commentDTO : embeddedComments.comments()) {
                if (commentProcessor.process(commentDTO, entity.getNumber(), context) != null) {
                    commentsSynced++;
                }
            }

            // Track issues that need additional comment pagination (with cursor for efficient continuation)
            if (embeddedComments.needsPagination()) {
                issuesNeedingCommentPagination.add(
                        new IssueWithCommentCursor(entity, Objects.requireNonNull(embeddedComments.endCursor())));
            }

            // Process embedded project items
            EmbeddedProjectItemsDTO embeddedProjectItems = issueWithComments.embeddedProjectItems();
            projectItemsSynced +=
                    projectItemSyncService.processEmbeddedItems(embeddedProjectItems, context, entity.getId());

            // Track issues that need additional project item pagination
            if (embeddedProjectItems.needsPagination()) {
                String issueNodeId = issueWithComments.issue().nodeId();
                if (issueNodeId == null) {
                    log.warn("Skipped project-item pagination: reason=missingIssueNodeId, issueId={}", entity.getId());
                    continue;
                }
                issuesNeedingProjectItemPagination.add(new IssueWithProjectItemCursor(
                        entity, issueNodeId, Objects.requireNonNull(embeddedProjectItems.endCursor())));
            }
        }

        return new PageResult(issuesSynced, commentsSynced, projectItemsSynced);
    }

    /**
     * Persists the cursor checkpoint in a new transaction.
     * This ensures the cursor is saved even if the main transaction is rolled back.
     * <p>
     * Uses programmatic transaction management with REQUIRES_NEW propagation
     * to avoid Spring proxy issues with self-invocation.
     */
    private void persistCursorCheckpoint(Long syncTargetId, String cursor) {
        TransactionTemplate requiresNewTemplate =
                new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNewTemplate.executeWithoutResult(status -> {
            backfillStateProvider.updateSyncCursor(syncTargetId, SyncCursorKind.ISSUE, cursor);
            log.debug("Persisted issue sync cursor checkpoint: syncTargetId={}", syncTargetId);
        });
    }

    /**
     * Clears the cursor checkpoint in a new transaction.
     * Called when sync completes successfully to indicate no resume needed.
     * <p>
     * Uses programmatic transaction management with REQUIRES_NEW propagation
     * to avoid Spring proxy issues with self-invocation.
     */
    private void clearCursorCheckpoint(Long syncTargetId) {
        TransactionTemplate requiresNewTemplate =
                new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNewTemplate.executeWithoutResult(status -> {
            backfillStateProvider.updateSyncCursor(syncTargetId, SyncCursorKind.ISSUE, null);
            log.debug("Cleared issue sync cursor checkpoint: syncTargetId={}", syncTargetId);
        });
    }

    /**
     * Lightweight probe to check if a repository has any issues updated since a given timestamp.
     * <p>
     * Uses the GetRepositoryIssueCount query which costs ~1 rate limit point (vs ~59 for the
     * full GetRepositoryIssues query). Returns the totalCount of issues matching the filter.
     * <p>
     * If the probe fails for any reason, returns -1 to signal the caller should proceed with
     * the full sync (fail-open behavior to avoid skipping data).
     *
     * @param client           the GraphQL client
     * @param ownerAndName     the parsed repository owner and name
     * @param sinceDateTime    the timestamp to filter issues by (updatedAt >= since)
     * @param timeout          the GraphQL request timeout
     * @param safeNameWithOwner sanitized repository name for logging
     * @return totalCount of matching issues, or -1 if the probe failed
     */
    private int probeIssueCount(
            HttpGraphQlClient client,
            RepositoryOwnerAndName ownerAndName,
            OffsetDateTime sinceDateTime,
            Duration timeout,
            String safeNameWithOwner) {
        try {
            ClientGraphQlResponse response = Mono.defer(() -> client.documentName("GetRepositoryIssueCount")
                            .variable("owner", ownerAndName.owner())
                            .variable("name", ownerAndName.name())
                            .variable("since", sinceDateTime.toString())
                            .execute())
                    .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                            .maxBackoff(TRANSPORT_MAX_BACKOFF)
                            .jitter(JITTER_FACTOR)
                            .filter(ScmTransportErrors::isTransportError)
                            .doBeforeRetry(signal -> log.warn(
                                    "Retrying after transport error: context=issueCountProbe, repoName={}, attempt={}, error={}",
                                    safeNameWithOwner,
                                    signal.totalRetries() + 1,
                                    signal.failure().getMessage())))
                    .block(timeout);

            if (response == null || !response.isValid()) {
                log.warn(
                        "Issue count probe returned invalid response, falling back to full sync: repoName={}",
                        safeNameWithOwner);
                return -1;
            }

            Integer totalCount = response.field("repository.issues.totalCount").toEntity(Integer.class);
            if (totalCount == null) {
                log.warn(
                        "Issue count probe returned null totalCount, falling back to full sync: repoName={}",
                        safeNameWithOwner);
                return -1;
            }

            return totalCount;
        } catch (Exception e) {
            // Fail-open: if the probe itself fails, proceed with the full sync
            log.warn(
                    "Issue count probe failed, falling back to full sync: repoName={}, error={}",
                    safeNameWithOwner,
                    e.getMessage());
            return -1;
        }
    }
}
