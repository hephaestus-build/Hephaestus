package de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.DEFAULT_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncCursorKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlErrorUtils;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser.RepositoryOwnerAndName;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedCommentsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.IssueWithComments;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.EmbeddedReviewThreadsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.EmbeddedReviewsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.PullRequestWithReviewThreads;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.GitHubPullRequestReviewCommentSyncService;
import java.io.Serial;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service for backfilling historical GitHub data that predates the initial sync window.
 *
 * <h2>Problem Statement</h2>
 * When a repository is first synced, only issues/PRs from the last N days (configured via
 * {@code hephaestus.sync.timeframe-days}) are fetched. Historical data before that window
 * is NOT synced. This service fills that gap.
 *
 * <h2>Strategy: CREATED_AT DESC (Newest First)</h2>
 * <p>We use {@code orderBy: CREATED_AT, direction: DESC} which provides:
 * <ul>
 *   <li><b>Most relevant first</b>: Recent issues/PRs are synced before older ones</li>
 *   <li><b>Stable ordering</b>: Creation time doesn't change, so pagination is deterministic</li>
 *   <li><b>Clear progress</b>: highWaterMark = highest number, checkpoint counts down to #1</li>
 *   <li><b>Incremental sync handles new items</b>: Items created during backfill are caught by regular sync</li>
 * </ul>
 *
 * <h2>State Tracking</h2>
 * <ul>
 *   <li>{@code backfillHighWaterMark} - Highest issue/PR number (set on first batch)</li>
 *   <li>{@code backfillCheckpoint} - Lowest number synced so far (counts down to 1, 0 = complete)</li>
 *   <li>{@code backfillLastRunAt} - Timestamp for cooldown tracking</li>
 *   <li>{@code issueSyncCursor} / {@code pullRequestSyncCursor} - GraphQL pagination cursors</li>
 * </ul>
 *
 * <h2>Rate Limiting</h2>
 * <p>Backfill is opportunistic and respects rate limits:
 * <ul>
 *   <li>Pauses when remaining API points drop below {@code backfill.rateLimitThreshold}</li>
 *   <li>Processes one batch per repository per cycle</li>
 *   <li>Cycles run frequently (default: every 60 seconds) - rate limit is the throttle</li>
 * </ul>
 *
 * @see SyncSchedulerProperties.BackfillProperties
 */
@Slf4j
@Service
public class GitHubHistoricalBackfillService {

    /** GraphQL query document name for historical issue sync (CREATED_AT DESC). */
    private static final String ISSUES_HISTORICAL_QUERY = "GetRepositoryIssuesHistorical";

    /** GraphQL query document name for historical PR sync (CREATED_AT DESC). */
    private static final String PRS_HISTORICAL_QUERY = "GetRepositoryPullRequestsHistorical";

    /**
     * Cooldown period for repositories after 5xx errors.
     * When a repository experiences 502/504 errors, skip it for this duration
     * to avoid hammering a failing endpoint and allow GitHub to recover.
     */
    private static final Duration COOLDOWN_AFTER_5XX_ERROR = Duration.ofMinutes(5);

    /**
     * Maximum consecutive failures before entering extended cooldown.
     * After this many failures, the repository enters a longer cooldown period.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * Extended cooldown period after max consecutive failures.
     */
    private static final Duration EXTENDED_COOLDOWN = Duration.ofMinutes(15);

    /**
     * Tracks repository cooldown state to prevent hammering failing endpoints.
     * Key: syncTargetId, Value: Instant when cooldown expires
     */
    private final Map<Long, Instant> repositoryCooldowns = new ConcurrentHashMap<>();

    /**
     * Tracks consecutive failure count per repository for escalating cooldowns.
     * Key: syncTargetId, Value: consecutive failure count
     */
    private final Map<Long, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    private final SyncTargetProvider syncTargetProvider;
    private final BackfillStateProvider backfillStateProvider;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final GitHubSyncProperties syncProperties;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubIssueProcessor issueProcessor;
    private final GitHubIssueCommentProcessor issueCommentProcessor;
    private final GitHubPullRequestProcessor prProcessor;
    private final GitHubPullRequestReviewProcessor reviewProcessor;
    private final GitHubPullRequestReviewSyncService reviewSyncService;
    private final GitHubPullRequestReviewCommentSyncService reviewCommentSyncService;
    private final RepositoryRepository repositoryRepository;
    private final TransactionTemplate transactionTemplate;
    private final Executor monitoringExecutor;

    public GitHubHistoricalBackfillService(
            SyncTargetProvider syncTargetProvider,
            BackfillStateProvider backfillStateProvider,
            SyncSchedulerProperties syncSchedulerProperties,
            GitHubSyncProperties syncProperties,
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubIssueProcessor issueProcessor,
            GitHubIssueCommentProcessor issueCommentProcessor,
            GitHubPullRequestProcessor prProcessor,
            GitHubPullRequestReviewProcessor reviewProcessor,
            GitHubPullRequestReviewSyncService reviewSyncService,
            GitHubPullRequestReviewCommentSyncService reviewCommentSyncService,
            RepositoryRepository repositoryRepository,
            TransactionTemplate transactionTemplate,
            @Qualifier("monitoringExecutor") Executor monitoringExecutor) {
        this.syncTargetProvider = syncTargetProvider;
        this.backfillStateProvider = backfillStateProvider;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.syncProperties = syncProperties;
        this.graphQlClientProvider = graphQlClientProvider;
        this.issueProcessor = issueProcessor;
        this.issueCommentProcessor = issueCommentProcessor;
        this.prProcessor = prProcessor;
        this.reviewProcessor = reviewProcessor;
        this.reviewSyncService = reviewSyncService;
        this.reviewCommentSyncService = reviewCommentSyncService;
        this.repositoryRepository = repositoryRepository;
        this.transactionTemplate = transactionTemplate;
        this.monitoringExecutor = monitoringExecutor;
    }

    /**
     * Checks if backfill is enabled in configuration.
     *
     * @return true if backfill is enabled
     */
    public boolean isEnabled() {
        return syncSchedulerProperties.backfill().enabled();
    }

    /**
     * Runs a single backfill cycle across all scopes and repositories.
     *
     * <p>This method processes repositories that:
     * <ul>
     *   <li>Have backfill not yet complete (cursor still exists or not initialized)</li>
     *   <li>Have sufficient API rate limit remaining</li>
     * </ul>
     *
     * <p>Rate limit is the primary throttle. When remaining points drop below threshold,
     * backfill pauses until the next cycle.
     *
     * @return result containing processed count and pending repository information
     */
    public BackfillCycleResult runBackfillCycle() {
        if (!isEnabled()) {
            log.trace("Historical backfill is disabled");
            return BackfillCycleResult.nothingToDo();
        }

        SyncSchedulerProperties.BackfillProperties backfillProps = syncSchedulerProperties.backfill();

        List<SyncSession> sessions = syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB);
        if (sessions.isEmpty()) {
            log.trace("No scopes available for backfill");
            return BackfillCycleResult.nothingToDo();
        }

        // Process all workspaces in parallel - each has its own GitHub App installation
        // with separate rate limits, so there's no reason to backfill sequentially.
        // Uses virtual threads (monitoringExecutor) for efficient I/O-bound operations.
        //
        // Each future handles its own exceptions via whenComplete() so that:
        // 1. One workspace failure doesn't prevent other workspaces from completing
        // 2. All exceptions are logged with proper context
        //
        // No global timeout: large repositories combined with rate limits can legitimately
        // take many hours. Individual GraphQL calls have their own timeouts for transient
        // failures. The backfill scheduler runs periodically and join() respects thread
        // interruption for JVM shutdown.
        AtomicInteger repositoriesProcessed = new AtomicInteger(0);
        AtomicInteger pendingRepositories = new AtomicInteger(0);

