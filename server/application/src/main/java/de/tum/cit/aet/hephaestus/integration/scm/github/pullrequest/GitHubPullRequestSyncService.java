package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.PR_SYNC_PAGE_SIZE;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
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
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedCommentsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedProjectItemsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO.GitHubCommentDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.GitHubProjectItemSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.EmbeddedReviewThreadsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.EmbeddedReviewsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubReviewThreadDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.PullRequestWithReviewThreads;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.dto.GitHubPullRequestReviewEventDTO.GitHubReviewDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.GitHubPullRequestReviewCommentSyncService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service for synchronizing GitHub pull requests via GraphQL API.
 * <p>
 * This service fetches PRs using typed GraphQL models and delegates persistence
 * to GitHubPullRequestProcessor. Conversation comments, reviews, and review threads
 * (with comments) are fetched inline with PRs to avoid N+1 query patterns - only PRs
 * with more than 10 conversation comments, 10 reviews, or 10 review threads require
 * additional API calls for pagination.
 * <p>
 * Conversation (top-level) comments are the same IssueComment entity as issue comments and are
 * persisted through the same GitHubIssueCommentProcessor. They must be fetched here because
 * GitHub's {@code repository.issues} connection - the issue sync's source - excludes pull requests,
 * so this is the only sync path that can observe them.
 * <p>
 * Supports checkpoint/cursor persistence for resumable sync operations.
 * When a sync target ID is provided, the pagination cursor is persisted after
 * each page, allowing sync to resume from where it left off if interrupted.
 * <p>
 * Supports incremental sync via client-side filtering. Since GitHub's GraphQL API
 * doesn't support filterBy for pull requests, we implement incremental sync by:
 * 1. Fetching PRs ordered by updatedAt DESC
 * 2. Stopping pagination when we encounter a PR with updatedAt older than the last sync timestamp
 */
