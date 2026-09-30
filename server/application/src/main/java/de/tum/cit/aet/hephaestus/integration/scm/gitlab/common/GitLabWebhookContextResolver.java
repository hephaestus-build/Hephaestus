package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Shared context resolver for GitLab webhook handlers.
 * <p>
 * Encapsulates repository lookup, scope filtering, and scope-ID resolution
 * so that all GitLab message handlers use the same logic without duplication.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabWebhookContextResolver {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookContextResolver.class);

    private final RepositoryRepository repositoryRepository;
    private final RepositoryScopeFilter repositoryScopeFilter;
    private final ScopeIdResolver scopeIdResolver;
    private final GitLabRouteAdmission routeAdmission;

    GitLabWebhookContextResolver(
            RepositoryRepository repositoryRepository,
            RepositoryScopeFilter repositoryScopeFilter,
            ScopeIdResolver scopeIdResolver,
            GitLabRouteAdmission routeAdmission) {
        this.repositoryRepository = repositoryRepository;
        this.repositoryScopeFilter = repositoryScopeFilter;
        this.scopeIdResolver = scopeIdResolver;
        this.routeAdmission = routeAdmission;
    }

    /**
     * Resolves a {@link ProcessingContext} for the given repository path and action.
     *
     * @param pathWithNamespace the GitLab project path (e.g., "group/project")
     * @param action            the webhook action string
     * @param eventLabel        a label for log messages (e.g., "issue", "merge request")
     * @return the resolved context, or null if the repository is filtered or not found
     */
    @Nullable
    public ProcessingContext resolve(String pathWithNamespace, String action, String eventLabel) {
        String safePath = sanitizeForLog(pathWithNamespace);

        // A delivery on a connection's own route acts for that connection's workspace, never for whichever workspace
        // a payload path happens to match, and only on a project of that connection's GitLab instance that admission
        // confirmed with GitLab.
        Optional<GitLabRouteAdmission.AdmittedRoute> route = GitLabRouteAdmission.current();
        if (route.isPresent()) {
            Repository repository = repositoryRepository
                    .findByNameWithOwnerAndProviderId(
                            pathWithNamespace, route.get().providerId())
                    .orElse(null);
            if (repository == null) {
                log.debug("Skipped {} event: reason=repositoryNotFound, repoName={}", eventLabel, safePath);
                return null;
            }
            if (!routeAdmission.admitRepository(route.get(), repository)) {
                return null;
            }
            return ProcessingContext.forWebhook(route.get().workspaceId(), repository, action);
        }

        if (!repositoryScopeFilter.isRepositoryAllowed(pathWithNamespace)) {
            log.debug("Skipped {} event: reason=repositoryFiltered, repoName={}", eventLabel, safePath);
            return null;
        }

        Repository repository = repositoryRepository
                .findByNameWithOwnerWithOrganization(pathWithNamespace)
                .orElse(null);

        if (repository == null) {
            log.debug("Skipped {} event: reason=repositoryNotFound, repoName={}", eventLabel, safePath);
            return null;
        }

        Long scopeId = resolveScopeId(repository);
        return ProcessingContext.forWebhook(scopeId, repository, action);
    }

    /**
     * For a write transaction after the one {@link #resolve} ran in, with GitLab read in between: whether the delivery
     * may still write to the repository {@code context} was resolved for, judged on that repository as stored now. On
     * a connection route the connection is held active until the transaction ends, and only then is the repository
     * loaded and admitted again ({@link GitLabRouteAdmission#admitRepository}), so a project the workspace stopped
     * monitoring, or that moved out of the group, meanwhile takes nothing from the read. Off a route it must still pass
     * the scope filter and belong to the same workspace.
     */
    public boolean mayStillWrite(ProcessingContext context) {
        Repository resolved = context.repository();
        if (resolved == null) {
            return false;
        }
        Optional<GitLabRouteAdmission.AdmittedRoute> route = GitLabRouteAdmission.current();
        if (route.isPresent()) {
            return Objects.equals(context.scopeId(), route.get().workspaceId())
                    && routeAdmission.holdActive(route.get())
                    && repositoryRepository
                            .findById(resolved.getId())
                            .filter(current -> routeAdmission.admitRepository(route.get(), current))
                            .isPresent();
        }
        return repositoryRepository
                .findById(resolved.getId())
                .filter(current -> repositoryScopeFilter.isRepositoryAllowed(current.getNameWithOwner()))
                .filter(current -> Objects.equals(resolveScopeId(current), context.scopeId()))
                .isPresent();
    }

    private @Nullable Long resolveScopeId(Repository repository) {
        if (repository.getOrganization() != null) {
            String orgLogin = repository.getOrganization().getLogin();
            Long scopeId = scopeIdResolver.findScopeIdByOrgLogin(orgLogin).orElse(null);
            if (scopeId != null) {
                return scopeId;
            }
        }
        return scopeIdResolver
                .findScopeIdByRepositoryName(repository.getNameWithOwner())
                .orElse(null);
    }
}
