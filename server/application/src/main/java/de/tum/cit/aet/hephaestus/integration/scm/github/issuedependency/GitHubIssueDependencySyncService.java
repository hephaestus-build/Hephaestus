package de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.LARGE_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncMetadata;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser.RepositoryOwnerAndName;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service for syncing GitHub issue dependency (blocked_by/blocking)
 * relationships.
 * <p>
 * GitHub's issue dependencies feature allows marking issues as blocked by other
 * issues, indicating that one issue must be resolved before another can be
 * worked on.
 * <p>
 * <b>Terminology:</b>
 * <ul>
 * <li><b>blocked issue</b> - The issue that cannot proceed until blockers are
 * resolved</li>
 * <li><b>blocking issue</b> - The issue that prevents work on another
 * issue</li>
 * </ul>
 * <p>
 * {@code issue_dependencies} deliveries apply single changes through {@link #processIssueDependencyEvent};
 * {@link #syncDependenciesForScope} reconciles every relationship through GraphQL.
 * <p>
 * This service implements the "find-or-create"
 * pattern for blocker issues. When a blocking issue doesn't exist locally (e.g.,
 * it's in a different repository or hasn't been synced yet), we create a stub
 * issue entity on-the-fly using the GraphQL response data. This ensures blocking
 * relationships are preserved even during incremental sync, before backfill completes.
 */
@Service
public class GitHubIssueDependencySyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssueDependencySyncService.class);

    /** Document name for graphql-documents/GetIssueDependencies.graphql */
    private static final String GET_DEPENDENCIES_DOCUMENT = "GetIssueDependencies";

    private final IssueRepository issueRepository;
    private final RepositoryRepository repositoryRepository;
    private final SyncTargetProvider syncTargetProvider;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubSyncProperties syncProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final GitHubIssueProcessor issueProcessor;
    private final TransactionTemplate transactionTemplate;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    public GitHubIssueDependencySyncService(
            IssueRepository issueRepository,
            RepositoryRepository repositoryRepository,
            SyncTargetProvider syncTargetProvider,
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            SyncSchedulerProperties syncSchedulerProperties,
            GitHubIssueProcessor issueProcessor,
            PlatformTransactionManager transactionManager,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper) {
        this.issueRepository = issueRepository;
        this.repositoryRepository = repositoryRepository;
        this.syncTargetProvider = syncTargetProvider;
        this.graphQlClientProvider = graphQlClientProvider;
        this.syncProperties = syncProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.issueProcessor = issueProcessor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        this.graphQlSyncHelper = graphQlSyncHelper;
    }

    // WEBHOOK EVENT PROCESSING

    /**
     * Process an issue_dependencies webhook event.
     * <p>
     * Creates or removes blocking relationships based on the event type.
     *
     * @param blockedIssueId  row id of the issue being blocked
     * @param blockingIssueId row id of the issue doing the blocking
     * @param isBlock         true if creating a block, false if removing
     */
    @Transactional
    public void processIssueDependencyEvent(long blockedIssueId, long blockingIssueId, boolean isBlock) {
        log.debug(
                "Received issue dependency event: blockedIssueId={}, blockingIssueId={}, isBlock={}",
                blockedIssueId,
                blockingIssueId,
                isBlock);

        Optional<Issue> blockedIssueOpt = issueRepository.findById(blockedIssueId);
        if (blockedIssueOpt.isEmpty()) {
            log.debug("Skipped dependency processing: reason=blockedIssueNotFound, issueId={}", blockedIssueId);
            return;
        }

        Optional<Issue> blockingIssueOpt = issueRepository.findById(blockingIssueId);
        if (blockingIssueOpt.isEmpty()) {
            log.debug("Skipped dependency processing: reason=blockingIssueNotFound, issueId={}", blockingIssueId);
            return;
        }

        Issue blockedIssue = blockedIssueOpt.get();
        Issue blockingIssue = blockingIssueOpt.get();

        if (isBlock) {
            addBlockingRelationship(blockedIssue, blockingIssue);
        } else {
            removeBlockingRelationship(blockedIssue, blockingIssue);
        }
    }

    // GRAPHQL BULK SYNC

    /**
     * Sync all issue dependencies for a scope via GraphQL.
     * <p>
     * <b>Transaction Strategy:</b> This method is NOT transactional at the top
     * level. GraphQL HTTP calls are made outside any transaction to avoid blocking
     * DB connections. Individual database writes use separate transactions.
     *
     * @param scopeId The scope to sync
     * @return Total relationships synced, or -1 if skipped due to cooldown
     */
    public int syncDependenciesForScope(Long scopeId) {
        Optional<SyncMetadata> metadataOpt = syncTargetProvider.getSyncMetadata(scopeId);
        if (metadataOpt.isEmpty()) {
            throw new IllegalArgumentException("Scope not found: " + scopeId);
        }

        SyncMetadata metadata = metadataOpt.get();
        if (!metadata.needsIssueDependenciesSync(syncSchedulerProperties.cooldownMinutes())) {
            log.debug(
                    "Skipped issue dependencies sync: reason=cooldownActive, scopeId={}, lastSyncedAt={}",
                    scopeId,
                    metadata.issueDependenciesSyncedAt());
            return -1;
        }

        // Get repository names via SPI (consuming module implements this)
        List<String> repositoryNames = syncTargetProvider.getRepositoryNamesForScope(scopeId);
        if (repositoryNames.isEmpty()) {
            log.debug("No repositories found for scope: scopeId={}", scopeId);
            // Still update timestamp to prevent repeated empty checks
            transactionTemplate.executeWithoutResult(status -> updateSyncTimestamp(scopeId));
            return 0;
        }

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
        int totalSynced = 0;
        int failedRepoCount = 0;

        for (String repoNameWithOwner : repositoryNames) {
            // Check if rate limit is critically low before making API calls for next repo
            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncHelper.waitForRateLimitIfNeeded(scopeId, "dependency sync", "scopeId", scopeId, log)) {
                    break;
                }
            }

            Optional<Repository> repoOpt = repositoryRepository.findByNameWithOwnerWithOrganization(repoNameWithOwner);
            if (repoOpt.isEmpty()) {
                log.debug(
                        "Skipped dependency sync: reason=repositoryNotFound, repoName={}",
                        sanitizeForLog(repoNameWithOwner));
                continue;
            }

            try {
                int synced = syncRepositoryDependencies(client, repoOpt.get(), scopeId);
                totalSynced += synced;
            } catch (InstallationNotFoundException e) {
                log.warn("Installation not found for scope {}, skipping issue dependency sync", scopeId);
                return 0;
            } catch (Exception e) {
                failedRepoCount++;
                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                        classification,
                        0,
                        MAX_RETRY_ATTEMPTS,
                        "dependency sync",
                        "repoName",
                        Objects.requireNonNullElse(sanitizeForLog(repoNameWithOwner), "<unknown>"),
                        log));
            }
        }

        // Only update timestamp if at least some repos succeeded
        if (failedRepoCount < repositoryNames.size()) {
            transactionTemplate.executeWithoutResult(status -> updateSyncTimestamp(scopeId));
        }

        log.info(
                "Completed issue dependency sync: scopeId={}, dependencyCount={}, failedRepoCount={}",
                scopeId,
                totalSynced,
                failedRepoCount);
        return totalSynced;
    }

    /**
     * Update the sync timestamp for issue dependencies.
     */
    private void updateSyncTimestamp(Long scopeId) {
        syncTargetProvider.updateScopeSyncTimestamp(
                scopeId, SyncTargetProvider.SyncType.ISSUE_DEPENDENCIES, Instant.now());
    }

    /**
     * Sync dependencies for a single repository.
     * Uses type-safe generated DTOs for GraphQL response handling.
     */
    private int syncRepositoryDependencies(HttpGraphQlClient client, Repository repo, Long scopeId) {
        String safeNameWithOwner = Objects.requireNonNullElse(sanitizeForLog(repo.getNameWithOwner()), "<unknown>");
        Optional<RepositoryOwnerAndName> parsedName = GitHubRepositoryNameParser.parse(repo.getNameWithOwner());
        if (parsedName.isEmpty()) {
            log.warn("Skipped dependency sync: reason=invalidNameFormat, repoName={}", safeNameWithOwner);
            return 0;
        }
        String owner = parsedName.get().owner();
        String name = parsedName.get().name();
        String after = null;
        boolean hasNextPage = true;
        int totalSynced = 0;
        // Raw issue nodes received across all pages — apples-to-apples with issues.totalCount.
        // (totalSynced counts dependency relationships, which most issues don't have at all.)
        int issuesReceived = 0;
        int reportedTotalCount = -1;
        int pageCount = 0;
        int retryAttempt = 0;

        while (hasNextPage) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for dependency sync: repoName={}, limit={}",
                        safeNameWithOwner,
                        MAX_PAGINATION_PAGES);
                break;
            }

            // GraphQL call OUTSIDE of @Transactional to avoid blocking DB connection
            final String currentAfter = after;
            final int currentPage = pageCount;

            ClientGraphQlResponse graphQlResponse = Mono.defer(() -> client.documentName(GET_DEPENDENCIES_DOCUMENT)
                            .variable("owner", owner)
                            .variable("name", name)
                            .variable(
                                    "first",
                                    adaptPageSize(
                                            LARGE_PAGE_SIZE, graphQlClientProvider.getRateLimitRemaining(scopeId)))
                            .variable("after", currentAfter)
                            .execute())
                    .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                            .maxBackoff(TRANSPORT_MAX_BACKOFF)
                            .jitter(JITTER_FACTOR)
                            .filter(ScmTransportErrors::isTransportError)
                            .doBeforeRetry(signal -> log.warn(
                                    "Retrying after transport error: context=dependencySync, repoName={}, page={}, attempt={}, error={}",
                                    safeNameWithOwner,
                                    currentPage,
                                    signal.totalRetries() + 1,
                                    signal.failure().getMessage())))
                    .block(syncProperties.graphqlTimeout());

            if (graphQlResponse == null || !graphQlResponse.isValid()) {
                ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(graphQlResponse);
                if (classification != null) {
                    if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                            classification,
                            retryAttempt,
                            MAX_RETRY_ATTEMPTS,
                            "dependency repository sync",
                            "repoName",
                            safeNameWithOwner,
                            log))) {
                        retryAttempt++;
                        continue;
                    }
                    break;
                }
                log.warn(
                        "Received invalid GraphQL response: repoName={}, errors={}",
                        safeNameWithOwner,
                        graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                break;
            }

            // Track rate limit from response
            graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

            // Check if we should pause due to rate limiting
            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                        scopeId, "dependency repository sync", "repoName", safeNameWithOwner, log)) {
                    break;
                }
            }

            GHIssueConnection issueConnection =
                    graphQlResponse.field("repository.issues").toEntity(GHIssueConnection.class);

            if (issueConnection == null || issueConnection.getNodes() == null) {
                log.warn("Skipped dependency sync: reason=emptyGraphQLResponse, repoName={}", safeNameWithOwner);
                break;
            }

            if (reportedTotalCount < 0) {
                reportedTotalCount = issueConnection.getTotalCount();
            }

            issuesReceived += issueConnection.getNodes().size();

            var pageInfo = issueConnection.getPageInfo();
            if (pageInfo == null) {
                log.debug("Received null pageInfo during dependency sync: repoName={}", safeNameWithOwner);
            }
            hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
            after = pageInfo != null ? pageInfo.getEndCursor() : null;

            retryAttempt = 0;

            totalSynced +=
                    transactionTemplate.execute(status -> processIssueDependencies(issueConnection, repo, scopeId));
        }

        // Raw issue nodes received (not dependency relationships synced) vs issues.totalCount.
        if (reportedTotalCount >= 0) {
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "issues", issuesReceived, reportedTotalCount, hasNextPage, "repoName=" + safeNameWithOwner);
        }

        return totalSynced;
    }

    /**
     * Process a page of issue dependencies in a separate transaction.
     * <p>
     * Using REQUIRES_NEW ensures each page is committed independently,
     * providing better resilience if a single page fails.
     */
    private int processIssueDependencies(GHIssueConnection issueConnection, Repository repo, Long scopeId) {
        if (issueConnection.getNodes() == null) {
            return 0;
        }

        int synced = 0;
        for (var graphQlIssue : issueConnection.getNodes()) {
            synced += processIssueDependencies(graphQlIssue, repo, scopeId);
        }
        return synced;
    }

    /**
     * Process dependencies for a single issue from the GraphQL response.
     * <p>
     * Implements find-or-create pattern: if the issue doesn't exist locally,
     * we create it from the GraphQL data. This ensures relationships are
     * preserved even before backfill completes.
     */
    private int processIssueDependencies(GHIssue graphQlIssue, Repository repo, Long scopeId) {
        if (graphQlIssue.getFullDatabaseId() == null) {
            return 0;
        }

        // Find or create the blocked issue
        Issue issue = findOrCreateIssue(graphQlIssue, repo, scopeId);
        if (issue == null) {
            return 0;
        }

        // Re-fetch with blockedBy eagerly loaded to avoid LazyInitializationException.
        // The persistence context may have been cleared by prior upsertCore() calls
        // (clearAutomatically=true) during stub creation for other issues in this page.
        issue = issueRepository.findByIdWithBlockedBy(issue.getId()).orElse(null);
        if (issue == null) {
            return 0;
        }

        // Process blockedBy relationships
        var blockedBy = graphQlIssue.getBlockedBy();
        if (blockedBy == null
                || blockedBy.getNodes() == null
                || blockedBy.getNodes().isEmpty()) {
            // No blockers - clear any existing relationships
            if (!issue.getBlockedBy().isEmpty()) {
                issue.getBlockedBy().clear();
                issueRepository.save(issue);
            }
            return 0;
        }

        GraphQlConnectionOverflowDetector.check(
                "blockedBy",
                blockedBy.getNodes().size(),
                blockedBy.getTotalCount(),
                "Issue #" + graphQlIssue.getNumber());

        return syncBlockedByRelationships(issue, blockedBy.getNodes(), scopeId);
    }

    /**
     * Find an issue by ID, or create it from GraphQL data if it doesn't exist.
     * <p>
     * This implements the find-or-create pattern needed for dependency sync
     * to work during incremental sync, before backfill completes.
     * <p>
     * <b>Note:</b> Uses {@code processStub()} to create placeholder issues without
     * triggering domain events. These stubs will be hydrated with full data when
     * the regular issue sync runs.
     */
    @Nullable
    private Issue findOrCreateIssue(GHIssue graphQlIssue, Repository repo, Long scopeId) {
        if (graphQlIssue.getFullDatabaseId() == null) {
            return null;
        }

        long issueId = graphQlIssue.getFullDatabaseId().longValue();
        Optional<Issue> existingOpt = issueRepository.findById(issueId);
        if (existingOpt.isPresent()) {
            return existingOpt.get();
        }

        // Issue doesn't exist - create it from GraphQL data as a stub
        log.debug(
                "Creating stub issue from dependency sync: issueId={}, issueNumber={}, repoName={}",
                issueId,
                graphQlIssue.getNumber(),
                sanitizeForLog(repo.getNameWithOwner()));

        GitHubIssueDTO dto = GitHubIssueDTO.fromIssue(graphQlIssue);
        if (dto == null) {
            return null;
        }

        ProcessingContext context = ProcessingContext.forSync(scopeId, repo);
        return issueProcessor.processStub(dto, context);
    }

    /**
     * Synchronize blocked-by relationships for an issue.
     * <p>
     * Implements find-or-create pattern for blocker issues: if a blocker
     * doesn't exist locally, we create a stub issue from the GraphQL data.
     * This ensures relationships are preserved even before backfill completes.
     */
    private int syncBlockedByRelationships(Issue issue, List<GHIssue> blockerNodes, Long scopeId) {
        if (blockerNodes.isEmpty()) {
            // Remove all existing relationships
            int removed = issue.getBlockedBy().size();
            if (removed > 0) {
                issue.getBlockedBy().clear();
                issueRepository.save(issue);
            }
            return 0;
        }

        // Build map of blocker ID -> GraphQL data for find-or-create
        Map<Long, GHIssue> blockerGraphQlData = new HashMap<>();
        Set<Long> expectedBlockerIds = new HashSet<>();
        for (GHIssue blockerNode : blockerNodes) {
            if (blockerNode.getFullDatabaseId() != null) {
                long blockerId = blockerNode.getFullDatabaseId().longValue();
                expectedBlockerIds.add(blockerId);
                blockerGraphQlData.put(blockerId, blockerNode);
            }
        }

        if (expectedBlockerIds.isEmpty()) {
            return 0;
        }

        // Batch load existing blockers (fixes N+1 problem)
        List<Issue> existingBlockers = issueRepository.findAllById(expectedBlockerIds);
        Set<Long> foundBlockerIds = existingBlockers.stream().map(Issue::getId).collect(Collectors.toSet());

        // Find blockers that need to be created
        Set<Long> missingBlockerIds = new HashSet<>(expectedBlockerIds);
        missingBlockerIds.removeAll(foundBlockerIds);

        // Create missing blockers from GraphQL data
        List<Issue> createdBlockers = new ArrayList<>();
        for (Long missingId : missingBlockerIds) {
            GHIssue blockerGraphQl = blockerGraphQlData.get(missingId);
            Issue createdBlocker = createBlockerIssue(blockerGraphQl, scopeId);
            if (createdBlocker != null) {
                createdBlockers.add(createdBlocker);
                log.debug(
                        "Created stub blocker issue: blockerId={}, blockerNumber={}, blockedIssueNumber={}",
                        createdBlocker.getId(),
                        createdBlocker.getNumber(),
                        issue.getNumber());
            }
        }

        // Combine existing and created blockers
        List<Issue> allBlockers = new ArrayList<>(existingBlockers);
        allBlockers.addAll(createdBlockers);
        Set<Long> allBlockerIds = allBlockers.stream().map(Issue::getId).collect(Collectors.toSet());

        int changes = 0;

        // Add missing relationships
        for (Issue blocker : allBlockers) {
            if (!issue.getBlockedBy().contains(blocker)) {
                issue.getBlockedBy().add(blocker);
                changes++;
            }
        }

        // Remove stale relationships (blockers that should no longer exist)
        int initialSize = issue.getBlockedBy().size();
        issue.getBlockedBy().removeIf(blocker -> !allBlockerIds.contains(blocker.getId()));
        int removedCount = initialSize - issue.getBlockedBy().size();
        changes += removedCount;

        if (changes > 0) {
            issueRepository.save(issue);
        }

        return changes;
    }

    /**
     * Create a blocker issue from GraphQL data.
     * <p>
     * The blocker may be in a different repository than the blocked issue.
     * We use the repository reference from the GraphQL data to find or create
     * the repository and then create the issue in that repository.
     * <p>
     * <b>Note:</b> Uses {@code processStub()} to create placeholder issues without
     * triggering domain events. These stubs will be hydrated with full data when
     * the regular issue sync runs.
     */
    private @Nullable Issue createBlockerIssue(@Nullable GHIssue blockerGraphQl, Long scopeId) {
        if (blockerGraphQl == null || blockerGraphQl.getFullDatabaseId() == null) {
            return null;
        }

        // The blocker may be in a different repository
        Repository blockerRepo = resolveBlockerRepository(blockerGraphQl);
        if (blockerRepo == null) {
            log.debug(
                    "Skipped creating blocker: reason=repositoryNotFound, blockerId={}, blockerNumber={}",
                    blockerGraphQl.getFullDatabaseId(),
                    blockerGraphQl.getNumber());
            return null;
        }

        GitHubIssueDTO dto = GitHubIssueDTO.fromIssueWithRepository(blockerGraphQl);
        if (dto == null) {
            return null;
        }

        ProcessingContext context = ProcessingContext.forSync(scopeId, blockerRepo);
        return issueProcessor.processStub(dto, context);
    }

    /**
     * Resolve the repository for a blocker issue.
     * <p>
     * The blocker issue may be in a different repository. We first try to find
     * it by the nameWithOwner from the GraphQL response. If not found and the
     * repository is within the same scope, we could create it - but for now
     * we just skip blockers in unknown repositories.
     */
    @Nullable
    private Repository resolveBlockerRepository(GHIssue blockerGraphQl) {
        if (blockerGraphQl.getRepository() == null) {
            return null;
        }

        String nameWithOwner = blockerGraphQl.getRepository().getNameWithOwner();
        if (nameWithOwner == null) {
            return null;
        }

        return repositoryRepository
                .findByNameWithOwnerWithOrganization(nameWithOwner)
                .orElse(null);
    }

    // HELPER METHODS

    private void addBlockingRelationship(Issue blockedIssue, Issue blockingIssue) {
        if (blockedIssue.getBlockedBy().contains(blockingIssue)) {
            log.debug(
                    "Skipped adding blocking relationship: reason=alreadyBlocked, blockedIssueNumber={}, blockingIssueNumber={}",
                    blockedIssue.getNumber(),
                    blockingIssue.getNumber());
            return;
        }

        blockedIssue.getBlockedBy().add(blockingIssue);
        issueRepository.save(blockedIssue);

        log.info(
                "Added blocking relationship: blockedIssueNumber={}, blockingIssueNumber={}",
                blockedIssue.getNumber(),
                blockingIssue.getNumber());
    }

    private void removeBlockingRelationship(Issue blockedIssue, Issue blockingIssue) {
        boolean removed = blockedIssue.getBlockedBy().remove(blockingIssue);

        if (removed) {
            issueRepository.save(blockedIssue);
            log.info(
                    "Removed blocking relationship: blockedIssueNumber={}, blockingIssueNumber={}",
                    blockedIssue.getNumber(),
                    blockingIssue.getNumber());
        } else {
            log.debug(
                    "Skipped removing blocking relationship: reason=notBlocked, blockedIssueNumber={}, blockingIssueNumber={}",
                    blockedIssue.getNumber(),
                    blockingIssue.getNumber());
        }
    }
}