@Service
public class GitHubPullRequestSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestSyncService.class);
    private static final String QUERY_DOCUMENT = "GetRepositoryPullRequests";

    private final RepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubPullRequestProcessor pullRequestProcessor;
    private final GitHubPullRequestReviewSyncService reviewSyncService;
    private final GitHubPullRequestReviewCommentSyncService reviewCommentSyncService;
    private final GitHubIssueCommentProcessor issueCommentProcessor;
    private final GitHubIssueCommentSyncService issueCommentSyncService;
    private final GitHubProjectItemSyncService projectItemSyncService;
    private final BackfillStateProvider backfillStateProvider;
    private final TransactionTemplate transactionTemplate;
    private final GitHubSyncProperties syncProperties;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;

    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * PRs needing additional review pagination. Stores PR ID rather than an entity reference so
     * the reference survives if the creating transaction rolls back.
     */
    private record PullRequestWithReviewCursor(Long pullRequestId, String reviewCursor, int inlineCount) {}

    /**
     * PRs needing additional review thread/comment pagination. Stores PR ID rather than an
     * entity reference to survive transaction rollbacks.
     */
    private record PullRequestWithThreadCursor(Long pullRequestId, String threadCursor) {}

    /**
     * PRs needing additional conversation comment pagination. Stores PR ID rather than an entity
     * reference to survive transaction rollbacks.
     */
    private record PullRequestWithCommentCursor(Long pullRequestId, String commentCursor) {}

    /**
     * PRs needing additional project item pagination. Stores PR ID rather than an entity
     * reference to survive transaction rollbacks.
     *
     * @param pullRequestId the database ID of the PR
     * @param nodeId the GitHub GraphQL node ID (needed for pagination query)
     * @param projectItemCursor the pagination cursor to start from
     */
    private record PullRequestWithProjectItemCursor(Long pullRequestId, String nodeId, String projectItemCursor) {}

    public GitHubPullRequestSyncService(
            RepositoryRepository repositoryRepository,
            PullRequestRepository pullRequestRepository,
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubPullRequestProcessor pullRequestProcessor,
            GitHubPullRequestReviewSyncService reviewSyncService,
            GitHubPullRequestReviewCommentSyncService reviewCommentSyncService,
            GitHubIssueCommentProcessor issueCommentProcessor,
            GitHubIssueCommentSyncService issueCommentSyncService,
            GitHubProjectItemSyncService projectItemSyncService,
            BackfillStateProvider backfillStateProvider,
            TransactionTemplate transactionTemplate,
            GitHubSyncProperties syncProperties,
            SyncSchedulerProperties syncSchedulerProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper) {
        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.pullRequestProcessor = pullRequestProcessor;
        this.reviewSyncService = reviewSyncService;
        this.reviewCommentSyncService = reviewCommentSyncService;
        this.issueCommentProcessor = issueCommentProcessor;
        this.issueCommentSyncService = issueCommentSyncService;
        this.projectItemSyncService = projectItemSyncService;
        this.backfillStateProvider = backfillStateProvider;
        this.transactionTemplate = transactionTemplate;
        this.syncProperties = syncProperties;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.graphQlSyncHelper = graphQlSyncHelper;
    }

    /**
     * Synchronizes all pull requests for a repository without cursor persistence.
     * <p>
     * Reviews are fetched inline with PRs (first 10 per PR) to eliminate N+1 queries.
     * Only PRs with more than 10 reviews require additional API calls for pagination.
     * <p>
     * This method is kept for backward compatibility. For resumable sync with
     * cursor persistence, use {@link #syncForRepository(Long, Long, Long, String)}.
     * <p>
     * Note: This method intentionally does NOT use @Transactional to avoid long-running
     * transactions. Each page of PRs is processed in its own transaction.
     *
     * @param scopeId      the scope ID for authentication
     * @param repositoryId the repository ID to sync pull requests for
     * @return sync result containing status and count of pull requests synced
     */
    public SyncResult syncForRepository(Long scopeId, Long repositoryId) {
        return syncForRepository(scopeId, repositoryId, null, null, null);
    }

    /**
     * Synchronizes all pull requests for a repository with cursor persistence support.
     * <p>
     * Reviews are fetched inline with PRs (first 10 per PR) to eliminate N+1 queries.
     * Only PRs with more than 10 reviews require additional API calls for pagination.
     * <p>
     * When {@code syncTargetId} is provided, the pagination cursor is persisted after
     * each successfully processed page. This allows sync to resume from where it left
     * off if the process is interrupted (e.g., crash, timeout, deployment).
     * <p>
     * On successful completion, the cursor is cleared to indicate sync finished.
     *
     * @param scopeId       the scope ID for authentication
     * @param repositoryId  the repository ID to sync pull requests for
     * @param syncTargetId  the sync target ID for cursor persistence (null to disable)
     * @param initialCursor the cursor to resume from (null to start from beginning)
     * @return sync result containing status and count of pull requests synced
     */
    public SyncResult syncForRepository(
            Long scopeId, Long repositoryId, @Nullable Long syncTargetId, @Nullable String initialCursor) {
        return syncForRepository(scopeId, repositoryId, syncTargetId, initialCursor, null);
    }

    /**
     * Synchronizes pull requests for a repository with incremental sync support.
     * <p>
     * When {@code lastSyncTimestamp} is provided and incremental sync is enabled,
     * pagination stops when we encounter a PR with updatedAt older than the timestamp.
     * Since GitHub's GraphQL API doesn't support filterBy for PRs, we implement
     * client-side filtering by stopping pagination early.
     * <p>
     * If the GitHub GraphQL API silently truncates results (totalCount &gt; fetched),
     * this method automatically retries with state-based splitting (OPEN, CLOSED, MERGED).
     * Each state gets its own independent pagination connection, effectively tripling
     * the cap imposed by GitHub's connection limit.
     *
     * @param scopeId           the scope ID for authentication
     * @param repositoryId      the repository ID to sync pull requests for
     * @param syncTargetId      the sync target ID for cursor persistence (null to disable)
     * @param initialCursor     the cursor to resume from (null to start from beginning)
     * @param lastSyncTimestamp the timestamp of the last sync for incremental sync (null for full sync)
     * @return sync result containing status and count of pull requests synced
     */
    public SyncResult syncForRepository(
            Long scopeId,
            Long repositoryId,
            @Nullable Long syncTargetId,
            @Nullable String initialCursor,
            @Nullable Instant lastSyncTimestamp) {
        return syncForRepositoryWithStates(scopeId, repositoryId, syncTargetId, initialCursor, lastSyncTimestamp, null);
    }

    /**
     * Core sync implementation that accepts an optional PR state filter.
     * <p>
     * When {@code states} is null, all PR states are fetched. If the GitHub GraphQL API
     * silently truncates the connection (a known bug where hasNextPage returns false
     * prematurely), this method detects the overflow and — for the unfiltered case —
     * retries automatically by splitting into per-state queries (OPEN, CLOSED, MERGED).
     */
    private SyncResult syncForRepositoryWithStates(
            Long scopeId,
            Long repositoryId,
            @Nullable Long syncTargetId,
            @Nullable String initialCursor,
            @Nullable Instant lastSyncTimestamp,
            @Nullable List<String> states) {
        // Fetch repository outside of transaction to avoid holding locks during API calls
        Optional<Repository> repositoryResult = Objects.requireNonNull(
                transactionTemplate.execute(status -> repositoryRepository.findById(repositoryId)));
        Repository repository = repositoryResult.orElse(null);
        if (repository == null) {
            log.debug("Skipped pull request sync: reason=repositoryNotFound, repoId={}", repositoryId);
            return SyncResult.completed(0);
        }

        String nameWithOwner = repository.getNameWithOwner();
        String safeNameWithOwner = Objects.requireNonNullElse(sanitizeForLog(nameWithOwner), "<unknown>");
        Optional<RepositoryOwnerAndName> parsedName = GitHubRepositoryNameParser.parse(nameWithOwner);
        if (parsedName.isEmpty()) {
            log.warn("Skipped pull request sync: reason=invalidRepoNameFormat, repoName={}", safeNameWithOwner);
            return SyncResult.completed(0);
        }
        RepositoryOwnerAndName ownerAndName = parsedName.get();

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
        // Use extended timeout for PR sync — PR queries are complex (nested reviews,
        // labels, assignees) and large repositories like Artemis consistently hit the
        // standard 30s timeout, triggering retries that waste rate limit budget.
        Duration timeout = syncProperties.extendedGraphqlTimeout();

        Instant effectiveSyncTimestamp = lastSyncTimestamp;
        boolean isIncrementalSync = false;
        if (syncProperties.incrementalSyncEnabled()) {
            if (lastSyncTimestamp != null) {
                // Subtract buffer to ensure items updated just before recorded timestamp are still fetched
                effectiveSyncTimestamp = lastSyncTimestamp.minus(syncProperties.incrementalSyncBuffer());
                log.info(
                        "Starting incremental PR sync: repoName={}, since={}, buffer={}",
                        safeNameWithOwner,
                        effectiveSyncTimestamp,
                        syncProperties.incrementalSyncBuffer());
            } else {
                // First sync - use configured timeframe as fallback to limit initial data fetch
                effectiveSyncTimestamp = OffsetDateTime.now(ZoneOffset.UTC)
                        .minusDays(syncSchedulerProperties.timeframeDays())
                        .toInstant();
                log.info(
                        "Starting first PR sync with timeframe fallback: repoName={}, timeframeDays={}, since={}",
                        safeNameWithOwner,
                        syncSchedulerProperties.timeframeDays(),
                        effectiveSyncTimestamp);
            }
            isIncrementalSync = true;
        }

        // Probe the newest PR's updatedAt before running the expensive full query. Mirrors the
        // issue-sync totalCount probe (~1 point vs ~59). Only valid for the unfiltered,
        // start-from-scratch case: a state-split retry observes a different population, and a
        // resumed cursor means we already know there is work left to page through.
        if (isIncrementalSync && effectiveSyncTimestamp != null && initialCursor == null && states == null) {
            if (!hasPullRequestsUpdatedSince(
                    client, ownerAndName, effectiveSyncTimestamp, timeout, safeNameWithOwner)) {
                log.info(
                        "Skipped PR sync: reason=noUpdatedPullRequests, repoName={}, since={}",
                        safeNameWithOwner,
                        effectiveSyncTimestamp);
                return SyncResult.completed(0);
            }
        }

        int totalPRsSynced = 0;
        int totalReviewsSynced = 0;
        int totalReviewCommentsSynced = 0;
        int totalCommentsSynced = 0;
        int totalProjectItemsSynced = 0;
        int reportedTotalCount = -1;
        List<PullRequestWithReviewCursor> prsNeedingReviewPagination = new ArrayList<>();
        List<PullRequestWithThreadCursor> prsNeedingThreadPagination = new ArrayList<>();
        List<PullRequestWithCommentCursor> prsNeedingCommentPagination = new ArrayList<>();
        List<PullRequestWithProjectItemCursor> prsNeedingProjectItemPagination = new ArrayList<>();
        String cursor = initialCursor;
        boolean hasMore = true;
        int pageCount = 0;
        int retryAttempt = 0;
        boolean resuming = initialCursor != null;
        boolean stoppedByIncrementalSync = false;
        final boolean incrementalSync = isIncrementalSync;
        SyncResult.Status abortReason = null; // null means completed successfully

        if (initialCursor != null) {
            log.info(
                    "Resuming pull request sync from checkpoint: repoName={}, cursor={}",
                    safeNameWithOwner,
                    initialCursor.substring(0, Math.min(20, initialCursor.length())) + "...");
        }

        while (hasMore) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for pull requests: repoName={}, limit={}",
                        safeNameWithOwner,
                        MAX_PAGINATION_PAGES);
                break;
            }

            try {
                // Wrap the entire execute() call in Mono.defer() so retries also cover body
                // streaming — WebClient's ExchangeFilterFunction retries only the HTTP exchange,
                // not body consumption, and PrematureCloseException occurs during body streaming.
                final String currentCursor = cursor;
                final int currentPage = pageCount;
                ClientGraphQlResponse response = Mono.defer(() -> client.documentName(QUERY_DOCUMENT)
                                .variable("owner", ownerAndName.owner())
                                .variable("name", ownerAndName.name())
                                .variable(
                                        "first",
                                        adaptPageSize(
                                                PR_SYNC_PAGE_SIZE,
                                                graphQlClientProvider.getRateLimitRemaining(scopeId)))
                                .variable("after", currentCursor)
                                .variable("states", states)
                                .execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying PR sync after transport error: repoName={}, page={}, attempt={}, error={}",
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
                                "pull request sync",
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
                            "Invalid GraphQL response for pull requests: repoName={}, errors={}",
                            safeNameWithOwner,
                            response != null ? response.getErrors() : "null");
                    abortReason = SyncResult.Status.ABORTED_ERROR;
                    break;
                }

                graphQlClientProvider.trackRateLimit(scopeId, response);

                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                            scopeId, "pull request sync", "repoName", safeNameWithOwner, log)) {
                        abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                        break;
                    }
                }

                GHPullRequestConnection connection =
                        response.field("repository.pullRequests").toEntity(GHPullRequestConnection.class);

                if (connection == null
                        || connection.getNodes() == null
                        || connection.getNodes().isEmpty()) {
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = connection.getTotalCount();
                }

                // Process the page within its own transaction to keep transactions short.
                final Long repoId = repositoryId;
                PageSyncResult pageResult = transactionTemplate.execute(status -> {
                    // Re-fetch repository within transaction to ensure it's attached to session
                    Repository repo = repositoryRepository.findById(repoId).orElse(null);
                    if (repo == null) {
                        return new PageSyncResult(0, 0, 0, 0, 0);
                    }
                    // Eagerly initialize the lazy provider proxy to prevent
                    // LazyInitializationException when EventContext.from() accesses provider.getType()
                    org.hibernate.Hibernate.initialize(repo.getProvider());
                    ProcessingContext context = ProcessingContext.forSync(scopeId, repo);
                    return processPullRequestPage(
                            connection,
                            context,
                            scopeId,
                            prsNeedingReviewPagination,
                            prsNeedingThreadPagination,
                            prsNeedingCommentPagination,
                            prsNeedingProjectItemPagination);
                });

                if (pageResult != null) {
                    totalPRsSynced += pageResult.prsSynced();
                    totalReviewsSynced += pageResult.reviewsSynced();
                    totalReviewCommentsSynced += pageResult.reviewCommentsSynced();
                    totalCommentsSynced += pageResult.commentsSynced();
                    totalProjectItemsSynced += pageResult.projectItemsSynced();
                }

                GHPageInfo pageInfo = connection.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;

                // GitHub has a known bug where hasNextPage returns false prematurely around
                // ~500 nodes (community discussion #30687). When totalCount indicates more data
                // exists and we have a valid cursor, force-continue. If GitHub truly has no more
                // data, the next page returns empty nodes and the isEmpty() check above breaks
                // out cleanly.
                if (!hasMore && cursor != null && reportedTotalCount > 0 && totalPRsSynced < reportedTotalCount) {
                    log.info(
                            "Forcing pagination past hasNextPage=false (GitHub GraphQL ~500-node bug): fetched={}, totalCount={}, repo={}",
                            totalPRsSynced,
                            reportedTotalCount,
                            safeNameWithOwner);
                    hasMore = true;
                }

                // PRs are ordered by updatedAt DESC, so the last node in the page is the oldest —
                // compare it against effectiveSyncTimestamp to decide whether to stop.
                if (incrementalSync && hasMore) {
                    var nodes = connection.getNodes();
                    if (!nodes.isEmpty()) {
                        var oldestPr = nodes.get(nodes.size() - 1);
                        OffsetDateTime oldestUpdatedAt = oldestPr.getUpdatedAt();
                        if (oldestUpdatedAt != null
                                && oldestUpdatedAt.toInstant().isBefore(effectiveSyncTimestamp)) {
                            log.debug(
                                    "Stopping incremental PR sync: oldestUpdatedAt={} is before effectiveSyncTimestamp={}",
                                    oldestUpdatedAt,
                                    effectiveSyncTimestamp);
                            hasMore = false;
                            stoppedByIncrementalSync = true;
                        }
                    }
                }

                // Persist cursor checkpoint after each successful page (uses REQUIRES_NEW)
                if (syncTargetId != null && cursor != null && hasMore) {
                    persistCursorCheckpoint(syncTargetId, cursor);
                }

                retryAttempt = 0;

                // Throttle between pagination requests — rapid-fire complex queries trigger
                // 502/504s from GitHub.
                if (hasMore && !syncProperties.paginationThrottle().isZero()) {
                    try {
                        Thread.sleep(syncProperties.paginationThrottle().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("PR sync interrupted during throttle: repoName={}", safeNameWithOwner);
                        break;
                    }
                }
            } catch (InstallationNotFoundException e) {
                // Re-throw to abort the entire sync operation
                throw e;
            } catch (Exception e) {
                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                Category category = classification.category();

                switch (category) {
                    case RETRYABLE -> {
                        if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                            retryAttempt++;
                            log.warn(
                                    "Retrying PR sync after transient error: repoName={}, attempt={}, error={}",
                                    safeNameWithOwner,
                                    retryAttempt,
                                    classification.message());
                            try {
                                ExponentialBackoff.sleep(retryAttempt);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                log.warn("PR sync interrupted during backoff: repoName={}", safeNameWithOwner);
                                abortReason = SyncResult.Status.ABORTED_ERROR;
                                break;
                            }
                            continue; // Retry the same page
                        }
                        log.error(
                                "Failed to sync PRs after {} retries: repoName={}, error={}",
                                MAX_RETRY_ATTEMPTS,
                                safeNameWithOwner,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    case RATE_LIMITED -> {
                        if (retryAttempt < MAX_RETRY_ATTEMPTS && classification.suggestedWait() != null) {
                            retryAttempt++;
                            long waitMs = Math.min(
                                    classification.suggestedWait().toMillis(), 300_000 // Cap at 5 minutes
                                    );
                            log.warn(
                                    "Rate limited during PR sync, waiting: repoName={}, waitMs={}, attempt={}",
                                    safeNameWithOwner,
                                    waitMs,
                                    retryAttempt);
                            try {
                                Thread.sleep(waitMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                log.warn("PR sync interrupted during rate limit wait: repoName={}", safeNameWithOwner);
                                abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                                break;
                            }
                            continue; // Retry the same page
                        }
                        log.error(
                                "Aborting PR sync due to rate limiting: repoName={}, error={}",
                                safeNameWithOwner,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                        break;
                    }
                    case NOT_FOUND -> {
                        log.warn(
                                "Resource not found during PR sync, skipping: repoName={}, error={}",
                                safeNameWithOwner,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    case AUTH_ERROR -> {
                        log.error(
                                "Aborting PR sync due to auth error: repoName={}, error={}",
                                safeNameWithOwner,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    case CLIENT_ERROR -> {
                        log.error(
                                "Aborting PR sync due to client error: repoName={}, error={}",
                                safeNameWithOwner,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    default -> {
                        log.error(
                                "Aborting PR sync due to unknown error: repoName={}, error={}",
                                safeNameWithOwner,
                                classification.message(),
                                e);
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                }
                break;
            }
        }

        // Only meaningful during full sync — incremental sync intentionally fetches only
        // recently-updated items, so fetchedCount < totalCount is expected there by design. The
        // force-pagination above handles most truncation; if GitHub still returns empty pages
        // (truly can't serve more data), fall back to state splitting.
        if (reportedTotalCount >= 0 && !incrementalSync) {
            // totalPRsSynced is the comparand the fallback below uses. checkPaginated returns
            // true only on a real early-stop gap (empty page after force-pagination), not on the
            // benign case where GitHub's totalCount over-reports.
            boolean overflowDetected = GraphQlConnectionOverflowDetector.checkPaginated(
                    "pullRequests",
                    totalPRsSynced,
                    reportedTotalCount,
                    abortReason != null || hasMore,
                    safeNameWithOwner);
            if (overflowDetected && states == null && !stoppedByIncrementalSync) {
                log.info(
                        "PR connection overflow persists after force-pagination (fetched={}, total={}), "
                                + "falling back to state splitting: repo={}",
                        totalPRsSynced,
                        reportedTotalCount,
                        safeNameWithOwner);
                // Clear stale cursor from the truncated run before retrying
                if (syncTargetId != null) {
                    clearCursorCheckpoint(syncTargetId);
                }
                SyncResult[] stateResults = Stream.of("OPEN", "CLOSED", "MERGED")
                        .map(state -> {
                            log.info("State-split sync: repo={}, state={}", safeNameWithOwner, state);
                            return syncForRepositoryWithStates(
                                    scopeId,
                                    repositoryId,
                                    null, // no cursor persistence for state-split retries
                                    null, // start fresh
                                    lastSyncTimestamp,
                                    List.of(state));
                        })
                        .toArray(SyncResult[]::new);
                return SyncResult.merge(stateResults);
            }
            if (overflowDetected && states != null) {
                log.error(
                        "PR sync data loss: state={} still has more PRs than GitHub GraphQL can return. "
                                + "fetched={}, totalCount={}, repo={}. No further recovery possible.",
                        states,
                        totalPRsSynced,
                        reportedTotalCount,
                        safeNameWithOwner);
            }
        }

        if (!prsNeedingReviewPagination.isEmpty()) {
            log.debug(
                    "Starting additional review fetch for PRs with pagination: repoName={}, prCount={}",
                    safeNameWithOwner,
                    prsNeedingReviewPagination.size());
            for (PullRequestWithReviewCursor prWithCursor : prsNeedingReviewPagination) {
                // Pagination runs in a new transaction, so reload the repository eagerly.
                PullRequest pr = pullRequestRepository
                        .findByIdWithRepository(prWithCursor.pullRequestId())
                        .orElse(null);
                if (pr == null) {
                    log.debug(
                            "Skipped review pagination: reason=prNotFound (likely transaction rollback), prId={}",
                            prWithCursor.pullRequestId());
                    continue;
                }
                int additionalReviews = reviewSyncService.syncRemainingReviews(
                        scopeId, pr, prWithCursor.reviewCursor(), prWithCursor.inlineCount());
                totalReviewsSynced += additionalReviews;
            }
        }

        if (!prsNeedingThreadPagination.isEmpty()) {
            log.debug(
                    "Starting additional review thread fetch for PRs with pagination: repoName={}, prCount={}",
                    safeNameWithOwner,
                    prsNeedingThreadPagination.size());
            for (PullRequestWithThreadCursor prWithCursor : prsNeedingThreadPagination) {
                // Re-fetch with repository eagerly loaded to avoid LazyInitializationException in
                // the new transaction; skip if the creating transaction was rolled back.
                PullRequest pr = pullRequestRepository
                        .findByIdWithRepository(prWithCursor.pullRequestId())
                        .orElse(null);
                if (pr == null) {
                    log.debug(
                            "Skipped thread pagination: reason=prNotFound (likely transaction rollback), prId={}",
                            prWithCursor.pullRequestId());
                    continue;
                }
                int additionalComments =
                        reviewCommentSyncService.syncRemainingThreads(scopeId, pr, prWithCursor.threadCursor());
                totalReviewCommentsSynced += additionalComments;
            }
        }

        // Reuses the issue-comment sync service: a PR's conversation comments are IssueComments,
        // and that service routes on the parent's concrete type.
        if (!prsNeedingCommentPagination.isEmpty()) {
            log.debug(
                    "Starting additional conversation comment fetch for PRs with pagination: repoName={}, prCount={}",
                    safeNameWithOwner,
                    prsNeedingCommentPagination.size());
            for (PullRequestWithCommentCursor prWithCursor : prsNeedingCommentPagination) {
                // Pagination runs in a new transaction, so reload the repository eagerly.
                PullRequest pr = pullRequestRepository
                        .findByIdWithRepository(prWithCursor.pullRequestId())
                        .orElse(null);
                if (pr == null) {
                    log.debug(
                            "Skipped comment pagination: reason=prNotFound (likely transaction rollback), prId={}",
                            prWithCursor.pullRequestId());
                    continue;
                }
                int additionalComments =
                        issueCommentSyncService.syncRemainingComments(scopeId, pr, prWithCursor.commentCursor());
                totalCommentsSynced += additionalComments;
            }
        }

        if (!prsNeedingProjectItemPagination.isEmpty()) {
            log.debug(
                    "Starting additional project item fetch for PRs with pagination: repoName={}, prCount={}",
                    safeNameWithOwner,
                    prsNeedingProjectItemPagination.size());
            for (PullRequestWithProjectItemCursor prWithCursor : prsNeedingProjectItemPagination) {
                // Re-fetch with repository eagerly loaded to avoid LazyInitializationException in
                // the new transaction; skip if the creating transaction was rolled back.
                PullRequest pr = pullRequestRepository
                        .findByIdWithRepository(prWithCursor.pullRequestId())
                        .orElse(null);
                if (pr == null) {
                    log.debug(
                            "Skipped project item pagination: reason=prNotFound (likely transaction rollback), prId={}",
                            prWithCursor.pullRequestId());
                    continue;
                }
                int additionalItems = projectItemSyncService.syncRemainingProjectItems(
                        scopeId,
                        prWithCursor.nodeId(),
                        true, // This is a pull request
                        pr.requireRepository(),
                        prWithCursor.projectItemCursor(),
                        pr.getId());
                totalProjectItemsSynced += additionalItems;
            }
        }

        if (syncTargetId != null && !hasMore && abortReason == null) {
            clearCursorCheckpoint(syncTargetId);
        }

        SyncResult.Status finalStatus = abortReason != null ? abortReason : SyncResult.Status.COMPLETED;

        log.info(
                "Completed pull request sync: repoName={}, prCount={}, reviewCount={}, reviewCommentCount={}, commentCount={}, projectItemCount={}, prsWithReviewPagination={}, prsWithThreadPagination={}, prsWithCommentPagination={}, prsWithProjectItemPagination={}, resumed={}, incremental={}, stoppedByIncremental={}, status={}",
                safeNameWithOwner,
                totalPRsSynced,
                totalReviewsSynced,
                totalReviewCommentsSynced,
                totalCommentsSynced,
                totalProjectItemsSynced,
                prsNeedingReviewPagination.size(),
                prsNeedingThreadPagination.size(),
                prsNeedingCommentPagination.size(),
                prsNeedingProjectItemPagination.size(),
                resuming,
                incrementalSync,
                stoppedByIncrementalSync,
                finalStatus);
        return new SyncResult(finalStatus, totalPRsSynced);
    }

    private record PageSyncResult(
            int prsSynced, int reviewsSynced, int reviewCommentsSynced, int commentsSynced, int projectItemsSynced) {}

    /**
     * Processes a page of pull requests with their embedded conversation comments, reviews,
     * review threads, and project items.
     */
    private PageSyncResult processPullRequestPage(
            GHPullRequestConnection connection,
            ProcessingContext context,
            Long scopeId,
            List<PullRequestWithReviewCursor> prsNeedingReviewPagination,
            List<PullRequestWithThreadCursor> prsNeedingThreadPagination,
            List<PullRequestWithCommentCursor> prsNeedingCommentPagination,
            List<PullRequestWithProjectItemCursor> prsNeedingProjectItemPagination) {
        int prsSynced = 0;
        int reviewsSynced = 0;
        int reviewCommentsSynced = 0;
        int commentsSynced = 0;
        int projectItemsSynced = 0;

        for (var graphQlPullRequest : connection.getNodes()) {
            PullRequestWithReviewThreads prWithReviews =
                    PullRequestWithReviewThreads.fromPullRequest(graphQlPullRequest);
            if (prWithReviews == null || prWithReviews.pullRequest() == null) {
                continue;
            }

            PullRequest entity = pullRequestProcessor.process(prWithReviews.pullRequest(), context);
            if (entity == null) {
                continue;
            }
            prsSynced++;

            // Process embedded conversation comments through the shared issue-comment processor —
            // same entity, same table as issue comments. Keyed by PR number, which the processor
            // resolves against the PullRequest rows the IssueRepository lookup skips.
            EmbeddedCommentsDTO embeddedComments = prWithReviews.embeddedComments();
            for (GitHubCommentDTO commentDTO : embeddedComments.comments()) {
                if (issueCommentProcessor.process(commentDTO, entity.getNumber(), context) != null) {
                    commentsSynced++;
                }
            }

            if (embeddedComments.needsPagination()) {
                prsNeedingCommentPagination.add(new PullRequestWithCommentCursor(
                        entity.getId(), Objects.requireNonNull(embeddedComments.endCursor())));
            }

            // context enables activity event creation for inline reviews
            EmbeddedReviewsDTO embeddedReviews = prWithReviews.embeddedReviews();
            for (GitHubReviewDTO reviewDTO : embeddedReviews.reviews()) {
                if (reviewSyncService.processInlineReview(reviewDTO, entity.getId(), context) != null) {
                    reviewsSynced++;
                }
            }

            if (embeddedReviews.needsPagination()) {
                prsNeedingReviewPagination.add(new PullRequestWithReviewCursor(
                        entity.getId(),
                        Objects.requireNonNull(embeddedReviews.endCursor()),
                        embeddedReviews.reviews().size()));
            }

            EmbeddedReviewThreadsDTO embeddedThreads = prWithReviews.embeddedReviewThreads();
            for (GitHubReviewThreadDTO thread : embeddedThreads.threads()) {
                reviewCommentsSynced += reviewCommentSyncService.processThread(thread, entity, scopeId);
            }

            if (embeddedThreads.needsPagination()) {
                prsNeedingThreadPagination.add(new PullRequestWithThreadCursor(
                        entity.getId(), Objects.requireNonNull(embeddedThreads.endCursor())));
            }

            EmbeddedProjectItemsDTO embeddedProjectItems = prWithReviews.embeddedProjectItems();
            projectItemsSynced +=
                    projectItemSyncService.processEmbeddedItems(embeddedProjectItems, context, entity.getId());

            if (embeddedProjectItems.needsPagination()) {
                prsNeedingProjectItemPagination.add(new PullRequestWithProjectItemCursor(
                        entity.getId(),
                        prWithReviews.pullRequest().nodeId(),
                        Objects.requireNonNull(embeddedProjectItems.endCursor())));
            }
        }

        return new PageSyncResult(prsSynced, reviewsSynced, reviewCommentsSynced, commentsSynced, projectItemsSynced);
    }

    /**
     * Lightweight probe answering "has any pull request been updated since {@code since}?".
     * <p>
     * Uses the GetRepositoryPullRequestLatestUpdate query, which costs ~1 rate limit point
     * against ~59 for the full GetRepositoryPullRequests query. Because GitHub's pullRequests
     * connection offers no server-side {@code since} filter, the probe fetches the single
     * most-recently-updated PR (UPDATED_AT DESC, first: 1) and compares its {@code updatedAt}
     * against the cutoff: if even the newest PR predates the cutoff, no PR can have changed.
     * <p>
     * Fails open — any probe failure returns {@code true} so the caller runs the full sync
     * rather than silently skipping data.
     *
     * @param client            the GraphQL client
     * @param ownerAndName      the parsed repository owner and name
     * @param since             the incremental cutoff
     * @param timeout           the GraphQL request timeout
     * @param safeNameWithOwner sanitized repository name for logging
     * @return {@code false} only when the probe positively proves nothing changed
     */
    private boolean hasPullRequestsUpdatedSince(
            HttpGraphQlClient client,
            RepositoryOwnerAndName ownerAndName,
            Instant since,
            Duration timeout,
            String safeNameWithOwner) {
        try {
            ClientGraphQlResponse response = Mono.defer(
                            () -> client.documentName("GetRepositoryPullRequestLatestUpdate")
                                    .variable("owner", ownerAndName.owner())
                                    .variable("name", ownerAndName.name())
                                    .execute())
                    .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                            .maxBackoff(TRANSPORT_MAX_BACKOFF)
                            .jitter(JITTER_FACTOR)
                            .filter(ScmTransportErrors::isTransportError)
                            .doBeforeRetry(signal -> log.warn(
                                    "Retrying after transport error: context=pullRequestUpdateProbe, repoName={}, attempt={}, error={}",
                                    safeNameWithOwner,
                                    signal.totalRetries() + 1,
                                    signal.failure().getMessage())))
                    .block(timeout);

            if (response == null || !response.isValid()) {
                log.warn(
                        "PR update probe returned invalid response, falling back to full sync: repoName={}",
                        safeNameWithOwner);
                return true;
            }

            List<Map<String, Object>> nodes = response.field("repository.pullRequests.nodes")
                    .toEntityList(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (nodes.isEmpty()) {
                // Repository genuinely has no pull requests at all — nothing to sync.
                return false;
            }

            Object rawUpdatedAt = nodes.get(0).get("updatedAt");
            if (rawUpdatedAt == null) {
                log.warn(
                        "PR update probe returned null updatedAt, falling back to full sync: repoName={}",
                        safeNameWithOwner);
                return true;
            }

            Instant newestUpdatedAt =
                    OffsetDateTime.parse(rawUpdatedAt.toString()).toInstant();
            return !newestUpdatedAt.isBefore(since);
        } catch (Exception e) {
            // Fail-open: if the probe itself fails, proceed with the full sync
            log.warn(
                    "PR update probe failed, falling back to full sync: repoName={}, error={}",
                    safeNameWithOwner,
                    e.getMessage());
            return true;
        }
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
            backfillStateProvider.updateSyncCursor(syncTargetId, SyncCursorKind.PULL_REQUEST, cursor);
            log.debug("Persisted PR sync cursor checkpoint: syncTargetId={}", syncTargetId);
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
            backfillStateProvider.updateSyncCursor(syncTargetId, SyncCursorKind.PULL_REQUEST, null);
            log.debug("Cleared PR sync cursor checkpoint: syncTargetId={}", syncTargetId);
        });
    }
}