        CompletableFuture<?>[] futures = sessions.stream()
                .map(session -> CompletableFuture.runAsync(
                                () -> backfillSession(
                                        session, backfillProps, repositoriesProcessed, pendingRepositories),
                                monitoringExecutor)
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                log.error(
                                        "Backfill session failed: scopeId={}, scopeSlug={}, error={}",
                                        session.scopeId(),
                                        session.slug(),
                                        error.getMessage());
                            }
                        }))
                .toArray(CompletableFuture[]::new);

        // Wait for all workspace backfills to complete
        // Use get() instead of join() because get() throws InterruptedException,
        // allowing graceful shutdown when Ctrl+C is pressed.
        try {
            CompletableFuture.allOf(futures).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "Backfill cycle interrupted (shutdown requested): processed={}, pending={}",
                    repositoriesProcessed.get(),
                    pendingRepositories.get());
            return new BackfillCycleResult(repositoriesProcessed.get(), pendingRepositories.get(), "interrupted");
        } catch (ExecutionException e) {
            // Should not happen since each future handles its own exceptions via whenComplete()
            log.error("Unexpected error during backfill cycle", e);
        }

        int processed = repositoriesProcessed.get();
        int pending = pendingRepositories.get();

        if (processed > 0) {
            log.info("Completed backfill cycle: repositoriesProcessed={}, pending={}", processed, pending);
        }
        return new BackfillCycleResult(processed, pending, pending > 0 ? "various" : null);
    }

    /**
     * Result of a backfill cycle execution.
     *
     * @param repositoriesProcessed number of repositories that had backfill work performed
     * @param pendingRepositories   number of repositories that need backfill but were skipped
     * @param skipReason            why pending repositories were skipped (null if none pending)
     */
    public record BackfillCycleResult(
            int repositoriesProcessed,
            int pendingRepositories,
            @Nullable String skipReason) {
        /** Creates a result indicating no backfill work is needed. */
        public static BackfillCycleResult nothingToDo() {
            return new BackfillCycleResult(0, 0, null);
        }
    }

    /**
     * Backfills repositories for a single session (workspace).
     * This method is called in parallel for each session.
     *
     * @param session               the sync session to process
     * @param backfillProps         backfill configuration properties
     * @param repositoriesProcessed atomic counter for processed repositories
     * @param pendingRepositories   atomic counter for pending repositories
     */
    private void backfillSession(
            SyncSession session,
            SyncSchedulerProperties.BackfillProperties backfillProps,
            AtomicInteger repositoriesProcessed,
            AtomicInteger pendingRepositories) {
        Long scopeId = session.scopeId();
        List<SyncTarget> targets = session.syncTargets();

        // Log incremental sync progress for visibility. Repos that haven't completed
        // their own incremental sync are skipped individually (per-repo gate below).
        long pendingIncrementalSync = targets.stream()
                .filter(t -> t.lastIssuesSyncedAt() == null || t.lastPullRequestsSyncedAt() == null)
                .count();

        if (pendingIncrementalSync > 0) {
            long completed = targets.size() - pendingIncrementalSync;
            log.debug(
                    "Some repos pending incremental sync (backfill proceeds for completed repos): "
                            + "scopeId={}, scopeSlug={}, incrementalComplete={}, incrementalPending={}",
                    scopeId,
                    session.slug(),
                    completed,
                    pendingIncrementalSync);
        }

        int remainingPoints = graphQlClientProvider.getRateLimitRemaining(scopeId);
        if (remainingPoints < backfillProps.rateLimitThreshold()) {
            log.debug(
                    "Skipping backfill for scope: reason=rateLimitLow, scopeId={}, remaining={}, threshold={}, resetsAt={}",
                    scopeId,
                    remainingPoints,
                    backfillProps.rateLimitThreshold(),
                    graphQlClientProvider.getRateLimitResetAt(scopeId));
            for (SyncTarget target : targets) {
                if (!target.isBackfillComplete()) {
                    pendingRepositories.incrementAndGet();
                }
            }
            return;
        }

        for (SyncTarget target : targets) {
            if (target.isBackfillComplete()) {
                continue;
            }

            // Skip repos that haven't completed their own incremental sync yet.
            // Backfill should only run for repos that have their baseline data.
            if (target.lastIssuesSyncedAt() == null || target.lastPullRequestsSyncedAt() == null) {
                log.trace(
                        "Skipping backfill: reason=incrementalSyncPending, repo={}",
                        sanitizeForLog(target.repositoryNameWithOwner()));
                pendingRepositories.incrementAndGet();
                continue;
            }

            if (isInCooldown(target.id())) {
                log.trace(
                        "Skipping backfill: reason=inCooldown, repo={}, cooldownExpiresAt={}",
                        sanitizeForLog(target.repositoryNameWithOwner()),
                        repositoryCooldowns.get(target.id()));
                pendingRepositories.incrementAndGet();
                continue;
            }

            remainingPoints = graphQlClientProvider.getRateLimitRemaining(scopeId);
            if (remainingPoints < backfillProps.rateLimitThreshold()) {
                log.info(
                        "Stopping backfill for scope: reason=rateLimitLow, scopeId={}, remaining={}, processed={}",
                        scopeId,
                        remainingPoints,
                        repositoriesProcessed.get());
                pendingRepositories.incrementAndGet();
                break;
            }

            try {
                // The scheduled cycle has no job handle to report to — nobody is watching a bar for it.
                boolean didWork = backfillRepository(target, backfillProps.batchSize(), BackfillPageObserver.NOOP);
                if (didWork) {
                    repositoriesProcessed.incrementAndGet();
                    clearFailureState(target.id());
                }
            } catch (Exception e) {
                handleBackfillFailure(target, e);
                pendingRepositories.incrementAndGet();
            }
        }
    }

    /**
     * Performs a backfill batch for a single repository using CREATED_AT DESC ordering.
     * Syncs from highest issue/PR number down to #1.
     *
     * @param target    the sync target to backfill
     * @param batchSize maximum pages to process in this batch
     * @param observer  notified after every vendor page, so a driving runner can report progress at the
     *                  grain the work happens at rather than once per whole batch
     * @return true if any work was performed
     */
    boolean backfillRepository(SyncTarget target, int batchSize, BackfillPageObserver observer) {
        String safeRepoName = Objects.requireNonNull(sanitizeForLog(target.repositoryNameWithOwner()));
        Long syncTargetId = target.id();
        Long scopeId = target.scopeId();

        Optional<RepositoryOwnerAndName> parsedName =
                GitHubRepositoryNameParser.parse(target.repositoryNameWithOwner());
        if (parsedName.isEmpty()) {
            log.warn("Skipping backfill: reason=invalidRepoName, repo={}", safeRepoName);
            return false;
        }
        RepositoryOwnerAndName ownerAndName = parsedName.get();

        // Find the repository in our database - we only need the ID here.
        // The actual entity will be fetched fresh inside each transaction to avoid
        // LazyInitializationException when accessing lazy associations like organization.
        Optional<Repository> repoOpt = repositoryRepository.findByNameWithOwner(target.repositoryNameWithOwner());
        if (repoOpt.isEmpty()) {
            log.debug("Skipping backfill: reason=repositoryNotInDb, repo={}", safeRepoName);
            return false;
        }
        Long repositoryId = repoOpt.get().getId();

        boolean isIssueFirstBatch = !target.isIssueBackfillInitialized();
        boolean isPullRequestFirstBatch = !target.isPullRequestBackfillInitialized();
        boolean isFirstBatch = isIssueFirstBatch && isPullRequestFirstBatch;

        if (isFirstBatch) {
            log.info(
                    "Starting fresh backfill: repo={}, pullRequestPageSize={}, timeout={}s",
                    safeRepoName,
                    syncProperties.backfillPrPageSize(),
                    syncProperties.backfillGraphqlTimeout().toSeconds());
        } else {
            log.info(
                    "Resuming backfill: repo={}, issueCheckpoint=#{}, issueHighWaterMark=#{}, "
                            + "pullRequestCheckpoint=#{}, pullRequestHighWaterMark=#{}, pullRequestPageSize={}, timeout={}s",
                    safeRepoName,
                    target.issueBackfillCheckpoint(),
                    target.issueBackfillHighWaterMark(),
                    target.pullRequestBackfillCheckpoint(),
                    target.pullRequestBackfillHighWaterMark(),
                    syncProperties.backfillPrPageSize(),
                    syncProperties.backfillGraphqlTimeout().toSeconds());
        }

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
        // Use longer timeout for backfill - responses include embedded reviews/threads/comments
        Duration timeout = syncProperties.backfillGraphqlTimeout();

        int totalIssuesSynced = 0;
        int totalPRsSynced = 0;

        // Backfill issues using CREATED_AT DESC (newest first) - pass repositoryId, not the entity
        BackfillBatchResult issueResult = backfillIssues(
                client,
                timeout,
                ownerAndName,
                scopeId,
                repositoryId,
                target.repositoryNameWithOwner(),
                syncTargetId,
                target.issueSyncCursor(),
                batchSize,
                observer);
        totalIssuesSynced = issueResult.itemsSynced();

        // Persist issue backfill progress after each batch, for admin visibility.
        if (issueResult.itemsSynced() > 0) {
            Integer issueCheckpoint = issueResult.minNumber() > 0 ? issueResult.minNumber() : null;
            Integer issueHighWaterMark =
                    isIssueFirstBatch && issueResult.maxNumber() > 0 ? issueResult.maxNumber() : null;

            if (!issueResult.hasMore()) {
                issueCheckpoint = 0;
            }

            backfillStateProvider.updateIssueBackfillState(
                    syncTargetId, issueHighWaterMark, issueCheckpoint, Instant.now());

            if (!issueResult.hasMore()) {
                log.info(
                        "Issue backfill complete: repo={}, issuesSynced={}, starting pull requests",
                        safeRepoName,
                        totalIssuesSynced);
            }
        } else if (!issueResult.hasMore() && isIssueFirstBatch) {
            // No issues found at all - mark issue backfill as complete with 0
            backfillStateProvider.updateIssueBackfillState(syncTargetId, 0, 0, Instant.now());
            log.info("No issues to backfill: repo={}", safeRepoName);
        }

        // Backfill PRs using CREATED_AT DESC (newest first) - pass repositoryId, not the entity
        BackfillBatchResult prResult = backfillPullRequests(
                client,
                timeout,
                ownerAndName,
                scopeId,
                repositoryId,
                target.repositoryNameWithOwner(),
                syncTargetId,
                target.pullRequestSyncCursor(),
                batchSize,
                observer);
        totalPRsSynced = prResult.itemsSynced();

        // Persist pull request backfill progress after each batch, for admin visibility.
        if (prResult.itemsSynced() > 0) {
            Integer prCheckpoint = prResult.minNumber() > 0 ? prResult.minNumber() : null;
            Integer prHighWaterMark = isPullRequestFirstBatch && prResult.maxNumber() > 0 ? prResult.maxNumber() : null;

            if (!prResult.hasMore()) {
                prCheckpoint = 0;
            }

            backfillStateProvider.updatePullRequestBackfillState(
                    syncTargetId, prHighWaterMark, prCheckpoint, Instant.now());

            if (!prResult.hasMore()) {
                log.info(
                        "Pull request backfill complete: repo={}, pullRequestsSynced={}", safeRepoName, totalPRsSynced);
            }
        } else if (!prResult.hasMore() && isPullRequestFirstBatch) {
            // No PRs found at all - mark PR backfill as complete with 0
            backfillStateProvider.updatePullRequestBackfillState(syncTargetId, 0, 0, Instant.now());
            log.info("No pull requests to backfill: repo={}", safeRepoName);
        }

        boolean issuesComplete = !issueResult.hasMore() || target.isIssueBackfillComplete();
        boolean pullRequestsComplete = !prResult.hasMore();

        if (issuesComplete && pullRequestsComplete) {
            log.info(
                    "Backfill complete: repo={}, totalIssues={}, totalPullRequests={}",
                    safeRepoName,
                    totalIssuesSynced,
                    totalPRsSynced);
        } else {
            // Log progress (sync goes newest to oldest by CREATED_AT DESC)
            String issueProgress = issueResult.itemsSynced() > 0
                    ? String.format("issues=#%d..#%d", issueResult.minNumber(), issueResult.maxNumber())
                    : "issues=0";
            String prProgress = prResult.itemsSynced() > 0
                    ? String.format("pullRequests=#%d..#%d", prResult.minNumber(), prResult.maxNumber())
                    : "pullRequests=0";
            log.info(
                    "Backfill batch: repo={}, {}, {}, issuesComplete={}, pullRequestsComplete={}",
                    safeRepoName,
                    issueProgress,
                    prProgress,
                    issuesComplete,
                    pullRequestsComplete);
        }

        return totalIssuesSynced > 0 || totalPRsSynced > 0;
    }

    /**
     * Backfills issues using CREATED_AT DESC ordering (newest first).
     * <p>
     * Sync goes from highest issue number down to #1.
     * This ensures the most relevant/recent issues are synced first.
     *
     * @param client         the GraphQL client
     * @param timeout        timeout for GraphQL requests
     * @param ownerAndName   parsed repository owner and name
     * @param scopeId        the scope ID for rate limit tracking
     * @param repositoryId   the repository ID (passed to transaction for fresh fetch)
     * @param repoNameForLog repository name for logging (avoids accessing detached entity)
     * @param syncTargetId   the sync target ID for cursor persistence
     * @param cursor         the starting cursor (null for first page)
     * @param maxPages       maximum pages to process in this batch
     * @param observer       notified after each page lands, so progress moves during the batch
     * @return result containing items synced and whether more pages exist
     */
    private BackfillBatchResult backfillIssues(
            HttpGraphQlClient client,
            Duration timeout,
            RepositoryOwnerAndName ownerAndName,
            Long scopeId,
            Long repositoryId,
            String repoNameForLog,
            Long syncTargetId,
            @Nullable String startCursor,
            int maxPages,
            BackfillPageObserver observer) {
        String cursor = startCursor;
        int totalIssuesSynced = 0;
        int totalCommentsSynced = 0;
        int batchMinNumber = Integer.MAX_VALUE;
        int batchMaxNumber = Integer.MIN_VALUE;
        boolean hasMore = true;
        int pageCount = 0;
        int reportedTotalCount = -1;

        while (hasMore && pageCount < maxPages) {
            pageCount++;

            try {
                // Wrap the entire execute() call in Mono.defer() so retries cover body streaming, not
                // just the HTTP exchange: WebClient's ExchangeFilterFunction retries only cover the
                // exchange, and PrematureCloseException occurs during body consumption.
                final String currentCursor = cursor;
                ClientGraphQlResponse response = Mono.defer(() -> client.documentName(ISSUES_HISTORICAL_QUERY)
                                .variable("owner", ownerAndName.owner())
                                .variable("name", ownerAndName.name())
                                .variable(
                                        "first",
                                        adaptPageSize(
                                                DEFAULT_PAGE_SIZE,
                                                graphQlClientProvider.getRateLimitRemaining(scopeId)))
                                .variable("after", currentCursor)
                                .execute())
                        .retryWhen(createTransportRetrySpec("issues backfill", repoNameForLog, pageCount))
                        .block(timeout);

                if (response == null) {
                    log.warn("Null GraphQL response for issues backfill: repo={}", sanitizeForLog(repoNameForLog));
                    break;
                }

                // Check for transient errors (timeouts, rate limits, server errors)
                // These come back as HTTP 200 with error in the response body
                GitHubGraphQlErrorUtils.TransientError transientError =
                        GitHubGraphQlErrorUtils.detectTransientError(response);
                if (transientError != null) {
                    log.warn(
                            "Detected transient GraphQL error for issues backfill: repo={}, type={}, message={}",
                            sanitizeForLog(repoNameForLog),
                            transientError.type(),
                            transientError.message());
                    // Throw to trigger cooldown mechanism
                    throw new BackfillTransientException(
                            "Transient error during issues backfill: " + transientError.message(), null);
                }

                if (!response.isValid()) {
                    log.warn(
                            "Invalid GraphQL response for issues backfill: repo={}, errors={}",
                            sanitizeForLog(repoNameForLog),
                            response.getErrors());
                    break;
                }

                graphQlClientProvider.trackRateLimit(scopeId, response);

                GHIssueConnection connection =
                        response.field("repository.issues").toEntity(GHIssueConnection.class);
                if (connection == null
                        || connection.getNodes() == null
                        || connection.getNodes().isEmpty()) {
                    hasMore = false;
                    break;
                }
                if (reportedTotalCount < 0) {
                    reportedTotalCount = connection.getTotalCount();
                }

                GHPageInfo pageInfo = connection.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                String nextCursor = pageInfo != null ? pageInfo.getEndCursor() : null;

                // GitHub has a known bug where hasNextPage returns false prematurely
                // around ~500 nodes (community discussion #30687). When totalCount
                // indicates more data exists and we have a valid cursor, force-continue.
                // Safety: if GitHub truly has no more data, the next page returns empty
                // nodes and the existing isEmpty() check above breaks out cleanly.
                if (!hasMore
                        && nextCursor != null
                        && reportedTotalCount > 0
                        && totalIssuesSynced < reportedTotalCount) {
                    log.info(
                            "Forcing pagination past hasNextPage=false (GitHub GraphQL ~500-node bug): fetched={}, totalCount={}, repo={}, entity=issues",
                            totalIssuesSynced,
                            reportedTotalCount,
                            sanitizeForLog(repoNameForLog));
                    hasMore = true;
                }

                // Process issues AND their embedded comments, persist cursor in the SAME transaction for atomicity.
                // This ensures that if processing succeeds but cursor save fails (or vice versa),
                // both are rolled back together, preventing duplicate processing on restart.
                // Pass repositoryId, not the entity, to avoid LazyInitializationException.
                IssuePageResult pageResult =
                        processIssuesPage(connection, scopeId, repositoryId, syncTargetId, hasMore ? nextCursor : null);
                totalIssuesSynced += pageResult.issueCount();
                totalCommentsSynced += pageResult.commentCount();
                // Track min/max across pages (sync goes newest to oldest by CREATED_AT DESC)
                if (pageResult.issueCount() > 0) {
                    batchMinNumber = Math.min(batchMinNumber, pageResult.minNumber());
                    batchMaxNumber = Math.max(batchMaxNumber, pageResult.maxNumber());
                }

                // Report the page. This is the whole point of threading the observer down here: a batch
                // is up to maxPages of this loop, so reporting only on return leaves a watcher staring
                // at an unchanged bar for the minutes it takes. batchMinNumber is the live checkpoint —
                // the value that will be persisted when the batch ends — so the runner can compute a
                // real percentage now instead of after the write.
                notifyPage(observer, syncTargetId, repoNameForLog, SyncPhase.ISSUES, batchMinNumber, totalIssuesSynced);

                cursor = nextCursor;

                // Throttle between pages to avoid overwhelming the GitHub API
                if (hasMore && !syncProperties.paginationThrottle().isZero()) {
                    try {
                        Thread.sleep(syncProperties.paginationThrottle().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                // Log at WARN level since transient errors are expected during large syncs.
                // Rethrow to trigger cooldown mechanism in runBackfillCycle.
                // Progress is preserved: cursor was saved in the last successful transaction.
                log.warn(
                        "Error during issues backfill, will retry after cooldown: repo={}, page={}, synced={}, error={}",
                        sanitizeForLog(repoNameForLog),
                        pageCount,
                        totalIssuesSynced,
                        e.getMessage());
                throw new BackfillTransientException(
                        "Issues backfill failed for " + repoNameForLog + " on page " + pageCount, e);
            }
        }

        // No completeness check: backfill paginates one bounded batch per cycle and resumes from a
        // saved cursor across cycles, so this batch's count vs the whole-connection totalCount is
        // meaningless. Real truncation surfaces via the force-pagination/completion logs.

        // Note: cursor is cleared inside the transaction when hasMore is false
        // (null cursor is passed to processIssuesPage when !hasMore)

        // Normalize min/max if no items were processed
        if (totalIssuesSynced == 0) {
            batchMinNumber = 0;
            batchMaxNumber = 0;
        }

        if (totalIssuesSynced > 0) {
            log.debug(
                    "Backfill issues batch complete: repo={}, issues={}, comments={}, numberRange=[#{}-#{}]",
                    sanitizeForLog(repoNameForLog),
                    totalIssuesSynced,
                    totalCommentsSynced,
                    batchMinNumber,
                    batchMaxNumber);
        }

        return new BackfillBatchResult(totalIssuesSynced, totalCommentsSynced, hasMore, batchMinNumber, batchMaxNumber);
    }

    /**
     * Backfills pull requests using CREATED_AT DESC ordering (newest first).
     * <p>
     * Sync goes from highest PR number down to #1.
     * This ensures the most relevant/recent PRs are synced first.
     *
     * @param client         the GraphQL client
     * @param timeout        timeout for GraphQL requests
     * @param ownerAndName   parsed repository owner and name
     * @param scopeId        the scope ID for rate limit tracking
     * @param repositoryId   the repository ID (passed to transaction for fresh fetch)
     * @param repoNameForLog repository name for logging (avoids accessing detached entity)
     * @param syncTargetId   the sync target ID for cursor persistence
     * @param cursor         the starting cursor (null for first page)
     * @param maxPages       maximum pages to process in this batch
     * @param observer       notified after each page lands, so progress moves during the batch
     * @return result containing items synced and whether more pages exist
     */
    private BackfillBatchResult backfillPullRequests(
            HttpGraphQlClient client,
            Duration timeout,
            RepositoryOwnerAndName ownerAndName,
            Long scopeId,
            Long repositoryId,
            String repoNameForLog,
            Long syncTargetId,
            @Nullable String startCursor,
            int maxPages,
            BackfillPageObserver observer) {
        String cursor = startCursor;
        int totalPRsSynced = 0;
        int totalReviewsSynced = 0;
        int totalReviewCommentsSynced = 0;
        int totalCommentsSynced = 0;
        int batchMinNumber = Integer.MAX_VALUE;
        int batchMaxNumber = Integer.MIN_VALUE;
        boolean hasMore = true;
        int pageCount = 0;
        int reportedTotalCount = -1;
        List<PullRequestWithReviewCursor> allPrsNeedingReviewPagination = new ArrayList<>();

        while (hasMore && pageCount < maxPages) {
            pageCount++;

            try {
                // Use Mono.defer() to wrap the entire execute() call so retries cover body streaming.
                final String currentCursor = cursor;
                final int prPageSize = resolveBackfillPrPageSize(scopeId, syncTargetId);
                ClientGraphQlResponse response = Mono.defer(() -> client.documentName(PRS_HISTORICAL_QUERY)
                                .variable("owner", ownerAndName.owner())
                                .variable("name", ownerAndName.name())
                                .variable("first", prPageSize)
                                .variable("after", currentCursor)
                                .execute())
                        .retryWhen(createTransportRetrySpec("pull requests backfill", repoNameForLog, pageCount))
                        .block(timeout);

                if (response == null) {
                    log.warn(
                            "Null GraphQL response for pull requests backfill: repo={}",
                            sanitizeForLog(repoNameForLog));
                    break;
                }

                // Check for transient errors (timeouts, rate limits, server errors)
                // These come back as HTTP 200 with error in the response body
                GitHubGraphQlErrorUtils.TransientError transientError =
                        GitHubGraphQlErrorUtils.detectTransientError(response);
                if (transientError != null) {
                    log.warn(
                            "Detected transient GraphQL error for pull requests backfill: repo={}, type={}, message={}",
                            sanitizeForLog(repoNameForLog),
                            transientError.type(),
                            transientError.message());
                    // Throw to trigger cooldown mechanism
                    throw new BackfillTransientException(
                            "Transient error during pull requests backfill: " + transientError.message(), null);
                }

                if (!response.isValid()) {
                    log.warn(
                            "Invalid GraphQL response for pull requests backfill: repo={}, errors={}",
                            sanitizeForLog(repoNameForLog),
                            response.getErrors());
                    break;
                }

                graphQlClientProvider.trackRateLimit(scopeId, response);

                GHPullRequestConnection connection =
                        response.field("repository.pullRequests").toEntity(GHPullRequestConnection.class);
                if (connection == null
                        || connection.getNodes() == null
                        || connection.getNodes().isEmpty()) {
                    hasMore = false;
                    break;
                }
                if (reportedTotalCount < 0) {
                    reportedTotalCount = connection.getTotalCount();
                }

                GHPageInfo pageInfo = connection.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                String nextCursor = pageInfo != null ? pageInfo.getEndCursor() : null;

                // GitHub has a known bug where hasNextPage returns false prematurely
                // around ~500 nodes (community discussion #30687). When totalCount
                // indicates more data exists and we have a valid cursor, force-continue.
                // Safety: if GitHub truly has no more data, the next page returns empty
                // nodes and the existing isEmpty() check above breaks out cleanly.
                if (!hasMore && nextCursor != null && reportedTotalCount > 0 && totalPRsSynced < reportedTotalCount) {
                    log.info(
                            "Forcing pagination past hasNextPage=false (GitHub GraphQL ~500-node bug): fetched={}, totalCount={}, repo={}, entity=pullRequests",
                            totalPRsSynced,
                            reportedTotalCount,
                            sanitizeForLog(repoNameForLog));
                    hasMore = true;
                }

                // Process PRs AND their embedded reviews/comments, persist cursor in the SAME transaction for
                // atomicity.
                // This ensures that if processing succeeds but cursor save fails (or vice versa),
                // both are rolled back together, preventing duplicate processing on restart.
                // Pass repositoryId, not the entity, to avoid LazyInitializationException.
                PullRequestPageResult pageResult = processPullRequestsPage(
                        connection, scopeId, repositoryId, syncTargetId, hasMore ? nextCursor : null);
                totalPRsSynced += pageResult.prCount();
                totalReviewsSynced += pageResult.reviewCount();
                totalReviewCommentsSynced += pageResult.reviewCommentCount();
                totalCommentsSynced += pageResult.commentCount();
                // Track min/max across pages (sync goes newest to oldest by CREATED_AT DESC)
                if (pageResult.prCount() > 0) {
                    batchMinNumber = Math.min(batchMinNumber, pageResult.minNumber());
                    batchMaxNumber = Math.max(batchMaxNumber, pageResult.maxNumber());
                }

                // Collect PRs that need additional review pagination (more than 50 reviews)
                allPrsNeedingReviewPagination.addAll(pageResult.prsNeedingReviewPagination());

                // See backfillIssues: report every page, not once per batch.
                notifyPage(
                        observer,
                        syncTargetId,
                        repoNameForLog,
                        SyncPhase.PULL_REQUESTS,
                        batchMinNumber,
                        totalPRsSynced);

                cursor = nextCursor;

                // Throttle between pages to avoid overwhelming the GitHub API
                if (hasMore && !syncProperties.paginationThrottle().isZero()) {
                    try {
                        Thread.sleep(syncProperties.paginationThrottle().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                // Log at WARN level since transient errors are expected during large syncs.
                // Rethrow to trigger cooldown mechanism in runBackfillCycle.
                // Progress is preserved: cursor was saved in the last successful transaction.
                log.warn(
                        "Error during pull requests backfill, will retry after cooldown: repo={}, page={}, synced={}, failures={}, error={}",
                        sanitizeForLog(repoNameForLog),
                        pageCount,
                        totalPRsSynced,
                        consecutiveFailures.getOrDefault(syncTargetId, 0),
                        e.getMessage());
                throw new BackfillTransientException(
                        "pull requests backfill failed for " + repoNameForLog + " on page " + pageCount, e);
            }
        }

        // No completeness check: see the issues batch above — per-batch count vs whole-connection
        // totalCount is meaningless for a resumable bounded backfill.

        // Note: cursor is cleared inside the transaction when hasMore is false
        // (null cursor is passed to processPullRequestsPage when !hasMore)

        // Fetch remaining reviews for PRs with >50 reviews (using cursor for efficient continuation)
        // This happens OUTSIDE the transaction to avoid long-running transactions during API calls
        if (!allPrsNeedingReviewPagination.isEmpty()) {
            log.debug(
                    "Fetching additional reviews for PRs with pagination: repo={}, prCount={}",
                    sanitizeForLog(repoNameForLog),
                    allPrsNeedingReviewPagination.size());
            int processedPrs = 0;
            for (PullRequestWithReviewCursor prWithCursor : allPrsNeedingReviewPagination) {
                // Check for interrupt (e.g., during application shutdown)
                if (Thread.interrupted()) {
                    log.info(
                            "Review pagination interrupted (shutdown requested): repo={}, processed={}/{}, preserving interrupt status",
                            sanitizeForLog(repoNameForLog),
                            processedPrs,
                            allPrsNeedingReviewPagination.size());
                    Thread.currentThread().interrupt();
                    break;
                }
                processedPrs++;
                try {
                    int additionalReviews = reviewSyncService.syncRemainingReviews(
                            scopeId,
                            prWithCursor.pullRequest(),
                            prWithCursor.reviewCursor(),
                            prWithCursor.inlineCount());
                    totalReviewsSynced += additionalReviews;
                    if (additionalReviews > 0) {
                        log.debug(
                                "Fetched additional reviews: prNumber={}, additionalReviews={}",
                                prWithCursor.pullRequest().getNumber(),
                                additionalReviews);
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed to fetch additional reviews for PR #{}: {}",
                            prWithCursor.pullRequest().getNumber(),
                            e.getMessage());
                }
            }
        }

        // Normalize min/max if no items were processed
        if (totalPRsSynced == 0) {
            batchMinNumber = 0;
            batchMaxNumber = 0;
        }

        if (totalPRsSynced > 0) {
            log.debug(
                    "Backfill PRs batch complete: repo={}, prs={}, reviews={}, reviewComments={}, comments={}, numberRange=[#{}..#{}], prsWithReviewPagination={}",
                    sanitizeForLog(repoNameForLog),
                    totalPRsSynced,
                    totalReviewsSynced,
                    totalReviewCommentsSynced,
                    totalCommentsSynced,
                    batchMinNumber,
                    batchMaxNumber,
                    allPrsNeedingReviewPagination.size());
        }

        return new BackfillBatchResult(
                totalPRsSynced,
                totalReviewsSynced + totalReviewCommentsSynced + totalCommentsSynced,
                hasMore,
                batchMinNumber,
                batchMaxNumber);
    }

    /**
     * Processes a page of issues and their embedded comments within a transaction.
     * <p>
     * Accepts repositoryId rather than a Repository entity to avoid LazyInitializationException: the
     * Repository is fetched inside the transaction with its organization eagerly loaded, so it stays
     * attached to the persistence context when the processor accesses lazy associations.
     * <p>
     * Cursor persistence is also done inside this transaction to ensure atomicity.
     * If data processing succeeds but cursor update fails (or vice versa), both are rolled back.
     * This prevents duplicate processing on restart.
     *
     * @param connection   the GraphQL connection with issue nodes
     * @param scopeId      the scope ID for processing context
     * @param repositoryId the repository ID (NOT the entity, to avoid detached entity issues)
     * @param syncTargetId the sync target ID for cursor persistence
     * @param nextCursor   the cursor to persist (null to clear cursor when page is complete)
     * @return result containing counts of issues and comments processed
     */
    private IssuePageResult processIssuesPage(
            GHIssueConnection connection,
            Long scopeId,
            Long repositoryId,
            Long syncTargetId,
            @Nullable String nextCursor) {
        IssuePageResult result = transactionTemplate.execute(status -> {
            // Fetch repository INSIDE the transaction with organization eagerly loaded.
            // This ensures the entity is attached and lazy associations can be accessed.
            Repository repository = repositoryRepository
                    .findByIdWithOrganization(repositoryId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Repository not found during backfill processing: id=" + repositoryId));

            ProcessingContext context = ProcessingContext.forSync(scopeId, repository);
            int issueCount = 0;
            int commentCount = 0;
            int minNumber = Integer.MAX_VALUE;
            int maxNumber = Integer.MIN_VALUE;

            for (var node : connection.getNodes()) {
                IssueWithComments issueData = IssueWithComments.fromIssue(node);
                if (issueData != null && issueData.issue() != null) {
                    Issue processed = issueProcessor.process(issueData.issue(), context);
                    if (processed != null) {
                        issueCount++;
                        // Track min/max for progress visibility
                        int number = processed.getNumber();
                        minNumber = Math.min(minNumber, number);
                        maxNumber = Math.max(maxNumber, number);

                        EmbeddedCommentsDTO embeddedComments = issueData.embeddedComments();
                        for (var commentDto : embeddedComments.comments()) {
                            if (issueCommentProcessor.process(commentDto, processed.getNumber(), context) != null) {
                                commentCount++;
                            }
                        }

                        // Note: Historical backfill fetches first 10 comments per issue.
                        // If an issue has more than 10 comments (needsPagination() returns true),
                        // those additional comments will be synced during the regular incremental
                        // sync which runs more frequently. This is an acceptable tradeoff to keep
                        // backfill simple and avoid additional API calls during historical sync.
                    }
                }
            }

            // Persist cursor INSIDE the same transaction as data processing.
            // This ensures atomicity: either both data AND cursor are saved, or neither.
            backfillStateProvider.updateSyncCursor(syncTargetId, SyncCursorKind.ISSUE, nextCursor);

            // Normalize min/max if no items were processed
            if (issueCount == 0) {
                minNumber = 0;
                maxNumber = 0;
            }

            return new IssuePageResult(issueCount, commentCount, minNumber, maxNumber);
        });
        return result != null ? result : new IssuePageResult(0, 0, 0, 0);
    }

    /**
     * Processes a page of pull requests and their embedded reviews/comments within a transaction.
     * <p>
     * Accepts repositoryId rather than a Repository entity to avoid LazyInitializationException: the
     * Repository is fetched inside the transaction with its organization eagerly loaded, so it stays
     * attached to the persistence context when the processor accesses lazy associations.
     * <p>
     * Cursor persistence is also done inside this transaction to ensure atomicity.
     * If data processing succeeds but cursor update fails (or vice versa), both are rolled back.
     * This prevents duplicate processing on restart.
     *
     * @param connection   the GraphQL connection with PR nodes
     * @param scopeId      the scope ID for processing context
     * @param repositoryId the repository ID (NOT the entity, to avoid detached entity issues)
     * @param syncTargetId the sync target ID for cursor persistence
     * @param nextCursor   the cursor to persist (null to clear cursor when page is complete)
     * @return result containing counts of PRs, reviews, and review comments processed
     */
    private PullRequestPageResult processPullRequestsPage(
            GHPullRequestConnection connection,
            Long scopeId,
            Long repositoryId,
            Long syncTargetId,
            @Nullable String nextCursor) {
        PullRequestPageResult result = transactionTemplate.execute(status -> {
            // Fetch repository INSIDE the transaction with organization eagerly loaded.
            // This ensures the entity is attached and lazy associations can be accessed.
            Repository repository = repositoryRepository
                    .findByIdWithOrganization(repositoryId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Repository not found during backfill processing: id=" + repositoryId));

            ProcessingContext context = ProcessingContext.forSync(scopeId, repository);
            int prCount = 0;
            int reviewCount = 0;
            int reviewCommentCount = 0;
            int commentCount = 0;
            int minNumber = Integer.MAX_VALUE;
            int maxNumber = Integer.MIN_VALUE;
            List<PullRequestWithReviewCursor> prsNeedingReviewPagination = new ArrayList<>();

            for (var node : connection.getNodes()) {
                PullRequestWithReviewThreads prData = PullRequestWithReviewThreads.fromPullRequest(node);
                if (prData != null && prData.pullRequest() != null) {
                    PullRequest processed = prProcessor.process(prData.pullRequest(), context);
                    if (processed != null) {
                        prCount++;
                        // Track min/max for progress visibility
                        int number = processed.getNumber();
                        minNumber = Math.min(minNumber, number);
                        maxNumber = Math.max(maxNumber, number);

                        // Process embedded conversation comments (first 10 per PR in historical query).
                        // Same IssueComment entity and processor as issue comments — repository.issues
                        // excludes PRs, so backfill is the only place a backfilled repo can pick these up.
                        EmbeddedCommentsDTO embeddedComments = prData.embeddedComments();
                        for (var commentDto : embeddedComments.comments()) {
                            if (issueCommentProcessor.process(commentDto, processed.getNumber(), context) != null) {
                                commentCount++;
                            }
                        }

                        // Note: as with issue backfill, PRs with more than 10 conversation comments
                        // get the tail from the regular incremental sync, which paginates them.
                        // Keeps backfill simple and avoids extra API calls during historical sync.

                        // Process embedded reviews (first 50 per PR in historical query)
                        // Pass context to enable activity event creation
                        EmbeddedReviewsDTO embeddedReviews = prData.embeddedReviews();
                        for (var reviewDto : embeddedReviews.reviews()) {
                            if (reviewProcessor.process(reviewDto, processed.getId(), context) != null) {
                                reviewCount++;
                            }
                        }

                        // Track PRs that need additional review pagination (with cursor for efficient continuation)
                        if (embeddedReviews.needsPagination()) {
                            prsNeedingReviewPagination.add(new PullRequestWithReviewCursor(
                                    processed,
                                    Objects.requireNonNull(embeddedReviews.endCursor()),
                                    embeddedReviews.reviews().size()));
                        }

                        // Process embedded review threads and their comments
                        EmbeddedReviewThreadsDTO embeddedThreads = prData.embeddedReviewThreads();
                        for (var thread : embeddedThreads.threads()) {
                            int commentsSynced = reviewCommentSyncService.processThread(thread, processed, scopeId);
                            reviewCommentCount += commentsSynced;
                        }
                    }
                }
            }

            // Persist cursor INSIDE the same transaction as data processing.
            // This ensures atomicity: either both data AND cursor are saved, or neither.
            backfillStateProvider.updateSyncCursor(syncTargetId, SyncCursorKind.PULL_REQUEST, nextCursor);

            // Normalize min/max if no items were processed
            if (prCount == 0) {
                minNumber = 0;
                maxNumber = 0;
            }

            return new PullRequestPageResult(
                    prCount,
                    reviewCount,
                    reviewCommentCount,
                    commentCount,
                    prsNeedingReviewPagination,
                    minNumber,
                    maxNumber);
        });
        return result != null ? result : new PullRequestPageResult(0, 0, 0, 0, new ArrayList<>(), 0, 0);
    }

    /**
     * Gets the current backfill progress for a specific sync target.
     *
     * @param syncTargetId the sync target ID
     * @return progress information, or empty if target not found
     */
    @Transactional(readOnly = true)
    public Optional<BackfillProgress> getProgress(Long syncTargetId) {
        return syncTargetProvider.findSyncTargetById(syncTargetId).map(BackfillProgress::fromSyncTarget);
    }

    /**
     * Runs a single backfill batch for one repository, applying the same cooldown/failure
     * bookkeeping and rate-limit gate the scheduled cycle ({@link #backfillSession}) applies
     * per-target. Exposed for the manual backfill sync-job runner ({@code
     * GithubIntegrationSyncRunner}), which drives repository-by-repository progress and cooperative
     * cancellation itself instead of the scheduler's whole-cycle parallel fan-out.
     *
     * <p>Deliberately ignores {@link #isEnabled()} — a manually triggered backfill is the point even
     * when the scheduled cycle is administratively disabled.
     *
     * @param target    the sync target (repository) to backfill
     * @param batchSize maximum pages to process in this batch
     * @param observer  notified after every vendor page within the batch
     * @return true if any work was performed; false if skipped (already complete, in cooldown, or
     *         rate limit below {@code hephaestus.sync.backfill.rate-limit-threshold})
     */
    public boolean runBackfillBatch(SyncTarget target, int batchSize, BackfillPageObserver observer) {
        if (target.isBackfillComplete()) {
            return false;
        }
        if (isInCooldown(target.id())) {
            return false;
        }
        int remainingPoints = graphQlClientProvider.getRateLimitRemaining(target.scopeId());
        if (remainingPoints < syncSchedulerProperties.backfill().rateLimitThreshold()) {
            return false;
        }
        try {
            boolean didWork = backfillRepository(target, batchSize, observer);
            if (didWork) {
                clearFailureState(target.id());
            }
            return didWork;
        } catch (Exception e) {
            handleBackfillFailure(target, e);
            return false;
        }
    }

    /**
     * Invokes {@code observer} for one page, swallowing anything it throws.
     *
     * <p>The observer is a progress callback owned by a caller in another module. It has no business
     * failing a backfill batch that is otherwise succeeding — losing a progress tick is a cosmetic
     * blip, losing the batch means re-fetching every page in it.
     *
     * <p>{@code lowestNumberSeen} is normalized: the page loops seed their running minimum at
     * {@link Integer#MAX_VALUE}, which would read as "nothing done" if it escaped before the first page
     * produced an item.
     */
    // Package-private for direct testing: the page loops it is called from need a live GraphQL
    // conversation to reach, and these two rules are worth pinning without one.
    static void notifyPage(
            BackfillPageObserver observer,
            Long syncTargetId,
            String repositoryName,
            SyncPhase phase,
            int lowestNumberSeen,
            int itemsSyncedInBatch) {
        if (lowestNumberSeen == Integer.MAX_VALUE) {
            return;
        }
        try {
            observer.onPageComplete(syncTargetId, repositoryName, phase, lowestNumberSeen, itemsSyncedInBatch);
        } catch (Exception e) {
            log.debug("Backfill progress observer threw, ignoring: {}", e.toString());
        }
    }

    /**
     * Result of a backfill batch operation with progress tracking information.
     *
     * @param itemsSynced       number of primary items synced (issues or PRs)
     * @param nestedItemsSynced number of nested items synced (comments or reviews)
     * @param hasMore           whether more items remain to sync
     * @param minNumber         lowest issue/PR number in this batch (for progress visibility)
     * @param maxNumber         highest issue/PR number in this batch (for progress visibility)
     */
    private record BackfillBatchResult(
            int itemsSynced, int nestedItemsSynced, boolean hasMore, int minNumber, int maxNumber) {}

    /**
     * Result of processing a page of issues with embedded comments.
     *
     * @param issueCount   number of issues processed
     * @param commentCount number of comments processed
     * @param minNumber    lowest issue number in this page
     * @param maxNumber    highest issue number in this page
     */
    private record IssuePageResult(int issueCount, int commentCount, int minNumber, int maxNumber) {}

    /**
     * Container for PRs that need additional review pagination.
     */
    private record PullRequestWithReviewCursor(PullRequest pullRequest, String reviewCursor, int inlineCount) {}

    /**
     * Result of processing a page of pull requests with embedded reviews and comments.
     *
     * @param prCount                      number of PRs processed
     * @param reviewCount                  number of reviews processed
     * @param reviewCommentCount           number of review comments processed
     * @param prsNeedingReviewPagination   PRs that have more reviews to fetch
     * @param minNumber                    lowest PR number in this page
     * @param maxNumber                    highest PR number in this page
     */
    private record PullRequestPageResult(
            int prCount,
            int reviewCount,
            int reviewCommentCount,
            int commentCount,
            List<PullRequestWithReviewCursor> prsNeedingReviewPagination,
            int minNumber,
            int maxNumber) {}

    /**
     * Represents the backfill progress for a repository.
     *
     * @param itemsRemaining issues + pull requests still to backfill (0 if not initialized)
     * @param itemsTotal     issue + pull request high-water-mark total captured at backfill start
     *                       (0 if not initialized, or if the repository has no issues/PRs)
     */
    public record BackfillProgress(
            String repositoryName,
            boolean isInitialized,
            boolean isComplete,
            @Nullable Instant lastRunAt,
            @Nullable String issueCursor,
            @Nullable String prCursor,
            int itemsRemaining,
            int itemsTotal) {
        public static BackfillProgress fromSyncTarget(SyncTarget target) {
            int issueHighWaterMark =
                    target.issueBackfillHighWaterMark() != null ? target.issueBackfillHighWaterMark() : 0;
            int prHighWaterMark =
                    target.pullRequestBackfillHighWaterMark() != null ? target.pullRequestBackfillHighWaterMark() : 0;
            return new BackfillProgress(
                    target.repositoryNameWithOwner(),
                    target.isBackfillInitialized(),
                    target.isBackfillComplete(),
                    target.backfillLastRunAt(),
                    target.issueSyncCursor(),
                    target.pullRequestSyncCursor(),
                    target.getBackfillRemaining(),
                    issueHighWaterMark + prHighWaterMark);
        }

        public String summary() {
            if (isComplete) {
                return String.format("Backfill complete for %s", repositoryName);
            }
            if (!isInitialized) {
                return String.format("Backfill not started for %s", repositoryName);
            }
            return String.format("Backfill in progress for %s (last run: %s)", repositoryName, lastRunAt);
        }

        /**
         * 0-100 aggregate percent for this repository — the numerator/denominator behind the
         * sync-observability {@code SyncResourceState#backfillPercent}. {@code null} when backfill
         * hasn't started yet (no denominator to divide by).
         */
        @Nullable
        public Integer percentComplete() {
            return percentComplete(isInitialized, itemsRemaining, itemsTotal);
        }

        /**
         * Static so callers with the raw fields in hand but no {@link SyncTarget}/{@link
         * BackfillProgress} instance can share the same math — e.g. {@code
         * GithubConnectionSyncStateProvider} computing a per-resource percent straight from a {@code
         * RepositoryToMonitor} entity's mirrored fields, without an extra {@link #getProgress} lookup.
         */
        @Nullable
        public static Integer percentComplete(boolean initialized, int itemsRemaining, int itemsTotal) {
            if (!initialized) {
                return null;
            }
            if (itemsTotal <= 0) {
                // High-water-marks captured as 0 (an empty repository) — trivially complete.
                return 100;
            }
            int done = Math.max(0, itemsTotal - itemsRemaining);
            return (int) Math.round((100.0 * done) / itemsTotal);
        }
    }

    // Cooldown Management for 5xx Errors

    /**
     * Checks if a repository is currently in cooldown after experiencing errors.
     *
     * @param syncTargetId the sync target ID to check
     * @return true if the repository should be skipped due to cooldown
     */
    private boolean isInCooldown(Long syncTargetId) {
        Instant cooldownExpires = repositoryCooldowns.get(syncTargetId);
        if (cooldownExpires == null) {
            return false;
        }
        if (Instant.now().isAfter(cooldownExpires)) {
            // Cooldown expired - remove from map but keep failure count
            // (failure count only resets on successful backfill)
            repositoryCooldowns.remove(syncTargetId);
            return false;
        }
        return true;
    }

    /**
     * Marks a repository for cooldown after experiencing 5xx errors.
     * Uses escalating cooldowns based on consecutive failure count.
     *
     * @param syncTargetId the sync target ID
     * @param safeRepoName sanitized repository name for logging
     */
    private void markForCooldown(Long syncTargetId, String safeRepoName) {
        int failures = consecutiveFailures.merge(syncTargetId, 1, Integer::sum);

        Duration cooldownDuration;
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            cooldownDuration = EXTENDED_COOLDOWN;
            log.warn(
                    "Repository entering extended cooldown after {} consecutive failures: repo={}, cooldownMinutes={}",
                    failures,
                    safeRepoName,
                    cooldownDuration.toMinutes());
        } else {
            cooldownDuration = COOLDOWN_AFTER_5XX_ERROR;
            log.info(
                    "Repository entering cooldown after transient error: repo={}, failures={}, cooldownMinutes={}",
                    safeRepoName,
                    failures,
                    cooldownDuration.toMinutes());
        }

        repositoryCooldowns.put(syncTargetId, Instant.now().plus(cooldownDuration));
    }

    /**
     * Clears cooldown and failure state for a repository after successful backfill.
     *
     * @param syncTargetId the sync target ID
     */
    private void clearFailureState(Long syncTargetId) {
        repositoryCooldowns.remove(syncTargetId);
        consecutiveFailures.remove(syncTargetId);
    }

    /**
     * Handles backfill failures with appropriate cooldown based on error type.
     *
     * @param target the sync target that failed
     * @param e the exception that occurred
     */
    private void handleBackfillFailure(SyncTarget target, Exception e) {
        String safeRepoName = Objects.requireNonNull(sanitizeForLog(target.repositoryNameWithOwner()));

        // BackfillTransientException is explicitly marked as transient - always cooldown
        if (e instanceof BackfillTransientException) {
            markForCooldown(target.id(), safeRepoName);
            log.info(
                    "Backfill failed with transient error, repository in cooldown: repo={}, error={}",
                    safeRepoName,
                    e.getMessage());
            return;
        }

        // Check if this is a retryable transient error (5xx or transport errors)
        boolean isTransientError = false;
        Throwable cause = e;
        while (cause != null) {
            String message = cause.getMessage();
            String className = cause.getClass().getSimpleName();
            String fullClassName = cause.getClass().getName();

            // Check for GitHubGraphQlException with 5xx status
            if (className.equals("GitHubGraphQlException") && message != null) {
                isTransientError = true;
                break;
            }

            // Check for TransportException (connection closed mid-response, etc.)
            if (className.equals("TransportException")) {
                isTransientError = true;
                break;
            }

            // Check for PrematureCloseException (connection closed during response)
            if (fullClassName.contains("PrematureCloseException")) {
                isTransientError = true;
                break;
            }

            // Check for GraphQlTransportException (wraps transport errors)
            if (fullClassName.contains("GraphQlTransportException")) {
                isTransientError = true;
                break;
            }

            // Check for WebClientResponseException with 5xx status
            if (className.equals("WebClientResponseException") && message != null) {
                if (message.contains("502")
                        || message.contains("503")
                        || message.contains("504")
                        || message.contains("500")) {
                    isTransientError = true;
                    break;
                }
            }

            // Check message content for 5xx indicators, transport errors, or timeouts
            if (message != null) {
                String lowerMessage = message.toLowerCase();
                if (message.contains("502")
                        || message.contains("503")
                        || message.contains("504")
                        || message.contains("500 Internal")
                        || lowerMessage.contains("connection prematurely closed")
                        || lowerMessage.contains("connection reset")
                        || lowerMessage.contains("transport error")
                        || lowerMessage.contains("timeout on blocking read")) {
                    isTransientError = true;
                    break;
                }
            }

            cause = cause.getCause();
        }

        if (isTransientError) {
            // Transient infrastructure or transport error - apply cooldown
            markForCooldown(target.id(), safeRepoName);
            log.warn(
                    "Backfill failed with transient error, repository in cooldown: repo={}, errorType={}, error={}",
                    safeRepoName,
                    e.getClass().getSimpleName(),
                    e.getMessage());
        } else {
            // Other error types - log but don't apply cooldown
            // (could be auth errors, not found, etc. which won't resolve with time)
            log.error(
                    "Backfill failed with non-transient error: repo={}, errorType={}, error={}",
                    safeRepoName,
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
        }
    }

    // Transport Retry Logic

    /**
     * Creates a retry specification for transport-level errors during body streaming.
     * <p>
     * WebClient's ExchangeFilterFunction retries do not cover body streaming errors —
     * PrematureCloseException occurs after HTTP headers are received, during body consumption — so
     * this retry spec is meant to be used with Mono.defer() wrapping the entire execute() call.
     *
     * @param operation  description of the operation for logging
     * @param repoName   repository name for logging
     * @param pageNumber current page number for logging
     * @return a retry specification for transport errors
     */
    private Retry createTransportRetrySpec(String operation, String repoName, int pageNumber) {
        return Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                .jitter(JITTER_FACTOR)
                .filter(ScmTransportErrors::isTransportError)
                .doBeforeRetry(signal -> log.warn(
                        "Retrying {} after transport error: repo={}, page={}, attempt={}, error={}",
                        operation,
                        sanitizeForLog(repoName),
                        pageNumber,
                        signal.totalRetries() + 1,
                        signal.failure().getMessage()));
    }

    /**
     * Resolves the effective pull request page size for backfill.
     *
     * <p>Base sizing follows rate-limit-aware adaptation. Additionally, when a repository has
     * consecutive transient failures we reduce PR page size further so large payloads can progress
     * instead of repeatedly timing out and entering cooldown.
     */
    private int resolveBackfillPrPageSize(Long scopeId, Long syncTargetId) {
        int rateLimitAdjusted = adaptPageSize(
                syncProperties.backfillPrPageSize(), graphQlClientProvider.getRateLimitRemaining(scopeId));
        int failures = consecutiveFailures.getOrDefault(syncTargetId, 0);

        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            return Math.max(1, rateLimitAdjusted / 4);
        }
        if (failures > 0) {
            return Math.max(2, rateLimitAdjusted / 2);
        }
        return rateLimitAdjusted;
    }

    // Exception Types

    /**
     * Signals that backfill hit a transient error after Mono.defer().retryWhen() already exhausted
     * transport-level retries, so the caller should apply repository-level cooldown.
     */
    static class BackfillTransientException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        BackfillTransientException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
