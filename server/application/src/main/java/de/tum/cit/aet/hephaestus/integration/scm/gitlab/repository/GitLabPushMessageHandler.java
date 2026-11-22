package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.consumer.DeliveryInbox;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit.GitLabCommitMergeRequestLinker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteWrites;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitLab push webhook events.
 * <p>
 * On each push event, upserts the project as a {@link Repository}
 * using the embedded project metadata from the webhook payload. This ensures the repository
 * entity exists before any commit processing.
 * <p>
 * When local git checkout is enabled ({@code hephaestus.git.enabled=true}), pushes to the
 * default branch trigger a local clone/fetch and JGit commit walk, providing line-level
 * diff statistics (additions/deletions per file). Falls back to webhook-only processing
 * on error or for non-default branches.
 * <p>
 * Only a delivery admitted on its connection's route is handled, for the project admission stored.
 * {@link GitLabPushCommits} writes the commits.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabPushMessageHandler extends AbstractIntegrationMessageHandler<GitLabPushEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabPushMessageHandler.class);

    private final RepositoryRepository repositoryRepository;
    private final GitRepositoryManager gitRepositoryManager;
    private final SyncTargetProvider syncTargetProvider;
    private final GitLabCommitMergeRequestLinker commitMergeRequestLinker;
    private final GitLabRouteWrites routeWrites;
    private final GitLabPushCommits pushCommits;

    private final TransactionTemplate transactions;
    private final GitLabProjectAdmission admission;

    GitLabPushMessageHandler(
            RepositoryRepository repositoryRepository,
            GitRepositoryManager gitRepositoryManager,
            SyncTargetProvider syncTargetProvider,
            GitLabCommitMergeRequestLinker commitMergeRequestLinker,
            GitLabRouteWrites routeWrites,
            GitLabPushCommits pushCommits,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate,
            GitLabProjectAdmission admission) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.PUSH.getValue(),
                GitLabPushEventDTO.class,
                deserializer,
                transactionTemplate);
        this.repositoryRepository = repositoryRepository;
        this.gitRepositoryManager = gitRepositoryManager;
        this.syncTargetProvider = syncTargetProvider;
        this.commitMergeRequestLinker = commitMergeRequestLinker;
        this.routeWrites = routeWrites;
        this.pushCommits = pushCommits;
        this.transactions = transactionTemplate;
        this.admission = admission;
    }

    @Override
    protected void dispatchEvent(GitLabPushEventDTO event) {
        handleEvent(event);
    }

    @Override
    protected void handleEvent(GitLabPushEventDTO event) {
        if (event.project() == null) {
            log.warn("Received push event with missing project data");
            return;
        }

        String projectPath = event.project().pathWithNamespace();
        if (projectPath == null) {
            log.warn("Received push event with missing project path");
            return;
        }
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");

        String safeRef = sanitizeForLog(event.ref());
        log.debug(
                "Received push event: projectPath={}, ref={}, commits={}",
                safeProjectPath,
                safeRef,
                event.totalCommitsCount());

        Optional<GitLabRouteAdmission.AdmittedRoute> route = GitLabRouteAdmission.current();
        if (route.isEmpty()) {
            log.warn("Skipped push event: reason=noAdmittedRoute, projectPath={}", safeProjectPath);
            return;
        }
        if (!route.get().contains(projectPath)) {
            log.warn("Skipped push event: reason=outsideConnectedGroup, projectPath={}", safeProjectPath);
            return;
        }

        // Admission already stored the project as GitLab reports it; the payload's copy of its metadata is not written
        // over that.
        GitLabRouteAdmission.AdmittedRoute admitted = route.get();
        var preparedPush = transactions.execute(status -> repositoryRepository
                .findByNameWithOwnerAndProviderId(projectPath, admitted.providerId())
                .filter(stored -> routeWrites.admitRepository(admitted, stored))
                .flatMap(stored -> repositoryRepository.findByIdWithOrganization(stored.getId()))
                .flatMap(stored ->
                        admission.capture(admitted.workspaceId(), stored).map(read -> new PreparedPush(stored, read)))
                .orElse(null));

        if (preparedPush != null) {
            Repository repository = preparedPush.repository();
            GitLabProjectAdmission.ProjectRead read = preparedPush.read();
            log.debug(
                    "Upserted project from push event: projectPath={}, repoId={}", safeProjectPath, repository.getId());

            if (event.isBranchDeletion()) return;

            long scopeId = read.connection().workspaceId();

            // Only a default-branch push is walked for line-level statistics; every other push
            // still
            // fetches so practice reviews see fresh refs.
            if (event.isDefaultBranch() && gitRepositoryManager.isEnabled()) {
                boolean scopeActive = syncTargetProvider.isScopeActiveForSync(scopeId);

                if (scopeActive) {
                    pushCommits.processCommitsViaLocalGit(event, repository, scopeId, read);
                } else {
                    log.debug("Skipped local git: reason=scopeNotActive, scopeId={}", scopeId);
                    pushCommits.processCommitsViaWebhook(event, repository, false, read);
                }
            } else {
                if (gitRepositoryManager.isEnabled()) pushCommits.fetchForNonDefaultBranch(event, repository, read);
                pushCommits.processCommitsViaWebhook(event, repository, false, read);
            }

            linkCommitsToMergeRequests(scopeId, repository, read);
        } else {
            log.warn("Failed to upsert project from push event: projectPath={}", safeProjectPath);
        }
    }

    /** One GraphQL round trip links every MR updated in the push window, rather than one call per commit. */
    private record PreparedPush(Repository repository, GitLabProjectAdmission.ProjectRead read) {}

    private void linkCommitsToMergeRequests(
            long scopeId, Repository repository, GitLabProjectAdmission.ProjectRead read) {
        try {
            commitMergeRequestLinker.linkCommits(
                    scopeId, repository, OffsetDateTime.now(ZoneOffset.UTC).minusHours(1), read);
        } catch (Exception e) {
            DeliveryInbox.rethrowRejectedWrite();
            log.debug(
                    "Push-time commit→MR link failed: repoId={}, error={}",
                    repository.getId(),
                    e.getClass().getSimpleName());
        }
    }
}
