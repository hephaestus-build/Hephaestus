package de.tum.cit.aet.hephaestus.integration.scm.github.subissue;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.LARGE_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncMetadata;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
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
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHSubIssuesSummary;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * Service for syncing sub-issue (parent-child) relationships from GitHub.
 * <p>
 * GitHub's sub-issues feature creates a hierarchical relationship between
 * issues.
 * This service handles:
 * <ul>
 * <li>Processing webhook events to update relationships in real-time</li>
 * <li>Scope-level GraphQL sync to fetch all relationships</li>
 * </ul>
 * <p>
 * <b>Architecture Notes:</b>
 * <ul>
 * <li>GraphQL calls are made OUTSIDE transactions to avoid blocking DB
 * connections</li>
 * <li>Database writes use separate transactions via REQUIRES_NEW</li>
 * <li>Uses Spring's {@code documentName()} for query loading</li>
 * <li>Respects sync cooldown to avoid excessive API calls</li>
 * </ul>
 * <p>
 * <b>ARCHITECTURE NOTE (Jan 2026):</b> This service implements the "find-or-create"
 * pattern for parent issues. When a parent issue doesn't exist locally (e.g.,
 * it's in a different repository or hasn't been synced yet), we create a stub
 * issue entity on-the-fly using the GraphQL response data. This ensures parent-child
 * relationships are preserved even during incremental sync, before backfill completes.
 *
 * @see GitHubSubIssuesMessageHandler
 */
