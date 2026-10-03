package de.tum.cit.aet.hephaestus.integration.scm.github.milestone;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.LARGE_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
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
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHMilestone;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHMilestoneConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto.GitHubMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service for synchronizing GitHub milestones via GraphQL API.
 * <p>
 * This service fetches milestones via GraphQL and delegates to {@link GitHubMilestoneProcessor}
 * for persistence, ensuring a single source of truth for milestone processing logic.
 */
@Service
public class GitHubMilestoneSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubMilestoneSyncService.class);
    private static final String GET_MILESTONES_DOCUMENT = "GetRepositoryMilestones";

    private final MilestoneRepository milestoneRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubMilestoneProcessor milestoneProcessor;
    private final GitHubSyncProperties syncProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    public GitHubMilestoneSyncService(
            MilestoneRepository milestoneRepository,
            RepositoryRepository repositoryRepository,
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubMilestoneProcessor milestoneProcessor,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper) {
        this.milestoneRepository = milestoneRepository;
        this.repositoryRepository = repositoryRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.milestoneProcessor = milestoneProcessor;
        this.syncProperties = syncProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.graphQlSyncHelper = graphQlSyncHelper;
    }

    /**
     * Synchronizes all milestones for a repository using GraphQL.
     *
     * @param scopeId  the scope ID for authentication
     * @param repositoryId the repository ID to sync milestones for
     * @return number of milestones synced
     */
    @Transactional
    public int syncMilestonesForRepository(Long scopeId, Long repositoryId) {
        Repository repository = repositoryRepository.findById(repositoryId).orElse(null);
        if (repository == null) {
            log.debug("Skipped milestone sync: reason=repositoryNotFound, repoId={}", repositoryId);
            return 0;
        }

        String safeNameWithOwner =
                Objects.requireNonNullElse(sanitizeForLog(repository.getNameWithOwner()), "<unknown>");
        Optional<RepositoryOwnerAndName> parsedName = GitHubRepositoryNameParser.parse(repository.getNameWithOwner());
        if (parsedName.isEmpty()) {
            log.warn("Skipped milestone sync: reason=invalidRepoNameFormat, repoName={}", safeNameWithOwner);
            return 0;
        }
        String owner = parsedName.get().owner();
        String name = parsedName.get().name();

        try {
            HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
            ProcessingContext context = ProcessingContext.forSync(scopeId, repository);
            Set<Integer> syncedNumbers = new HashSet<>();
            int totalSynced = 0;
            int milestonesReceived = 0;
            int reportedTotalCount = -1;
            String cursor = null;
            boolean hasNextPage = true;
            int pageCount = 0;
            int retryAttempt = 0;

            while (hasNextPage) {
                pageCount++;
                if (pageCount >= MAX_PAGINATION_PAGES) {
                    log.warn(
                            "Reached maximum pagination limit for milestones: repoName={}, limit={}",
                            safeNameWithOwner,
                            MAX_PAGINATION_PAGES);
                    break;
                }

                final String currentCursor = cursor;
                final int currentPage = pageCount;
                ClientGraphQlResponse graphQlResponse = Mono.defer(() -> client.documentName(GET_MILESTONES_DOCUMENT)
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
                                        "Retrying milestone sync after transport error: repoName={}, page={}, attempt={}, error={}",
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
                                "milestone sync",
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
                            scopeId, "milestone sync", "repoName", safeNameWithOwner, log)) {
                        break;
                    }
                }

                GHMilestoneConnection response =
                        graphQlResponse.field("repository.milestones").toEntity(GHMilestoneConnection.class);

                if (response == null || response.getNodes() == null) {
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = response.getTotalCount();
                }
                milestonesReceived += response.getNodes().size();

                for (var graphQlMilestone : response.getNodes()) {
                    GitHubMilestoneDTO dto = convertToDTO(graphQlMilestone);
                    GitHubUserDTO creatorDto = GitHubUserDTO.fromActor(graphQlMilestone.getCreator());
                    Milestone milestone = milestoneProcessor.process(dto, repository, creatorDto, context);
                    if (milestone != null) {
                        syncedNumbers.add(milestone.getNumber());
                        totalSynced++;
                    }
                }

                var pageInfo = response.getPageInfo();
                hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
                retryAttempt = 0;
            }

            // Mark sync as completed normally if we exhausted all pages
            boolean syncCompletedNormally = !hasNextPage;

            // Raw nodes received vs milestones.totalCount (totalSynced is post-filter).
            if (reportedTotalCount >= 0) {
                GraphQlConnectionOverflowDetector.checkPaginated(
                        "milestones",
                        milestonesReceived,
                        reportedTotalCount,
                        !syncCompletedNormally,
                        safeNameWithOwner);
            }

            // CRITICAL: Only remove stale milestones if sync completed fully.
            // If sync was aborted (rate limit, error, pagination limit), we don't have
            // the complete list and would incorrectly delete valid milestones.
            if (syncCompletedNormally) {
                removeDeletedMilestones(repository.getId(), syncedNumbers, context);
            } else {
                log.warn(
                        "Skipped stale milestone removal: reason=incompleteSync, repoName={}, pagesProcessed={}",
                        safeNameWithOwner,
                        pageCount);
            }

            log.info(
                    "Completed milestone sync: repoName={}, milestoneCount={}, complete={}, scopeId={}",
                    safeNameWithOwner,
                    totalSynced,
                    syncCompletedNormally,
                    scopeId);
            return totalSynced;
        } catch (InstallationNotFoundException e) {
            // Re-throw to abort the entire sync operation
            throw e;
        } catch (Exception e) {
            ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
            if (!graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                    classification, 0, MAX_RETRY_ATTEMPTS, "milestone sync", "repoName", safeNameWithOwner, log))) {
                return 0;
            }
            return 0;
        }
    }

    private void removeDeletedMilestones(Long repositoryId, Set<Integer> syncedNumbers, ProcessingContext context) {
        List<Milestone> existing = milestoneRepository.findAllByRepository_Id(repositoryId);
        int removed = 0;
        for (Milestone milestone : existing) {
            if (!syncedNumbers.contains(milestone.getNumber())) {
                milestoneProcessor.delete(milestone.getNativeId(), context);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Removed stale milestones: removedCount={}, repoId={}", removed, repositoryId);
        }
    }

    /**
     * Converts a GraphQL GHMilestone to a GitHubMilestoneDTO.
     * Note: GraphQL doesn't expose databaseId for milestones, so id will be null.
     * The processor handles this by using number-based lookup as fallback.
     */
    private GitHubMilestoneDTO convertToDTO(GHMilestone graphQlMilestone) {
        return Objects.requireNonNull(GitHubMilestoneDTO.fromMilestone(graphQlMilestone));
    }
}
