package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.extractEntityId;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;

/**
 * Service for syncing a single GitLab project via GraphQL API.
 * <p>
 * Fetches project metadata using the {@code GetProject} query, which also
 * includes the parent group. Both the project and its group are persisted
 * in a single transaction.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabProjectSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabProjectSyncService.class);

    private static final String GET_PROJECT_DOCUMENT = "GetProject";
    private static final String GET_PROJECTS_BY_IDS_DOCUMENT = "GetProjectsByIds";
    private static final String PROJECT_GLOBAL_ID_PREFIX = "gid://gitlab/Project/";

    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final GitLabExceptionClassifier exceptionClassifier;
    private final GitLabProjectProcessor projectProcessor;
    private final GitLabGroupProcessor groupProcessor;
    private final GitLabProperties gitLabProperties;
    private final SourceProviderNamespaces providerNamespaces;
    private final GitLabProjectAdmission admission;

    public GitLabProjectSyncService(
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler,
            GitLabProjectProcessor projectProcessor,
            GitLabGroupProcessor groupProcessor,
            GitLabProperties gitLabProperties,
            SourceProviderNamespaces providerNamespaces,
            GitLabExceptionClassifier exceptionClassifier,
            GitLabProjectAdmission admission) {
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
        this.exceptionClassifier = exceptionClassifier;
        this.admission = admission;
        this.projectProcessor = projectProcessor;
        this.groupProcessor = groupProcessor;
        this.gitLabProperties = gitLabProperties;
        this.providerNamespaces = providerNamespaces;
    }

    /**
     * Resolves the GitLab provider entity from the database.
     *
     * @return the GitLab provider
     * @throws IllegalStateException if no GitLab provider is found
     */
    private IdentityProvider resolveProvider() {
        return providerNamespaces
                .findForMirror(IdentityProviderType.GITLAB, gitLabProperties.defaultServerUrl())
                .orElseThrow(() -> new IllegalStateException("IdentityProvider not found for type=GITLAB, serverUrl="
                        + gitLabProperties.defaultServerUrl()));
    }

    /**
     * Syncs a single GitLab project by its full path.
     * <p>
     * Also syncs the parent group (if present) to ensure the Organization
     * entity exists before linking it to the Repository.
     *
     * @param scopeId         the workspace/scope ID for authentication
     * @param projectFullPath the full path of the project (e.g., {@code org/my-project})
     * @return the synced Repository entity, or empty if not found or on error
     */
    public Optional<Repository> syncProject(Long scopeId, @Nullable String projectFullPath) {
        if (projectFullPath == null || projectFullPath.isBlank()) {
            log.warn("Skipped project sync: reason=nullOrBlankProjectPath, scopeId={}", scopeId);
            return Optional.empty();
        }
        try {
            IdentityProvider provider = resolveProvider();
            var read = admission
                    .captureConnection(scopeId, Objects.requireNonNull(provider.getId()))
                    .orElse(null);
            if (read == null) return Optional.empty();
            ClientGraphQlResponse response = query(
                    () -> graphQlClientProvider.forScope(scopeId),
                    GET_PROJECT_DOCUMENT,
                    "fullPath",
                    projectFullPath,
                    "project " + sanitizeForLog(projectFullPath),
                    false);
            return Optional.ofNullable(response.field("project").toEntity(GitLabProjectResponse.class))
                    .flatMap(project -> admission.discover(
                            read,
                            extractEntityId(project.id()),
                            Objects.requireNonNull(project.fullPath()),
                            provider.getServerUrl(),
                            () -> persistProject(project, provider).orElse(null)));
        } catch (GitLabSyncException e) {
            return Optional.empty();
        } catch (Exception e) {
            graphQlClientProvider.recordFailure(e);
            log.error(
                    "Failed to sync project: scopeId={}, projectPath={}", scopeId, sanitizeForLog(projectFullPath), e);
            return Optional.empty();
        }
    }

    /**
     * The project GitLab reports at {@code projectFullPath} to the workspace's own credential. Only reads: empty when
     * GitLab reports no such project, and a response GitLab could not give throws, so a caller can retry.
     */
    public Optional<GitLabProjectResponse> fetchProject(Long scopeId, String projectFullPath) {
        return fetchProject(() -> graphQlClientProvider.forScope(scopeId), projectFullPath);
    }

    /**
     * As {@link #fetchProject(Long, String)}, with a token and server URL the caller read together from one stored
     * connection, so the token is only ever sent to the server it was stored with.
     */
    public Optional<GitLabProjectResponse> fetchProject(
            Long scopeId, String token, String serverUrl, String projectFullPath) {
        return fetchProject(() -> graphQlClientProvider.forScope(scopeId, token, serverUrl), projectFullPath);
    }

    private Optional<GitLabProjectResponse> fetchProject(Supplier<HttpGraphQlClient> client, String projectFullPath) {
        ClientGraphQlResponse response = query(
                client,
                GET_PROJECT_DOCUMENT,
                "fullPath",
                projectFullPath,
                "project " + sanitizeForLog(projectFullPath),
                true);
        return Optional.ofNullable(response.field("project").toEntity(GitLabProjectResponse.class));
    }

    /**
     * The project GitLab reports under {@code nativeId} to the workspace's own credential, whatever its path is now.
     * Only reads, like {@link #fetchProject}.
     */
    public Optional<GitLabProjectResponse> fetchProjectById(Long scopeId, long nativeId) {
        String globalId = PROJECT_GLOBAL_ID_PREFIX + nativeId;
        ClientGraphQlResponse response = query(
                () -> graphQlClientProvider.forScope(scopeId),
                GET_PROJECTS_BY_IDS_DOCUMENT,
                "ids",
                List.of(globalId),
                "project " + nativeId,
                true);
        if (response.field("projects.nodes").getValue() == null) {
            throw new GitLabSyncException("GitLab did not list project " + nativeId);
        }
        return response.field("projects.nodes").toEntityList(GitLabProjectResponse.class).stream()
                .filter(project -> globalId.equals(project.id()))
                .findFirst();
    }

    /** Stores {@code project} and its group as GitLab reported them, in the caller's transaction when there is one. */
    public Optional<Repository> persistProject(GitLabProjectResponse project) {
        return persistProject(project, resolveProvider());
    }

    /** Keeps a fetched project on the caller's provider, including self-hosted GitLab instances. */
    public Optional<Repository> persistProject(GitLabProjectResponse project, IdentityProvider provider) {
        Organization organization = null;
        GitLabGroupResponse groupData = project.group();
        if (groupData != null) {
            organization = groupProcessor.process(groupData, Objects.requireNonNull(provider.getId()));
            if (organization == null) {
                log.warn(
                        "Skipped project sync: reason=groupProcessingFailed, projectPath={}",
                        sanitizeForLog(project.fullPath()));
                return Optional.empty();
            }
        }
        Repository repository = projectProcessor.processGraphQlResponse(project, organization, provider);
        if (repository != null) {
            log.info(
                    "Synced project: repoId={}, projectPath={}",
                    repository.getId(),
                    sanitizeForLog(project.fullPath()));
        }
        return Optional.ofNullable(repository);
    }

    /**
     * Runs {@code document}. A {@code complete} read also refuses a partial answer, whose errored field would otherwise
     * read as an absent project.
     */
    private ClientGraphQlResponse query(
            Supplier<HttpGraphQlClient> client,
            String document,
            String name,
            Object value,
            String context,
            boolean complete) {
        graphQlClientProvider.acquirePermission();
        ClientGraphQlResponse response;
        try {
            response = client.get()
                    .documentName(document)
                    .variable(name, value)
                    .execute()
                    .block(gitLabProperties.graphqlTimeout());
        } catch (RuntimeException e) {
            var category = exceptionClassifier.classifyWithDetails(e).category();
            if (category == GitLabExceptionClassifier.Category.NOT_FOUND
                    || category == GitLabExceptionClassifier.Category.AUTH_ERROR) {
                throw new RepositoryNotFoundOnGitProviderException(context, e);
            }
            throw e;
        }
        var handled = complete
                        && response != null
                        && response.isValid()
                        && !response.getErrors().isEmpty()
                ? new GitLabGraphQlResponseHandler.HandleResult(
                        GitLabGraphQlResponseHandler.HandleResult.Action.ABORT,
                        exceptionClassifier.classifyGraphQlResponse(response))
                : responseHandler.handle(response, context, log);
        var classification = handled.classification();
        if (classification != null
                && (classification.category() == GitLabExceptionClassifier.Category.NOT_FOUND
                        || classification.category() == GitLabExceptionClassifier.Category.AUTH_ERROR)) {
            Object projectData = response != null
                    ? response.field(GET_PROJECT_DOCUMENT.equals(document) ? "project" : "projects.nodes")
                            .getValue()
                    : null;
            if (projectData == null || (projectData instanceof List<?> projects && projects.isEmpty())) {
                throw new RepositoryNotFoundOnGitProviderException(context);
            }
        }
        if (handled.action() != GitLabGraphQlResponseHandler.HandleResult.Action.CONTINUE
                || (complete && !Objects.requireNonNull(response).getErrors().isEmpty())) {
            GitLabSyncException failure = new GitLabSyncException("Invalid GraphQL response");
            graphQlClientProvider.recordFailure(failure);
            throw failure;
        }
        graphQlClientProvider.recordSuccess();
        return Objects.requireNonNull(response);
    }
}
