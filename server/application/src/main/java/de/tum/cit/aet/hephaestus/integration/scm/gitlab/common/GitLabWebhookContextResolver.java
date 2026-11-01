package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Shared context resolver for GitLab webhook handlers. A delivery acts only on the route admission confirmed: for that
 * connection's workspace, never for whichever workspace a payload path happens to match, and only on a project of that
 * connection's GitLab instance that admission confirmed with GitLab. Without an admitted route it resolves nothing.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabWebhookContextResolver {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookContextResolver.class);

    private final RepositoryRepository repositoryRepository;
    private final GitLabRouteAdmission routeAdmission;
    private final GitLabProjectAdmission admission;

    GitLabWebhookContextResolver(
            RepositoryRepository repositoryRepository,
            GitLabRouteAdmission routeAdmission,
            GitLabProjectAdmission admission) {
        this.repositoryRepository = repositoryRepository;
        this.routeAdmission = routeAdmission;
        this.admission = admission;
    }

    /**
     * Resolves a {@link ProcessingContext} for the given repository path and action.
     *
     * @param pathWithNamespace the GitLab project path (e.g., "group/project")
     * @param action            the webhook action string
     * @param eventLabel        a label for log messages (e.g., "issue", "merge request")
     * @return the resolved context, or null without an admitted route or when the repository is not admitted
     */
    @Nullable
    public ProcessingContext resolve(String pathWithNamespace, String action, String eventLabel) {
        String safePath = sanitizeForLog(pathWithNamespace);
        Optional<GitLabRouteAdmission.AdmittedRoute> route = GitLabRouteAdmission.current();
        if (route.isEmpty()) {
            log.warn("Skipped {} event: reason=noAdmittedRoute, repoName={}", eventLabel, safePath);
            return null;
        }
        Repository repository = repositoryRepository
                .findByNameWithOwnerAndProviderId(pathWithNamespace, route.get().providerId())
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

    /**
     * For a write transaction after the one {@link #resolve} ran in, with GitLab read in between: whether the delivery
     * may still write to the repository {@code context} was resolved for, judged on that repository as stored now. The
     * connection is held active until the transaction ends, and only then is the repository loaded and admitted again
     * ({@link GitLabRouteAdmission#admitRepository}), so a project the workspace stopped monitoring, or that moved out
     * of the group, meanwhile takes nothing from the read.
     */
    public Optional<GitLabProjectAdmission.ProjectRead> capture(ProcessingContext context) {
        Long scopeId = context.scopeId();
        Repository repository = context.repository();
        return scopeId != null && repository != null ? admission.capture(scopeId, repository) : Optional.empty();
    }

    public boolean mayStillWrite(ProcessingContext context, GitLabProjectAdmission.ProjectRead read) {
        if (!admission.hold(read)) return false;
        Repository resolved = context.repository();
        Optional<GitLabRouteAdmission.AdmittedRoute> route = GitLabRouteAdmission.current();
        if (resolved == null || route.isEmpty()) {
            return false;
        }
        return Objects.equals(context.scopeId(), route.get().workspaceId())
                && routeAdmission.holdActive(route.get())
                && repositoryRepository
                        .findById(resolved.getId())
                        .filter(current -> routeAdmission.admitRepository(route.get(), current))
                        .isPresent();
    }
}
