package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
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
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHOrganization;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepositoryOwner;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUser;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
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
    private final RepositoryRepository repositoryRepository;
    private final OrganizationRepository organizationRepository;
    private final GitHubSyncProperties syncProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private final WebClient restClient;

    public GitHubRepositorySyncService(
            GitHubGraphQlClientProvider graphQlClientProvider,
            RepositoryRepository repositoryRepository,
            OrganizationRepository organizationRepository,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper,
            @Qualifier("gitHubGraphQlWebClient") WebClient webClient) {
        this.restClient = webClient.mutate().baseUrl("https://api.github.com").build();
        this.graphQlClientProvider = graphQlClientProvider;
        this.repositoryRepository = repositoryRepository;
        this.organizationRepository = organizationRepository;
        this.syncProperties = syncProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.graphQlSyncHelper = graphQlSyncHelper;
    }

    /**
     * Syncs a single repository from GitHub.
     *
     * @param scopeId the scope ID for authentication
     * @param nameWithOwner the full repository name (owner/repo)
     * @param provider the IdentityProvider entity representing the GitHub provider instance
     * @return the synced repository, or empty on a transient or incomplete response
     */
    @Transactional
    public Optional<Repository> syncRepository(Long scopeId, String nameWithOwner, IdentityProvider provider) {
        return syncRepositoryWithRetry(scopeId, nameWithOwner, provider, 0);
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
                    .block(syncProperties.graphqlTimeout());
            if (identity == null
                    || identity.id() != nativeId
                    || identity.fullName() == null
                    || identity.fullName().isBlank()) {
                throw new IllegalStateException("Incomplete repository identity response");
            }
            return identity.fullName();
        } catch (WebClientResponseException.NotFound e) {
            throw new RepositoryNotFoundOnGitProviderException(Long.toString(nativeId), e);
        }
    }

    private record RepositoryIdentity(
            long id, @JsonProperty("full_name") @Nullable String fullName) {}

    /**
     * Internal implementation with retry counter to prevent infinite recursion.
     */
    private Optional<Repository> syncRepositoryWithRetry(
            Long scopeId, String nameWithOwner, IdentityProvider provider, int retryAttempt) {
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
                                    signal.failure().getMessage())))
                    .block(syncProperties.graphqlTimeout());

            if (response == null || !response.isValid()) {
                ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(response);
                if (classification != null) {
                    if (classification.category() == GitHubExceptionClassifier.Category.NOT_FOUND) {
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
                        return syncRepositoryWithRetry(scopeId, nameWithOwner, provider, retryAttempt + 1);
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

            // Ensure organization exists
            GHRepositoryOwner owner = repoData.getOwner();
            Organization organization = ensureOrganization(owner, provider);

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

            Repository repository = repositoryRepository
                    .findByNativeIdAndProviderId(githubDatabaseId, Objects.requireNonNull(provider.getId()))
                    .orElseGet(Repository::new);

            repository.setNativeId(githubDatabaseId);
            repository.setProvider(provider);
            repository.setName(repoData.getName());
            repository.setNameWithOwner(repoData.getNameWithOwner());
            repository.setDescription(repoData.getDescription());
            repository.setHtmlUrl(Objects.requireNonNull(repoData.getUrl()).toString());
            repository.setOrganization(organization);

            repository.setPrivate(repoData.getIsPrivate());
            repository.setArchived(repoData.getIsArchived());
            repository.setDisabled(repoData.getIsDisabled());
            repository.setHasDiscussionsEnabled(repoData.getHasDiscussionsEnabled());

            if (repoData.getCreatedAt() != null) {
                repository.setCreatedAt(repoData.getCreatedAt().toInstant());
            }
            if (repoData.getUpdatedAt() != null) {
                repository.setUpdatedAt(repoData.getUpdatedAt().toInstant());
            }
            if (repoData.getPushedAt() != null) {
                repository.setPushedAt(repoData.getPushedAt().toInstant());
            }

            if (repoData.getDefaultBranchRef() != null) {
                repository.setDefaultBranch(repoData.getDefaultBranchRef().getName());
            } else {
                repository.setDefaultBranch("main");
            }

            if (repoData.getVisibility() != null) {
                repository.setVisibility(
                        Repository.Visibility.valueOf(repoData.getVisibility().name()));
            } else {
                repository.setVisibility(Repository.Visibility.PRIVATE);
            }

            repository.setLastSyncAt(Instant.now());

            repository = repositoryRepository.save(repository);
            log.info(
                    "Synced repository: scopeId={}, repoId={}, repoName={}",
                    scopeId,
                    repository.getId(),
                    safeNameWithOwner);

            return Optional.of(repository);
        } catch (InstallationNotFoundException | RepositoryNotFoundOnGitProviderException e) {
            // Installation failure aborts the scope. Repository absence pauses only its monitor.
            throw e;
        } catch (Exception e) {
            ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
            if (classification.category() == GitHubExceptionClassifier.Category.NOT_FOUND) {
                throw new RepositoryNotFoundOnGitProviderException(nameWithOwner, e);
            }
            graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                    classification,
                    retryAttempt,
                    MAX_RETRY_ATTEMPTS,
                    "repository sync",
                    "repoName",
                    safeNameWithOwner,
                    log));
            return Optional.empty();
        }
    }

    /**
     * Ensures the organization exists, creating it if necessary.
     * Uses PostgreSQL upsert for thread-safe concurrent access.
     */
    @Nullable
    private Organization ensureOrganization(GHRepositoryOwner owner, IdentityProvider provider) {
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
                    .findByNativeIdAndProviderId(databaseId, Objects.requireNonNull(provider.getId()))
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
                    .findByNativeIdAndProviderId(databaseId, Objects.requireNonNull(provider.getId()))
                    .orElse(null);
        }

        return null;
    }
}
