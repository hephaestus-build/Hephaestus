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
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
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
import java.util.ArrayList;
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
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate transactionTemplate;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    public GitHubCollaboratorSyncService(
            RepositoryRepository repositoryRepository,
            RepositoryCollaboratorRepository collaboratorRepository,
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubUserProcessor userProcessor,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper,
            TransactionTemplate transactionTemplate) {
        this.repositoryRepository = repositoryRepository;
        this.collaboratorRepository = collaboratorRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.userProcessor = userProcessor;
        this.syncProperties = syncProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.graphQlSyncHelper = graphQlSyncHelper;
        this.transactionTemplate = transactionTemplate;
    }

    /** One collaborator as GitHub listed them, with the permission the listing named. */
    public record ListedCollaborator(GitHubUserDTO user, RepositoryCollaborator.Permission permission) {}

    /**
     * What one listing of a repository's collaborators answered. Only {@link Outcome#COMPLETE} proves who can read the
     * repository: every page arrived without errors, the cursor moved on, the repository was the one asked about on
     * every page, and the distinct users match GitHub's count.
     */
    public record CollaboratorListing(Outcome outcome, List<ListedCollaborator> collaborators) {

        public enum Outcome {
            COMPLETE,
            RATE_LIMITED,
            INCOMPLETE,
            /** GitHub answered that the repository does not exist or this credential cannot see it. */
            REPOSITORY_MISSING,
            /**
             * GitHub answered that the repository is internal: enterprise members outside its organization can read it,
             * and no listing names them.
             */
            NOT_LISTABLE
        }

        public CollaboratorListing {
            collaborators = List.copyOf(collaborators);
        }

        static CollaboratorListing stopped(Outcome outcome, List<ListedCollaborator> collaborators) {
            return new CollaboratorListing(outcome, collaborators);
        }
    }

    /**
     * Lists every collaborator of a repository through GraphQL, with every affiliation: outside collaborators, direct
     * collaborators and organization members who can read it.
     *
     * @param scopeId the scope ID for authentication
     * @param repositoryId the mirror repository to list
     * @param expectedNativeId GitHub's id of the repository the monitor names; a page about another one stops the listing
     */
    public CollaboratorListing listCollaborators(Long scopeId, Long repositoryId, long expectedNativeId) {
        Repository repository = repositoryRepository.findById(repositoryId).orElse(null);
        if (repository == null) {
            log.debug("Skipped collaborator sync: reason=repositoryNotFound, repoId={}", repositoryId);
            return CollaboratorListing.stopped(CollaboratorListing.Outcome.INCOMPLETE, List.of());
        }

        String safeNameWithOwner = Objects.requireNonNull(sanitizeForLog(repository.getNameWithOwner()));
        Optional<RepositoryOwnerAndName> parsedName = GitHubRepositoryNameParser.parse(repository.getNameWithOwner());
        if (parsedName.isEmpty()) {
            log.warn("Skipped collaborator sync: reason=invalidRepoNameFormat, repoName={}", safeNameWithOwner);
            return CollaboratorListing.stopped(CollaboratorListing.Outcome.INCOMPLETE, List.of());
        }
        String owner = parsedName.get().owner();
        String name = parsedName.get().name();

        List<ListedCollaborator> fetched = new ArrayList<>();
        Set<Long> distinctReaders = new HashSet<>();
        try {
            HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
            int edgesReceived = 0;
            int reportedTotalCount = -1;
            String cursor = null;
            boolean hasNextPage = true;
            int pageCount = 0;
            int retryAttempt = 0;

            while (hasNextPage) {
                pageCount++;
                if (pageCount >= MAX_PAGINATION_PAGES) {
                    log.warn(
                            "Reached maximum pagination limit for collaborators: repoName={}, limit={}",
                            safeNameWithOwner,
                            MAX_PAGINATION_PAGES);
                    return stopped(CollaboratorListing.Outcome.INCOMPLETE, fetched, safeNameWithOwner, "pageLimit");
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
                                        signal.failure().getClass().getSimpleName())))
                        .block(syncProperties.graphqlTimeout());

                // A valid response can still carry field errors, such as a rate limit or a refused repository.
                if (graphQlResponse == null
                        || !graphQlResponse.isValid()
                        || !graphQlResponse.getErrors().isEmpty()) {
                    ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(graphQlResponse);
                    if (classification != null
                            && graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
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
                    if (classification != null && classification.category() == Category.RATE_LIMITED) {
                        return stopped(
                                CollaboratorListing.Outcome.RATE_LIMITED, fetched, safeNameWithOwner, "rateLimited");
                    }
                    if (graphQlResponse != null
                            && graphQlResponse.isValid()
                            && graphQlResponse.field("repository").getValue() == null) {
                        return stopped(
                                CollaboratorListing.Outcome.REPOSITORY_MISSING,
                                fetched,
                                safeNameWithOwner,
                                "repositoryRefused");
                    }
                    log.warn(
                            "Received invalid GraphQL response: repoName={}, errors={}",
                            safeNameWithOwner,
                            graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                    return stopped(
                            CollaboratorListing.Outcome.INCOMPLETE, fetched, safeNameWithOwner, "invalidResponse");
                }

                graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

                if (graphQlResponse.field("repository").getValue() == null) {
                    return stopped(
                            CollaboratorListing.Outcome.REPOSITORY_MISSING,
                            fetched,
                            safeNameWithOwner,
                            "repositoryMissing");
                }
                // A rename or transfer can put another repository behind the name between two pages.
                Object pageRepositoryId =
                        graphQlResponse.field("repository.databaseId").getValue();
                if (!(pageRepositoryId instanceof Number number) || number.longValue() != expectedNativeId) {
                    return stopped(
                            CollaboratorListing.Outcome.INCOMPLETE, List.of(), safeNameWithOwner, "anotherRepository");
                }
                // This read's own answer decides whether a listing can name every reader, not the mirror row.
                if ("INTERNAL"
                        .equals(graphQlResponse.field("repository.visibility").getValue())) {
                    return stopped(CollaboratorListing.Outcome.NOT_LISTABLE, fetched, safeNameWithOwner, "internal");
                }
                // The generated model reads a missing count or page flag as zero or false; only a present one counts.
                Object totalCountValue = graphQlResponse
                        .field("repository.collaborators.totalCount")
                        .getValue();
                Object hasNextPageValue = graphQlResponse
                        .field("repository.collaborators.pageInfo.hasNextPage")
                        .getValue();
                GHRepositoryCollaboratorConnection response = graphQlResponse
                        .field("repository.collaborators")
                        .toEntity(GHRepositoryCollaboratorConnection.class);
                if (response == null
                        || response.getEdges() == null
                        || !(totalCountValue instanceof Number totalCount)
                        || !(hasNextPageValue instanceof Boolean pageHasNext)) {
                    return stopped(
                            CollaboratorListing.Outcome.INCOMPLETE, fetched, safeNameWithOwner, "missingPageFields");
                }
                if (reportedTotalCount < 0) {
                    reportedTotalCount = totalCount.intValue();
                } else if (reportedTotalCount != totalCount.intValue()) {
                    return stopped(CollaboratorListing.Outcome.INCOMPLETE, fetched, safeNameWithOwner, "countChanged");
                }
                edgesReceived += response.getEdges().size();

                boolean everyEdgeNamesAUser = true;
                for (GHRepositoryCollaboratorEdge edge : response.getEdges()) {
                    GitHubUserDTO userDTO = GitHubUserDTO.fromUser(edge.getNode());
                    if (userDTO == null || userDTO.databaseId() == null) {
                        everyEdgeNamesAUser = false;
                        continue;
                    }
                    fetched.add(new ListedCollaborator(userDTO, parsePermission(edge.getPermission())));
                    distinctReaders.add(userDTO.databaseId());
                }
                if (!everyEdgeNamesAUser) {
                    return stopped(
                            CollaboratorListing.Outcome.INCOMPLETE, fetched, safeNameWithOwner, "edgeWithoutUser");
                }

                String endCursor = response.getPageInfo() == null
                        ? null
                        : response.getPageInfo().getEndCursor();
                if (pageHasNext && (endCursor == null || endCursor.equals(cursor))) {
                    return stopped(CollaboratorListing.Outcome.INCOMPLETE, fetched, safeNameWithOwner, "cursorStalled");
                }
                hasNextPage = pageHasNext;
                cursor = endCursor;
                retryAttempt = 0;

                if (hasNextPage
                        && graphQlClientProvider.isRateLimitCritical(scopeId)
                        && !graphQlSyncHelper.waitForRateLimitIfNeeded(
                                scopeId, "collaborator sync", "repoName", safeNameWithOwner, log)) {
                    return stopped(CollaboratorListing.Outcome.RATE_LIMITED, fetched, safeNameWithOwner, "rateLimited");
                }
            }

            GraphQlConnectionOverflowDetector.checkPaginated(
                    "collaborators", edgesReceived, reportedTotalCount, false, safeNameWithOwner);
            // A user listed twice hides a user not listed at all, so the distinct users must match the count.
            if (edgesReceived != reportedTotalCount || distinctReaders.size() != reportedTotalCount) {
                return stopped(CollaboratorListing.Outcome.INCOMPLETE, fetched, safeNameWithOwner, "countMismatch");
            }
            return new CollaboratorListing(CollaboratorListing.Outcome.COMPLETE, fetched);
        } catch (InstallationNotFoundException e) {
            // Re-throw to abort the entire sync operation
            throw e;
        } catch (Exception e) {
            ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
            graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                    classification,
                    MAX_RETRY_ATTEMPTS,
                    MAX_RETRY_ATTEMPTS,
                    "collaborator sync",
                    "repoName",
                    safeNameWithOwner,
                    log));
            return stopped(
                    classification.category() == Category.RATE_LIMITED
                            ? CollaboratorListing.Outcome.RATE_LIMITED
                            : CollaboratorListing.Outcome.INCOMPLETE,
                    fetched,
                    safeNameWithOwner,
                    "exception");
        }
    }

    private static CollaboratorListing stopped(
            CollaboratorListing.Outcome outcome,
            List<ListedCollaborator> fetched,
            String safeNameWithOwner,
            String reason) {
        log.warn(
                "Stopped collaborator listing: outcome={}, reason={}, repoName={}, listed={}",
                outcome,
                reason,
                safeNameWithOwner,
                fetched.size());
        return CollaboratorListing.stopped(outcome, fetched);
    }

    /**
     * Stores the collaborators that a listing found in the shared mirror. Only a complete listing removes the stale
     * ones: an aborted one (rate limit, error, pagination limit) would delete collaborators who still have access.
     *
     * @return the number of collaborators stored
     */
    public int storeCollaborators(Long repositoryId, CollaboratorListing listing) {
        if (listing.outcome() == CollaboratorListing.Outcome.REPOSITORY_MISSING) {
            return 0;
        }
        boolean complete = listing.outcome() == CollaboratorListing.Outcome.COMPLETE;
        // A database failure propagates, so the caller's transient-failure retry gets a fresh transaction.
        CollaboratorWrite written = Objects.requireNonNull(transactionTemplate.execute(
                status -> writeCollaborators(repositoryId, listing.collaborators(), complete)));
        log.info(
                "Completed collaborator sync: repoId={}, collaboratorCount={}, removedCount={}, complete={}",
                repositoryId,
                written.synced(),
                written.removed(),
                complete);
        return written.synced();
    }

    private record CollaboratorWrite(int synced, int removed) {}

    /** Stores the collaborators that GitHub listed, and removes the stale ones if the listing is complete. */
    private CollaboratorWrite writeCollaborators(
            Long repositoryId, List<ListedCollaborator> fetched, boolean complete) {
        Repository repository = repositoryRepository.findById(repositoryId).orElse(null);
        if (repository == null) {
            return new CollaboratorWrite(0, 0);
        }
        long providerId = Objects.requireNonNull(repository.getProvider().getId());
        Set<Long> syncedUserIds = new HashSet<>();
        int synced = 0;
        for (ListedCollaborator entry : fetched) {
            User user = userProcessor.findOrCreate(entry.user(), providerId);
            if (user == null) {
                continue;
            }
            upsertCollaborator(repository, user, entry.permission());
            syncedUserIds.add(user.getId());
            synced++;
        }
        int removed = complete ? removeStaleCollaborators(repositoryId, syncedUserIds) : 0;
        return new CollaboratorWrite(synced, removed);
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
                        "Removed stale collaborator: repoId={}, userId={}",
                        repositoryId,
                        existing.getUser().getId());
            }
        }

        return removedCount;
    }
}