@Service
public class GitHubSubIssueSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubSubIssueSyncService.class);

    /** Document name for graphql-documents/GetSubIssuesForRepository.graphql */
    private static final String GET_SUB_ISSUES_DOCUMENT = "GetSubIssuesForRepository";

    private final IssueRepository issueRepository;
    private final RepositoryRepository repositoryRepository;
    private final IdentityProviderLookup identityProviders;
    private final SyncTargetProvider syncTargetProvider;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubSyncProperties syncProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final GitHubIssueProcessor issueProcessor;
    private final TransactionTemplate transactionTemplate;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;
    private final ApplicationEventPublisher events;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    public GitHubSubIssueSyncService(
            IssueRepository issueRepository,
            RepositoryRepository repositoryRepository,
            IdentityProviderLookup identityProviders,
            SyncTargetProvider syncTargetProvider,
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            SyncSchedulerProperties syncSchedulerProperties,
            GitHubIssueProcessor issueProcessor,
            PlatformTransactionManager transactionManager,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper,
            ApplicationEventPublisher events) {
        this.issueRepository = issueRepository;
        this.repositoryRepository = repositoryRepository;
        this.identityProviders = identityProviders;
        this.syncTargetProvider = syncTargetProvider;
        this.graphQlClientProvider = graphQlClientProvider;
        this.syncProperties = syncProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.issueProcessor = issueProcessor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        this.graphQlSyncHelper = graphQlSyncHelper;
        this.events = events;
    }

    // WEBHOOK EVENT PROCESSING

    /** Apply the native relationship and progress in the webhook transaction. */
    @Transactional
    public void processSubIssueEvent(
            long subIssueId,
            long parentIssueId,
            boolean isLink,
            @Nullable SubIssuesSummaryDTO parentSummary,
            ProcessingContext context) {
        var child = issueRepository.findById(subIssueId).orElse(null);
        var parent = issueRepository.findById(parentIssueId).orElse(null);
        if (child == null || parent == null) return;
        var previous = child.getParentIssue();
        if (!isLink && (previous == null || !previous.getId().equals(parentIssueId))) {
            // A supplied provider summary is authoritative even when this mirror did not hold the relation.
            // Without a relationship change or a summary, there is no new progress fact to store.
            if (parentSummary != null && updateParentSummary(parent, parentSummary)) {
                publishProgress(parent, EventContext.from(context));
            }
            return;
        }
        var eventContext = EventContext.from(context);
        boolean relationshipChanged = isLink ? !Objects.equals(previous, parent) : true;
        if (!relationshipChanged && parentSummary == null) return;
        child.setParentIssue(isLink ? parent : null);
        issueRepository.saveAndFlush(child);
        if (isLink && previous != null && !previous.getId().equals(parentIssueId)) {
            updateParentSummary(previous, null);
            publishProgress(previous, eventContext);
        }
        boolean summaryChanged = updateParentSummary(parent, parentSummary);
        if (relationshipChanged || summaryChanged) publishProgress(parent, eventContext);
    }

    /** A child hook cannot certify that the stored child inventory is complete. */
    @Transactional
    public void invalidateParentCompletionOf(long issueId) {
        var parent =
                issueRepository.findById(issueId).map(Issue::getParentIssue).orElse(null);
        if (parent == null) return;
        parent.setSubIssuesCompleted(null);
        parent.setSubIssuesPercentCompleted(null);
        issueRepository.save(parent);
    }

    private boolean updateParentSummary(Issue parent, @Nullable SubIssuesSummaryDTO summary) {
        Integer total = summary == null ? null : summary.total();
        Integer completed = summary == null ? null : summary.completed();
        boolean changed = !Objects.equals(parent.getSubIssuesTotal(), total)
                || !Objects.equals(parent.getSubIssuesCompleted(), completed);
        parent.setSubIssuesTotal(total);
        parent.setSubIssuesCompleted(completed);
        parent.setSubIssuesPercentCompleted(summary == null ? null : summary.percentCompleted());
        issueRepository.save(parent);
        return changed;
    }

    private void publishProgress(Issue parent, EventContext context) {
        events.publishEvent(new ScmDomainEvent.IssueUpdated(
                ScmEventPayload.IssueData.from(parent), Set.of(ScmItemEvents.ISSUE_PROGRESS_FIELD), context));
    }

    private EventContext syncContext(Issue parent, Long scopeId) {
        return EventContext.forSync(
                scopeId, RepositoryRef.from(parent.getRepository()), IdentityProviderType.GITHUB, Instant.now());
    }

    // GRAPHQL BULK SYNC

    /**
     * Sync all sub-issue relationships for a scope via GraphQL.
     * <p>
     * <b>Transaction Strategy:</b> This method is NOT transactional at the top
     * level.
     * GraphQL HTTP calls are made outside any transaction to avoid blocking DB
     * connections.
     * Individual database writes use separate transactions.
     * <p>
     * <b>Cooldown:</b> Respects sync cooldown to avoid excessive API calls.
     *
     * @param scopeId The scope to sync
     * @return total number of relationships synchronized, or -1 if skipped due to
     *         cooldown
     */
    public int syncSubIssuesForScope(Long scopeId) {
        // Load scope metadata via SPI
        Optional<SyncMetadata> metadataOpt = syncTargetProvider.getSyncMetadata(scopeId);
        if (metadataOpt.isEmpty()) {
            throw new IllegalArgumentException("Scope not found: " + scopeId);
        }

        SyncMetadata metadata = metadataOpt.get();

        // Check cooldown
        if (!metadata.needsSubIssuesSync(syncSchedulerProperties.cooldownMinutes())) {
            log.debug(
                    "Skipped sub-issues sync: reason=cooldownActive, scopeId={}, lastSyncedAt={}",
                    scopeId,
                    metadata.subIssuesSyncedAt());
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

        var provider = identityProviders
                .findId(IdentityProviderType.GITHUB, PersonSourceNamespace.GITHUB_DEFAULT_URL)
                .orElseThrow(() -> new IllegalStateException("IdentityProvider not found for type=GITHUB, serverUrl="
                        + PersonSourceNamespace.GITHUB_DEFAULT_URL));
        Long providerId = Objects.requireNonNull(provider);
        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

        log.info("Starting sub-issue sync: scopeId={}, repoCount={}", scopeId, repositoryNames.size());

        int totalLinked = 0;
        int failedRepoCount = 0;

        for (String repoNameWithOwner : repositoryNames) {
            // Check if rate limit is critically low before making API calls for next repo
            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncHelper.waitForRateLimitIfNeeded(scopeId, "sub-issue sync", "scopeId", scopeId, log)) {
                    break;
                }
            }

            Optional<Repository> repoOpt =
                    repositoryRepository.findByNameWithOwnerAndProviderId(repoNameWithOwner, providerId);
            if (repoOpt.isEmpty()) {
                log.debug(
                        "Skipped sub-issue sync: reason=repositoryNotFound, repoName={}",
                        sanitizeForLog(repoNameWithOwner));
                continue;
            }

            try {
                totalLinked += syncSubIssuesForRepository(client, repoOpt.get(), scopeId);
            } catch (InstallationNotFoundException e) {
                log.warn("Installation not found for scope {}, skipping sub-issue sync", scopeId);
                return 0;
            } catch (Exception e) {
                failedRepoCount++;
                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                        classification,
                        0,
                        MAX_RETRY_ATTEMPTS,
                        "sub-issue sync",
                        "repoName",
                        Objects.requireNonNullElse(sanitizeForLog(repoNameWithOwner), "null"),
                        log));
            }
        }

        // Only update timestamp if at least some repos succeeded
        if (failedRepoCount < repositoryNames.size()) {
            transactionTemplate.executeWithoutResult(status -> updateSyncTimestamp(scopeId));
        }

        log.info(
                "Completed sub-issue sync: scopeId={}, subIssueCount={}, failedRepoCount={}",
                scopeId,
                totalLinked,
                failedRepoCount);

        return totalLinked;
    }

    private void updateSyncTimestamp(Long scopeId) {
        syncTargetProvider.updateScopeSyncTimestamp(scopeId, SyncTargetProvider.SyncType.SUB_ISSUES, Instant.now());
    }

    private int syncSubIssuesForRepository(HttpGraphQlClient client, Repository repository, Long scopeId) {
        Optional<RepositoryOwnerAndName> parsedName = GitHubRepositoryNameParser.parse(repository.getNameWithOwner());
        if (parsedName.isEmpty()) {
            log.warn(
                    "Skipped sub-issue sync: reason=invalidNameFormat, repoName={}",
                    sanitizeForLog(repository.getNameWithOwner()));
            return 0;
        }
        String owner = parsedName.get().owner();
        String name = parsedName.get().name();
        String cursor = null;
        boolean hasNextPage = true;
        int linkedCount = 0;
        // Raw issue nodes received across all pages — apples-to-apples with issues.totalCount.
        // (linkedCount counts sub-issue links found, which most issues don't have.)
        int issuesReceived = 0;
        int reportedTotalCount = -1;

        int pageCount = 0;
        int retryAttempt = 0;

        while (hasNextPage) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for sub-issue sync: repoName={}, limit={}",
                        sanitizeForLog(repository.getNameWithOwner()),
                        MAX_PAGINATION_PAGES);
                break;
            }

            try {
                // GraphQL call OUTSIDE of @Transactional to avoid blocking DB connection
                final String currentCursor = cursor;
                final int currentPage = pageCount;

                ClientGraphQlResponse graphQlResponse = Mono.defer(() -> client.documentName(GET_SUB_ISSUES_DOCUMENT)
                                .variable("owner", owner)
                                .variable("name", name)
                                .variable(
                                        "first",
                                        adaptPageSize(
                                                LARGE_PAGE_SIZE, graphQlClientProvider.getRateLimitRemaining(scopeId)))
                                .variable("after", currentCursor)
                                .execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying after transport error: context=subIssueSync, repoName={}, page={}, attempt={}, error={}",
                                        Objects.requireNonNullElse(
                                                sanitizeForLog(repository.getNameWithOwner()), "null"),
                                        currentPage,
                                        signal.totalRetries() + 1,
                                        signal.failure().getClass().getSimpleName())))
                        .block(syncProperties.graphqlTimeout());

                if (graphQlResponse == null || !graphQlResponse.isValid()) {
                    ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(graphQlResponse);
                    if (classification != null) {
                        if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                                classification,
                                retryAttempt,
                                MAX_RETRY_ATTEMPTS,
                                "sub-issue repository sync",
                                "repoName",
                                Objects.requireNonNullElse(sanitizeForLog(repository.getNameWithOwner()), "null"),
                                log))) {
                            retryAttempt++;
                            continue;
                        }
                        break;
                    }
                    log.warn(
                            "Received invalid GraphQL response: repoName={}, errors={}",
                            sanitizeForLog(repository.getNameWithOwner()),
                            graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                    break;
                }

                if (!graphQlResponse.getErrors().isEmpty()) {
                    log.warn(
                            "Skipped sub-issue page: reason=partialGraphQlResponse, repoName={}",
                            sanitizeForLog(repository.getNameWithOwner()));
                    break;
                }

                // Track rate limit from response
                graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

                // Check if we should pause due to rate limiting
                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                            scopeId,
                            "sub-issue repository sync",
                            "repoName",
                            Objects.requireNonNullElse(sanitizeForLog(repository.getNameWithOwner()), "null"),
                            log)) {
                        break;
                    }
                }

                GHIssueConnection issueConnection =
                        graphQlResponse.field("repository.issues").toEntity(GHIssueConnection.class);

                if (issueConnection == null) {
                    log.warn(
                            "Skipped sub-issue sync: reason=emptyGraphQLResponse, repoName={}",
                            sanitizeForLog(repository.getNameWithOwner()));
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = issueConnection.getTotalCount();
                }

                var pageInfo = issueConnection.getPageInfo();
                if (pageInfo == null) {
                    log.debug(
                            "Received null pageInfo during sub-issue sync: repoName={}",
                            sanitizeForLog(repository.getNameWithOwner()));
                }
                hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
                retryAttempt = 0;

                issuesReceived += issueConnection.getNodes() != null
                        ? issueConnection.getNodes().size()
                        : 0;

                linkedCount +=
                        transactionTemplate.execute(status -> processIssueNodes(issueConnection, repository, scopeId));
            } catch (InstallationNotFoundException e) {
                // Re-throw to abort the entire sync operation
                throw e;
            } catch (Exception e) {
                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                if (!graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                        classification,
                        retryAttempt,
                        MAX_RETRY_ATTEMPTS,
                        "sub-issue repository sync",
                        "repoName",
                        Objects.requireNonNullElse(sanitizeForLog(repository.getNameWithOwner()), "null"),
                        log))) {
                    break;
                }
                retryAttempt++;
            }
        }

        // Raw issue nodes received (not sub-issue links found) vs issues.totalCount.
        if (reportedTotalCount >= 0) {
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "issues",
                    issuesReceived,
                    reportedTotalCount,
                    hasNextPage,
                    "repoName=" + sanitizeForLog(repository.getNameWithOwner()));
        }

        return linkedCount;
    }

    /**
     * Process a page of issues in a separate transaction.
     * <p>
     * Using REQUIRES_NEW ensures each page is committed independently,
     * providing better resilience if a single page fails.
     */
    int processIssueNodes(GHIssueConnection issueConnection, Repository repository, Long scopeId) {
        if (issueConnection.getNodes() == null) {
            return 0;
        }

        int linkedCount = 0;

        for (var graphQlIssue : issueConnection.getNodes()) {
            if (graphQlIssue.getParent() != null) {
                linkedCount += processParentRelationship(graphQlIssue, repository, scopeId);
            }
        }
        // A summary from this page is authoritative even if a later node changed a stored relationship.
        for (var graphQlIssue : issueConnection.getNodes()) {
            if (graphQlIssue.getSubIssuesSummary() != null) {
                processSubIssuesSummary(graphQlIssue, repository, scopeId);
            }
        }

        return linkedCount;
    }

    /**
     * Process parent relationship for a sub-issue.
     * <p>
     * Implements find-or-create pattern: if the sub-issue or parent doesn't exist
     * locally, we create it from the GraphQL data. This ensures relationships are
     * preserved even before backfill completes.
     */
    private int processParentRelationship(GHIssue graphQlIssue, Repository repository, Long scopeId) {
        if (graphQlIssue.getFullDatabaseId() == null) {
            return 0;
        }
        GHIssue parentGraphQl = graphQlIssue.getParent();
        if (parentGraphQl == null || parentGraphQl.getFullDatabaseId() == null) {
            return 0;
        }

        // Find or create the sub-issue
        Issue subIssue = findOrCreateIssue(graphQlIssue, repository, scopeId);
        if (subIssue == null) {
            return 0;
        }

        // Find or create the parent issue
        Issue parent = findOrCreateParentIssue(parentGraphQl, repository, scopeId);
        if (parent == null) {
            if (parentRepositoryPath(parentGraphQl) != null) {
                clearForeignParent(subIssue, repository);
            }
            log.debug(
                    "Skipped parent relationship: reason=parentNotCreated, subIssueNumber={}, parentNumber={}",
                    graphQlIssue.getNumber(),
                    parentGraphQl.getNumber());
            return 0;
        }

        // Check if relationship already exists
        if (Objects.equals(parent, subIssue.getParentIssue())) {
            return 0;
        }

        var previous = subIssue.getParentIssue();
        subIssue.setParentIssue(parent);
        issueRepository.saveAndFlush(subIssue);
        if (previous != null) {
            updateParentSummary(previous, null);
            publishProgress(previous, syncContext(previous, scopeId));
        }
        updateParentSummary(parent, null);
        publishProgress(parent, syncContext(parent, scopeId));

        log.debug(
                "Linked issue to parent: issueNumber={}, parentIssueNumber={}, repoName={}",
                subIssue.getNumber(),
                parent.getNumber(),
                sanitizeForLog(repository.getNameWithOwner()));
        return 1;
    }

    private void clearForeignParent(Issue subIssue, Repository repository) {
        Issue storedParent = subIssue.getParentIssue();
        if (storedParent == null) {
            return;
        }
        Long providerId = repository.getProvider().getId();
        Long parentProviderId = storedParent.getProvider().getId();
        if (providerId != null && parentProviderId != null && !providerId.equals(parentProviderId)) {
            subIssue.setParentIssue(null);
            issueRepository.save(subIssue);
        }
    }

    /**
     * Find an issue by ID, or create it from GraphQL data if it doesn't exist.
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
        // Rows are keyed by (provider, native id), not by GitHub's id: the row is found the way the
        // issue processor re-reads it after its upsert, by repository and number.
        Optional<Issue> existingOpt =
                issueRepository.findByRepositoryIdAndNumber(repo.getId(), graphQlIssue.getNumber());
        if (existingOpt.isPresent()) {
            return existingOpt.get();
        }

        // Issue doesn't exist - create it from GraphQL data as a stub
        log.debug(
                "Creating stub issue from sub-issue sync: issueId={}, issueNumber={}, repoName={}",
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
     * Find or create a parent issue from GraphQL data.
     * <p>
     * The parent may be in a different repository than the sub-issue.
     * We use the repository reference from the GraphQL data to find the repository
     * and create the issue there.
     * <p>
     * <b>Note:</b> Uses {@code processStub()} to create placeholder issues without
     * triggering domain events. These stubs will be hydrated with full data when
     * the regular issue sync runs.
     */
    @Nullable
    private Issue findOrCreateParentIssue(GHIssue parentGraphQl, Repository childRepository, Long scopeId) {
        if (parentGraphQl.getFullDatabaseId() == null) {
            return null;
        }

        long parentId = parentGraphQl.getFullDatabaseId().longValue();
        // The parent may live in another repository; it is found there by number, as the child is.
        Repository parentRepo = resolveParentRepository(parentGraphQl, childRepository);
        if (parentRepo == null) {
            log.debug(
                    "Skipped parent lookup: reason=repositoryNotFound, parentId={}, parentNumber={}",
                    parentId,
                    parentGraphQl.getNumber());
            return null;
        }
        Optional<Issue> existingOpt =
                issueRepository.findByRepositoryIdAndNumber(parentRepo.getId(), parentGraphQl.getNumber());
        if (existingOpt.isPresent()) {
            return existingOpt.get();
        }

        // Parent doesn't exist - create it from GraphQL data as a stub

        log.debug(
                "Creating stub parent issue from sub-issue sync: parentId={}, parentNumber={}, repoName={}",
                parentId,
                parentGraphQl.getNumber(),
                sanitizeForLog(parentRepo.getNameWithOwner()));

        GitHubIssueDTO dto = GitHubIssueDTO.fromIssueWithRepository(parentGraphQl);
        if (dto == null) {
            return null;
        }

        ProcessingContext context = ProcessingContext.forSync(scopeId, parentRepo);
        return issueProcessor.processStub(dto, context);
    }

    /**
     * Resolve the repository for a parent issue.
     * <p>
     * The parent issue may be in a different repository. We find it by the
     * nameWithOwner from the GraphQL response on the child's provider.
     */
    @Nullable
    private Repository resolveParentRepository(GHIssue parentGraphQl, Repository childRepository) {
        String nameWithOwner = parentRepositoryPath(parentGraphQl);
        if (nameWithOwner == null) {
            return null;
        }

        return repositoryRepository
                .findByNameWithOwnerAndProviderId(
                        nameWithOwner,
                        Objects.requireNonNull(childRepository.getProvider().getId()))
                .orElse(null);
    }

    @Nullable
    private static String parentRepositoryPath(GHIssue parentGraphQl) {
        if (parentGraphQl.getRepository() == null) {
            return null;
        }
        String path = parentGraphQl.getRepository().getNameWithOwner();
        return path == null || path.isBlank() ? null : path;
    }

    /**
     * Process sub-issues summary for an issue.
     * <p>
     * Also implements find-or-create for the issue itself.
     */
    private void processSubIssuesSummary(GHIssue graphQlIssue, Repository repository, Long scopeId) {
        GHSubIssuesSummary summary = graphQlIssue.getSubIssuesSummary();
        if (summary == null) {
            return;
        }

        if (graphQlIssue.getFullDatabaseId() == null) {
            return;
        }

        // Find or create the issue
        Issue issue = findOrCreateIssue(graphQlIssue, repository, scopeId);
        if (issue == null) {
            return;
        }

        if (updateParentSummary(
                issue,
                new SubIssuesSummaryDTO(summary.getTotal(), summary.getCompleted(), summary.getPercentCompleted()))) {
            publishProgress(issue, syncContext(issue, scopeId));
        }

        log.debug(
                "Updated issue sub-issues summary: issueNumber={}, completedCount={}, totalCount={}",
                issue.getNumber(),
                summary.getCompleted(),
                summary.getTotal());
    }
}
