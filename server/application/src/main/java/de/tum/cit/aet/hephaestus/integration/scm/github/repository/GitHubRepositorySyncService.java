package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReadersRead;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.StaleRepositoryMetadataException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore.Observation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore.Result;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlErrorUtils;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser.RepositoryOwnerAndName;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHOrganization;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepositoryOwner;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUser;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Service for syncing GitHub repository metadata via GraphQL API.
 * <p>
 * This service fetches repository information from GitHub and creates/updates
 * the corresponding Repository entity in the database.
 */
@Service
public class GitHubRepositorySyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubRepositorySyncService.class);

    private static final String QUERY_DOCUMENT = "GetRepository";

    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final RepositoryObservationStore observations;
    private final OrganizationRepository organizationRepository;
    private final GitHubSyncProperties syncProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private final WebClient restClient;
    private final TransactionTemplate transactionTemplate;
    // Absent in a role without the workspace filter, and such a role syncs no workspace's repository.
    private final List<RepositoryScopeFilter> scopeFilters;
    private final List<SyncTargetProvider> syncTargets;
    /** Counts the GitHub timeouts; a test passes a virtual clock, so no wall time decides its outcome. */
    private final Scheduler timeouts;

    @Autowired
    public GitHubRepositorySyncService(
            GitHubGraphQlClientProvider graphQlClientProvider,
            RepositoryObservationStore observations,
            OrganizationRepository organizationRepository,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper,
            @Qualifier("gitHubGraphQlWebClient") WebClient webClient,
            TransactionTemplate transactionTemplate,
            List<RepositoryScopeFilter> scopeFilters,
            List<SyncTargetProvider> syncTargets) {
        this(
                graphQlClientProvider,
                observations,
                organizationRepository,
                syncProperties,
                exceptionClassifier,
                graphQlSyncHelper,
                webClient,
                transactionTemplate,
                scopeFilters,
                syncTargets,
                Schedulers.parallel());
    }

    GitHubRepositorySyncService(
            GitHubGraphQlClientProvider graphQlClientProvider,
            RepositoryObservationStore observations,
            OrganizationRepository organizationRepository,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper,
            WebClient webClient,
            TransactionTemplate transactionTemplate,
            List<RepositoryScopeFilter> scopeFilters,
            List<SyncTargetProvider> syncTargets,
            Scheduler timeouts) {
        this.timeouts = timeouts;
        this.scopeFilters = List.copyOf(scopeFilters);
        this.syncTargets = List.copyOf(syncTargets);
        this.restClient = webClient.mutate().baseUrl("https://api.github.com").build();
        this.graphQlClientProvider = graphQlClientProvider;
        this.observations = observations;
        this.organizationRepository = organizationRepository;
        this.syncProperties = syncProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.graphQlSyncHelper = graphQlSyncHelper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Syncs a single repository from GitHub.
     *
     * @param scopeId the scope ID for authentication
     * @param nameWithOwner the full repository name (owner/repo)
     * @param provider the IdentityProvider entity representing the GitHub provider instance
     * @param expectedNativeId the monitored identity, or null until it has been captured
     * @param read the repository snapshot taken before asking GitHub
     * @return the synced repository, or empty on a transient or incomplete response
     */
    public Optional<Repository> syncRepository(
            Long scopeId,
            String nameWithOwner,
            IdentityProvider provider,
            @Nullable Long expectedNativeId,
            ReadersRead read) {
        Objects.requireNonNull(read);
        return syncRepositoryWithRetry(scopeId, nameWithOwner, provider, expectedNativeId, read, 0);
    }

    /** Resolves a renamed repository through its stable REST id using this scope's credential. */
    public String resolveRepositoryNameById(Long scopeId, long nativeId) {
        try {
            var identity = restClient
                    .get()
                    .uri("/repositories/{id}", nativeId)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .headers(headers -> headers.setBearerAuth(graphQlClientProvider.getToken(scopeId)))
                    .retrieve()
                    .bodyToMono(RepositoryIdentity.class)
                    .timeout(syncProperties.graphqlTimeout(), timeouts)
                    .block();
            if (identity == null
                    || identity.id() != nativeId
                    || identity.fullName() == null
                    || identity.fullName().isBlank()) {
                throw new IllegalStateException("Incomplete repository identity response");
            }
            return identity.fullName();
        } catch (WebClientResponseException e) {
            var category = exceptionClassifier.classifyWithDetails(e).category();
            if (category == GitHubExceptionClassifier.Category.NOT_FOUND
                    || category == GitHubExceptionClassifier.Category.AUTH_ERROR) {
                throw new RepositoryNotFoundOnGitProviderException(Long.toString(nativeId), e);
            }
            throw e;
        }
    }

    private static boolean isRepositoryAccessRefusal(ClientGraphQlResponse response) {
        if (GitHubGraphQlErrorUtils.detectTransientError(response) != null) return false;
        var errors = response.getErrors();
        for (int index = 0; index < errors.size(); index++) {
            String type = GitHubGraphQlErrorUtils.errorType(response, index);
            List<Object> path = errors.get(index).getParsedPath();
            if (type != null
                    && Set.of("NOT_FOUND", "FORBIDDEN", "UNAUTHORIZED").contains(type)
                    && (path.isEmpty() || path.equals(List.of("repository")))) {
                // A pathless refusal applies to this single-repository query; it does not prove deletion.
                return true;
            }
        }
        return false;
    }

    private record RepositoryIdentity(
            long id, @JsonProperty("full_name") @Nullable String fullName) {}

    /**
     * Internal implementation with retry counter to prevent infinite recursion.
     */
    private Optional<Repository> syncRepositoryWithRetry(
            Long scopeId,
            String nameWithOwner,
            IdentityProvider provider,
            @Nullable Long expectedNativeId,
            ReadersRead read,
            int retryAttempt) {
        String safeNameWithOwner = Objects.requireNonNull(sanitizeForLog(nameWithOwner));
        Optional<RepositoryOwnerAndName> parsedName = GitHubRepositoryNameParser.parse(nameWithOwner);
        if (parsedName.isEmpty()) {
            log.warn(
                    "Skipped repository sync: reason=invalidNameFormat, scopeId={}, repoName={}",
                    scopeId,
                    safeNameWithOwner);
            return Optional.empty();
        }
        String repoOwner = parsedName.get().owner();
        String repoName = parsedName.get().name();

        try {
            HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
            ClientGraphQlResponse response = Mono.defer(() -> client.documentName(QUERY_DOCUMENT)
                            .variable("owner", repoOwner)
                            .variable("name", repoName)
                            .execute())
                    .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                            .maxBackoff(TRANSPORT_MAX_BACKOFF)
                            .jitter(JITTER_FACTOR)
                            .filter(ScmTransportErrors::isTransportError)
                            .doBeforeRetry(signal -> log.warn(
                                    "Retrying repository sync after transport error: repoName={}, attempt={}, error={}",
                                    safeNameWithOwner,
                                    signal.totalRetries() + 1,
                                    signal.failure().getClass().getSimpleName())))
                    .timeout(syncProperties.graphqlTimeout(), timeouts)
                    .block();

            if (response == null || !response.isValid() || !response.getErrors().isEmpty()) {
                ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(response);
                if (classification != null) {
                    if ((classification.category() == GitHubExceptionClassifier.Category.NOT_FOUND
                                    || classification.category() == GitHubExceptionClassifier.Category.AUTH_ERROR)
                            && response != null
                            && response.field("repository").getValue() == null
                            && isRepositoryAccessRefusal(response)) {
                        throw new RepositoryNotFoundOnGitProviderException(nameWithOwner);
                    }
                    if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                            classification,
                            retryAttempt,
                            MAX_RETRY_ATTEMPTS,
                            "repository sync",
                            "repoName",
                            safeNameWithOwner,
                            log))) {
                        return syncRepositoryWithRetry(
                                scopeId, nameWithOwner, provider, expectedNativeId, read, retryAttempt + 1);
                    }
                    return Optional.empty();
                }
                log.warn(
                        "Failed to fetch repository: scopeId={}, repoName={}, errors={}",
                        scopeId,
                        safeNameWithOwner,
                        response != null ? response.getErrors() : "null response");
                return Optional.empty();
            }

            // Track rate limit from response
            graphQlClientProvider.trackRateLimit(scopeId, response);

            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                        scopeId, "repository sync", "repoName", safeNameWithOwner, log)) {
                    return Optional.empty();
                }
            }

            // Use typed GraphQL model for type-safe parsing
            var repoData = response.field("repository").toEntity(GHRepository.class);
            if (repoData == null) {
                // A missing repository can also mean lost access. Preserve that distinction from
                // a failed request so callers can pause retries without deleting retained work.
                log.warn(
                        "Skipped repository sync: reason=notFoundOnGitHub, scopeId={}, repoName={}",
                        scopeId,
                        safeNameWithOwner);
                throw new RepositoryNotFoundOnGitProviderException(nameWithOwner);
            }

            // Create or update repository using typed accessors
            Long githubDatabaseId =
                    repoData.getDatabaseId() != null ? repoData.getDatabaseId().longValue() : null;
            if (githubDatabaseId == null) {
                log.warn(
                        "Skipped repository sync: reason=missingDatabaseId, scopeId={}, repoName={}",
                        scopeId,
                        safeNameWithOwner);
                return Optional.empty();
            }

            if (expectedNativeId != null && !expectedNativeId.equals(githubDatabaseId)) {
                throw new RepositoryIdentityMismatchException(nameWithOwner, expectedNativeId, githubDatabaseId);
            }

            // GitHub answers with the current name, which a rename may have turned into a repository no workspace may
            // hold. It is checked here, before anything of it is stored.
            String answeredName = repoData.getNameWithOwner();
            if (answeredName != null
                    && scopeFilters.stream().anyMatch(filter -> !filter.isRepositoryAllowed(answeredName))) {
                syncTargets.forEach(targets -> targets.reconcileSyncTargetsForRepository(
                        Objects.requireNonNull(provider.getId()), githubDatabaseId, answeredName));
                log.info(
                        "Skipped repository sync: reason=repositoryNotAllowed, scopeId={}, repoId={}",
                        scopeId,
                        githubDatabaseId);
                return Optional.empty();
            }

            // GitHub has answered: the write is its own short transaction, so no connection waits on GitHub.
            Result result = Objects.requireNonNull(
                    transactionTemplate.execute(status -> store(provider, repoData, githubDatabaseId, read)));
            if (!result.applied()) throw new StaleRepositoryMetadataException();
            Repository repository = Objects.requireNonNull(result.repository());
            log.info(
                    "Synced repository: scopeId={}, repoId={}, repoName={}",
                    scopeId,
                    repository.getId(),
                    safeNameWithOwner);

            return Optional.of(repository);
        } catch (InstallationNotFoundException
                | RepositoryNotFoundOnGitProviderException
                | RepositoryIdentityMismatchException
                | StaleRepositoryMetadataException e) {
            throw e;
        } catch (DataAccessException | TransactionException e) {
            // A failed write is not a GitHub error: the caller records it and the next sync stores the repository.
            throw e;
        } catch (Exception e) {
            ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
            if (classification.category() == GitHubExceptionClassifier.Category.NOT_FOUND
                    || classification.category() == GitHubExceptionClassifier.Category.AUTH_ERROR) {
                throw new RepositoryNotFoundOnGitProviderException(nameWithOwner, e);
            }
            if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                    classification,
                    retryAttempt,
                    MAX_RETRY_ATTEMPTS,
                    "repository sync",
                    "repoName",
                    safeNameWithOwner,
                    log))) {
                return syncRepositoryWithRetry(
                        scopeId, nameWithOwner, provider, expectedNativeId, read, retryAttempt + 1);
            }
            return Optional.empty();
        }
    }

    /** Creates or updates the repository and its organization from GitHub's answer. */
    private Result store(IdentityProvider provider, GHRepository repoData, long githubDatabaseId, ReadersRead read) {
        long providerId = Objects.requireNonNull(provider.getId());
        if (read.repositoryNativeId() != githubDatabaseId || read.providerId() != providerId) {
            return new Result(false, null);
        }
        Long organizationId = ensureOrganization(repoData.getOwner(), provider);
        Repository.Visibility visibility = repoData.getIsPrivate() || repoData.getVisibility() == null
                ? Repository.Visibility.PRIVATE
                : Repository.Visibility.valueOf(repoData.getVisibility().name());
        var patch = RepositoryObservationStore.path(
                repoData.getNameWithOwner(),
                repoData.getName(),
                Objects.requireNonNull(repoData.getUrl()).toString());
        patch.put("description", repoData.getDescription());
        patch.put("organization_id", organizationId);
        patch.put("is_archived", repoData.getIsArchived());
        patch.put("is_disabled", repoData.getIsDisabled());
        if (repoData.getCreatedAt() != null)
            patch.put("created_at", repoData.getCreatedAt().toInstant().toString());
        if (repoData.getUpdatedAt() != null)
            patch.put("updated_at", repoData.getUpdatedAt().toInstant().toString());
        if (repoData.getPushedAt() != null)
            patch.put("pushed_at", repoData.getPushedAt().toInstant().toString());
        var branch = repoData.getDefaultBranchRef();
        patch.put("default_branch", branch == null ? "main" : branch.getName());
        patch.put("last_sync_at", read.requestedAt().toString());
        return observations.observe(read, Observation.read(read, visibility, patch));
    }

    /**
     * Ensures the organization exists, creating it if necessary.
     * Uses PostgreSQL upsert for thread-safe concurrent access.
     */
    @Nullable
    private Long ensureOrganization(GHRepositoryOwner owner, IdentityProvider provider) {
        if (owner == null) {
            return null;
        }

        // GHRepositoryOwner can be GHOrganization or GHUser
        // Only handle Organization type for organization linking
        if (owner instanceof GHOrganization graphQlOrg) {
            Integer dbId = graphQlOrg.getDatabaseId();
            if (dbId == null) {
                return null;
            }

            Long databaseId = dbId.longValue();
            String login = graphQlOrg.getLogin();
            String name = graphQlOrg.getName() != null ? graphQlOrg.getName() : login;
            String url = graphQlOrg.getUrl() != null ? graphQlOrg.getUrl().toString() : null;
            String avatarUrl = graphQlOrg.getAvatarUrl() != null
                    ? graphQlOrg.getAvatarUrl().toString()
                    : null;

            // Use upsert for thread-safe concurrent inserts
            organizationRepository.upsert(
                    databaseId, Objects.requireNonNull(provider.getId()), login, name, avatarUrl, url);
            return organizationRepository
                    .findIdByNativeIdAndProviderId(databaseId, Objects.requireNonNull(provider.getId()))
                    .orElse(null);
        } else if (owner instanceof GHUser graphQlUser) {
            // User repositories - create a "virtual" organization from the user
            Integer dbId = graphQlUser.getDatabaseId();
            if (dbId == null) {
                return null;
            }

            Long databaseId = dbId.longValue();
            String login = graphQlUser.getLogin();
            String name = graphQlUser.getName() != null ? graphQlUser.getName() : login;
            String url = graphQlUser.getUrl() != null ? graphQlUser.getUrl().toString() : null;
            String avatarUrl = graphQlUser.getAvatarUrl() != null
                    ? graphQlUser.getAvatarUrl().toString()
                    : null;

            // Use upsert for thread-safe concurrent inserts
            organizationRepository.upsert(
                    databaseId, Objects.requireNonNull(provider.getId()), login, name, avatarUrl, url);
            return organizationRepository
                    .findIdByNativeIdAndProviderId(databaseId, Objects.requireNonNull(provider.getId()))
                    .orElse(null);
        }

        return null;
    }
}
