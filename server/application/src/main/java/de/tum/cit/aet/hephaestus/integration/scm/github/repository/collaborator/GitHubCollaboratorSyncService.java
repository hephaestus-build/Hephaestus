package de.tum.cit.aet.hephaestus.integration.scm.github.repository.collaborator;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.LARGE_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser.RepositoryOwnerAndName;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepositoryCollaboratorConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepositoryCollaboratorEdge;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepositoryPermission;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
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
 * Service for synchronizing GitHub repository collaborators via GraphQL API.
 * <p>
 * This service fetches collaborators via GraphQL and uses {@link GitHubUserProcessor}
 * to persist users, ensuring a single source of truth for user processing logic.
 */
@Service
public class GitHubCollaboratorSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubCollaboratorSyncService.class);
    private static final String GET_COLLABORATORS_DOCUMENT = "GetRepositoryCollaborators";

    private final RepositoryRepository repositoryRepository;
    private final RepositoryCollaboratorRepository collaboratorRepository;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubUserProcessor userProcessor;
    private final GitHubSyncProperties syncProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    public GitHubCollaboratorSyncService(
            RepositoryRepository repositoryRepository,
            RepositoryCollaboratorRepository collaboratorRepository,
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubUserProcessor userProcessor,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper) {
        this.repositoryRepository = repositoryRepository;
        this.collaboratorRepository = collaboratorRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.userProcessor = userProcessor;
        this.syncProperties = syncProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.graphQlSyncHelper = graphQlSyncHelper;
    }

    /**
     * Synchronizes all collaborators for a repository using GraphQL.
     *
     * @param scopeId  the scope ID for authentication
     * @param repositoryId the repository ID to sync collaborators for
     * @return number of collaborators synced
     */
    @Transactional
    public int syncCollaboratorsForRepository(Long scopeId, Long repositoryId) {
        Repository repository = repositoryRepository.findById(repositoryId).orElse(null);
        if (repository == null) {
            log.debug("Skipped collaborator sync: reason=repositoryNotFound, repoId={}", repositoryId);
            return 0;
        }

        String safeNameWithOwner = Objects.requireNonNull(sanitizeForLog(repository.getNameWithOwner()));
        Optional<RepositoryOwnerAndName> parsedName = GitHubRepositoryNameParser.parse(repository.getNameWithOwner());
        if (parsedName.isEmpty()) {
            log.warn("Skipped collaborator sync: reason=invalidRepoNameFormat, repoName={}", safeNameWithOwner);
            return 0;
        }
        String owner = parsedName.get().owner();
        String name = parsedName.get().name();

        try {
            HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
            int totalSynced = 0;
            int collaboratorsReceived = 0;
            int reportedTotalCount = -1;
            String cursor = null;
            boolean hasNextPage = true;
            Set<Long> syncedUserIds = new HashSet<>();
            int pageCount = 0;
            int retryAttempt = 0;

            while (hasNextPage) {
                pageCount++;
                if (pageCount >= MAX_PAGINATION_PAGES) {
                    log.warn(
                            "Reached maximum pagination limit for collaborators: repoName={}, limit={}",
                            safeNameWithOwner,
                            MAX_PAGINATION_PAGES);
                    break;
                }

                final String currentCursor = cursor;
                final int currentPage = pageCount;

                ClientGraphQlResponse graphQlResponse = Mono.defer(() -> client.documentName(GET_COLLABORATORS_DOCUMENT)
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
                                        "Retrying after transport error: context=collaboratorSync, repoName={}, page={}, attempt={}, error={}",
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
                                "collaborator sync",
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
                            scopeId, "collaborator sync", "repoName", safeNameWithOwner, log)) {
                        break;
                    }
                }

                GHRepositoryCollaboratorConnection response = graphQlResponse
                        .field("repository.collaborators")
                        .toEntity(GHRepositoryCollaboratorConnection.class);

                if (response == null || response.getEdges() == null) {
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = response.getTotalCount();
                }
                collaboratorsReceived += response.getEdges().size();

                for (GHRepositoryCollaboratorEdge edge : response.getEdges()) {
                    var graphQlUser = edge.getNode();
                    if (graphQlUser == null) {
                        continue;
                    }

                    // Convert GraphQL User to DTO and upsert
                    GitHubUserDTO userDTO = GitHubUserDTO.fromUser(graphQlUser);
                    if (userDTO == null) {
                        continue;
                    }
                    User user = userProcessor.findOrCreate(
                            userDTO,
                            Objects.requireNonNull(repository.getProvider().getId()));
                    if (user == null) {
                        continue;
                    }

                    // Get permission from edge
                    RepositoryCollaborator.Permission permission = parsePermission(edge.getPermission());

                    // Upsert collaborator relationship
                    upsertCollaborator(repository, user, permission);
                    syncedUserIds.add(user.getId());
                    totalSynced++;
                }

                var pageInfo = response.getPageInfo();
                hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
                retryAttempt = 0;
            }

            // Mark sync as completed normally if we exhausted all pages
            boolean syncCompletedNormally = !hasNextPage;

            // Raw edges received vs collaborators.totalCount (totalSynced is post-filter).
            if (reportedTotalCount >= 0) {
                GraphQlConnectionOverflowDetector.checkPaginated(
                        "collaborators",
                        collaboratorsReceived,
                        reportedTotalCount,
                        !syncCompletedNormally,
                        safeNameWithOwner);
            }

            // CRITICAL: Only remove stale collaborators if sync completed fully.
            // If sync was aborted (rate limit, error, pagination limit), we don't have
            // the complete list and would incorrectly delete valid collaborators.
            int removedCount = 0;
            if (syncCompletedNormally) {
                removedCount = removeStaleCollaborators(repositoryId, syncedUserIds);
            } else {
                log.warn(
                        "Skipped stale collaborator removal: reason=incompleteSync, repoName={}, pagesProcessed={}",
                        safeNameWithOwner,
                        pageCount);
            }

            log.info(
                    "Completed collaborator sync: repoName={}, collaboratorCount={}, removedCount={}, complete={}",
                    safeNameWithOwner,
                    totalSynced,
                    removedCount,
                    syncCompletedNormally);
            return totalSynced;
        } catch (InstallationNotFoundException e) {
            // Re-throw to abort the entire sync operation
            throw e;
        } catch (Exception e) {
            ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
            if (!graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                    classification, 0, MAX_RETRY_ATTEMPTS, "collaborator sync", "repoName", safeNameWithOwner, log))) {
                return 0;
            }
            return 0;
        }
    }

    /**
     * Upserts a collaborator relationship between a repository and a user.
     *
     * @param repository the repository
     * @param user       the user
     * @param permission the permission level
     */
    private void upsertCollaborator(Repository repository, User user, RepositoryCollaborator.Permission permission) {
        Optional<RepositoryCollaborator> existing =
                collaboratorRepository.findByRepositoryIdAndUserId(repository.getId(), user.getId());

        if (existing.isPresent()) {
            // Update permission if changed
            RepositoryCollaborator collaborator = existing.get();
            collaborator.updatePermission(permission);
            collaboratorRepository.save(collaborator);
        } else {
            // Create new collaborator
            RepositoryCollaborator collaborator = new RepositoryCollaborator(repository, user, permission);
            collaboratorRepository.save(collaborator);
        }
    }

    /**
     * Parses a GraphQL GHRepositoryPermission to the entity Permission enum.
     *
     * @param permission the GraphQL permission enum
     * @return the entity Permission enum
     */
    private RepositoryCollaborator.Permission parsePermission(GHRepositoryPermission permission) {
        if (permission == null) {
            return RepositoryCollaborator.Permission.UNKNOWN;
        }
        return RepositoryCollaborator.Permission.fromGitHubValue(permission.name());
    }

    /**
     * Removes collaborators from the local database that no longer exist on GitHub.
     *
     * @param repositoryId   the repository ID
     * @param syncedUserIds  the set of user IDs that were synced from GitHub
     * @return number of stale collaborators removed
     */
    private int removeStaleCollaborators(Long repositoryId, Set<Long> syncedUserIds) {
        List<RepositoryCollaborator> existingCollaborators = collaboratorRepository.findByRepository_Id(repositoryId);
        int removedCount = 0;

        for (RepositoryCollaborator existing : existingCollaborators) {
            // If user ID was not in the sync results, it's stale
            if (!syncedUserIds.contains(existing.getUser().getId())) {
                collaboratorRepository.delete(existing);
                removedCount++;
                log.debug(
                        "Removed stale collaborator: repoId={}, userId={}, userLogin={}",
                        repositoryId,
                        existing.getUser().getId(),
                        sanitizeForLog(existing.getUser().getLogin()));
            }
        }

        return removedCount;
    }
}
