package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister.Outcome;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.util.CommitUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit.GitLabCommitMergeRequestLinker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO.CommitInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
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
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabPushMessageHandler extends AbstractIntegrationMessageHandler<GitLabPushEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabPushMessageHandler.class);
    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";

    private final RepositoryRepository repositoryRepository;
    private final CommitRepository commitRepository;
    private final CommitDetailsPersister persister;
    private final GitRepositoryManager gitRepositoryManager;
    private final GitLabTokenService tokenService;
    private final CommitAuthorResolver authorResolver;
    private final SyncTargetProvider syncTargetProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final GitLabCommitMergeRequestLinker commitMergeRequestLinker;
    private final GitLabRouteAdmission routeAdmission;

    private final TransactionTemplate transactions;
    private final GitLabProjectAdmission admission;

    GitLabPushMessageHandler(
            RepositoryRepository repositoryRepository,
            CommitRepository commitRepository,
            CommitDetailsPersister persister,
            GitRepositoryManager gitRepositoryManager,
            GitLabTokenService tokenService,
            CommitAuthorResolver authorResolver,
            SyncTargetProvider syncTargetProvider,
            ApplicationEventPublisher eventPublisher,
            GitLabCommitMergeRequestLinker commitMergeRequestLinker,
            GitLabRouteAdmission routeAdmission,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate,
            GitLabProjectAdmission admission) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.PUSH.getValue(),
                GitLabPushEventDTO.class,
                deserializer,
                transactionTemplate);
        this.repositoryRepository = repositoryRepository;
        this.commitRepository = commitRepository;
        this.persister = persister;
        this.gitRepositoryManager = gitRepositoryManager;
        this.tokenService = tokenService;
        this.authorResolver = authorResolver;
        this.syncTargetProvider = syncTargetProvider;
        this.eventPublisher = eventPublisher;
        this.commitMergeRequestLinker = commitMergeRequestLinker;
        this.routeAdmission = routeAdmission;
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

        if (event.isBranchDeletion()) {
            log.debug("Skipped push event: reason=branchDeletion, projectPath={}", safeProjectPath);
            return;
        }

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
                .filter(stored -> routeAdmission.admitRepository(admitted, stored))
                .flatMap(stored -> repositoryRepository.findByIdWithOrganization(stored.getId()))
                .flatMap(stored ->
                        admission.capture(admitted.workspaceId(), stored).map(read -> new PreparedPush(stored, read)))
                .orElse(null));

        if (preparedPush != null) {
            Repository repository = preparedPush.repository();
            GitLabProjectAdmission.ProjectRead read = preparedPush.read();
            log.debug(
                    "Upserted project from push event: projectPath={}, repoId={}", safeProjectPath, repository.getId());

            long scopeId = read.connection().workspaceId();

            // Only a default-branch push is walked for line-level statistics; every other push
            // still
            // fetches so practice reviews see fresh refs.
            if (event.isDefaultBranch() && gitRepositoryManager.isEnabled()) {
                boolean scopeActive = syncTargetProvider.isScopeActiveForSync(scopeId);

                if (scopeActive) {
                    processCommitsViaLocalGit(event, repository, scopeId, read);
                } else {
                    log.debug("Skipped local git: reason=scopeNotActive, scopeId={}", scopeId);
                    processCommitsViaWebhook(event, repository, false, read);
                }
            } else {
                if (gitRepositoryManager.isEnabled()) fetchForNonDefaultBranch(event, repository, read);
                processCommitsViaWebhook(event, repository, false, read);
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
            log.debug(
                    "Push-time commit→MR link failed: repoId={}, error={}",
                    repository.getId(),
                    e.getClass().getSimpleName());
        }
    }

    /** Fetches so the mirror is current for practice reviews; commits on other branches are not walked. */
    private void fetchForNonDefaultBranch(
            GitLabPushEventDTO event, Repository repository, GitLabProjectAdmission.ProjectRead read) {
        try {
            long scopeId = read.connection().workspaceId();
            if (!syncTargetProvider.isScopeActiveForSync(scopeId)) {
                return;
            }
            String serverUrl = tokenService.resolveServerUrl(scopeId);
            String token = tokenService.getAccessToken(scopeId);
            String cloneUrl = serverUrl + "/" + repository.getNameWithOwner() + ".git";
            gitRepositoryManager.ensureRepository(new RepositoryKey(scopeId, repository.getId()), cloneUrl, token);
            log.debug(
                    "Fetched non-default branch push: ref={}, repo={}",
                    sanitizeForLog(event.ref()),
                    sanitizeForLog(repository.getNameWithOwner()));
        } catch (Exception e) {
            log.warn(
                    "Non-default branch fetch failed: repo={}, error={}",
                    sanitizeForLog(repository.getNameWithOwner()),
                    e.getClass().getSimpleName());
        }
    }

    // Local git path (enriched with line-level diff stats)

    private void processCommitsViaLocalGit(
            GitLabPushEventDTO event, Repository repository, Long scopeId, GitLabProjectAdmission.ProjectRead read) {
        String repoName = sanitizeForLog(repository.getNameWithOwner());
        String beforeSha = event.before();
        String afterSha = event.after();
        if (afterSha == null) {
            processCommitsViaWebhook(event, repository, true, read);
            return;
        }

        int[] failed = {0};
        try {
            String serverUrl = tokenService.resolveServerUrl(scopeId);
            String token = tokenService.getAccessToken(scopeId);
            String cloneUrl = serverUrl + "/" + repository.getNameWithOwner() + ".git";
            RepositoryKey key = new RepositoryKey(scopeId, repository.getId());
            gitRepositoryManager.ensureRepository(key, cloneUrl, token);

            Long providerId = Objects.requireNonNull(repository.getProvider().getId());
            var origin = new CommitDetailsPersister.Origin(
                    scopeId,
                    DataSource.WEBHOOK,
                    IdentityProviderType.GITLAB,
                    sha -> CommitUtils.buildGitLabCommitUrl(serverUrl, repository.getNameWithOwner(), sha),
                    email -> authorResolver.resolveAndBackfillByEmail(email, providerId));
            gitRepositoryManager.forEachCommitInRange(
                    key,
                    isInitialPush(beforeSha) ? null : beforeSha,
                    afterSha,
                    shas -> commitRepository.findGitDetailsCapturedShas(repository.getId(), shas),
                    info -> {
                        Outcome outcome = transactions.execute(status ->
                                mayWrite(read) ? persister.persist(info, repository, origin) : Outcome.FAILED);
                        if (outcome == Outcome.FAILED) failed[0]++;
                    });

            log.info("Processed push commits via local git: repoName={}, failed={}", repoName, failed[0]);
        } catch (Exception e) {
            log.error(
                    "Failed to process commits via local git, falling back to webhook: repoName={}, error={}",
                    repoName,
                    e.getClass().getSimpleName());
            failed[0]++;
        }
        if (failed[0] > 0) processCommitsViaWebhook(event, repository, true, read);
    }

    // Webhook-only path (file lists without line-level stats)

    /**
     * Persists the push payload's commits, one transaction each so one bad commit does not roll back
     * its neighbours. As the fallback after a local-git failure the statistics and file lists are
     * withheld so {@code COALESCE} keeps what JGit already captured.
     */
    private void processCommitsViaWebhook(
            GitLabPushEventDTO event,
            Repository repository,
            boolean asFallback,
            GitLabProjectAdmission.ProjectRead read) {
        List<CommitInfo> commits = event.commits();
        if (commits == null || commits.isEmpty()) {
            return;
        }

        int created = 0;
        for (CommitInfo commit : commits) {
            if (commit.id() == null || commit.id().isBlank()) {
                continue;
            }
            try {
                Boolean written = transactions.execute(status -> {
                    if (!mayWrite(read)) return false;
                    persistWebhookCommit(
                            commit, repository, asFallback, read.connection().workspaceId());
                    return true;
                });
                if (Boolean.TRUE.equals(written)) created++;
            } catch (Exception e) {
                log.warn(
                        "Failed to upsert commit: sha={}, repoId={}, error={}",
                        commit.id(),
                        repository.getId(),
                        e.getClass().getSimpleName());
            }
        }

        if (created > 0) {
            log.info(
                    "Created commits from push event: repoId={}, created={}, total={}, fallback={}",
                    repository.getId(),
                    created,
                    commits.size(),
                    asFallback);
        }
    }

    private void persistWebhookCommit(CommitInfo commit, Repository repository, boolean asFallback, long scopeId) {
        String sha = Objects.requireNonNull(commit.id());
        Instant authoredAt = parseTimestamp(commit.timestamp());
        int changedFiles = commit.changedFilesCount();
        String authorEmail = commit.author() != null ? commit.author().email() : null;

        commitRepository.upsertCommit(
                sha,
                extractHeadline(commit.message(), commit.title()),
                extractBody(commit.message()),
                commit.url(),
                authoredAt,
                authoredAt,
                asFallback ? null : 0,
                asFallback ? null : 0,
                asFallback ? null : (changedFiles > 0 ? changedFiles : null),
                Instant.now(),
                repository.getId(),
                null,
                null,
                authorEmail,
                authorEmail,
                null);

        if (!asFallback) {
            persistWebhookFileChanges(sha, commit, repository);
        }
        publishCommitCreated(sha, repository, scopeId);
    }

    /**
     * Persists file changes from webhook payload (filenames + change type, no line stats).
     */
    private void persistWebhookFileChanges(String sha, CommitInfo commitInfo, Repository repository) {
        boolean hasFiles = (commitInfo.added() != null && !commitInfo.added().isEmpty())
                || (commitInfo.modified() != null && !commitInfo.modified().isEmpty())
                || (commitInfo.removed() != null && !commitInfo.removed().isEmpty());

        if (!hasFiles) {
            return;
        }

        Commit commitEntity = commitRepository
                .findByShaAndRepositoryId(sha, repository.getId())
                .orElse(null);
        if (commitEntity == null) {
            return;
        }

        addFileChanges(commitEntity, commitInfo.added(), CommitFileChange.ChangeType.ADDED);
        addFileChanges(commitEntity, commitInfo.modified(), CommitFileChange.ChangeType.MODIFIED);
        addFileChanges(commitEntity, commitInfo.removed(), CommitFileChange.ChangeType.REMOVED);

        commitRepository.save(commitEntity);
    }

    // Domain event publishing

    private void publishCommitCreated(String sha, Repository repository, long scopeId) {
        Commit commit = commitRepository
                .findByShaAndRepositoryId(sha, repository.getId())
                .orElse(null);
        if (commit == null) {
            return;
        }

        ScmEventPayload.CommitData commitData = ScmEventPayload.CommitData.from(commit);
        EventContext context = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                scopeId,
                Objects.requireNonNull(RepositoryRef.from(repository)),
                DataSource.WEBHOOK,
                null,
                UUID.randomUUID().toString(),
                IdentityProviderType.GITLAB);

        eventPublisher.publishEvent(new ScmDomainEvent.CommitCreated(commitData, context));
    }

    // Scope resolution

    /**
     * For a write transaction after the git fetch: whether the delivery may still write, which means its connection is
     * still active, held so until the transaction ends.
     */
    private boolean mayWrite(GitLabProjectAdmission.ProjectRead read) {
        return admission.hold(read)
                && GitLabRouteAdmission.current()
                        .map(routeAdmission::holdActive)
                        .orElse(false);
    }

    // Utility methods

    private static String extractHeadline(@Nullable String fullMessage, @Nullable String title) {
        if (title != null && !title.isBlank()) return CommitDetailsPersister.fit(title, Commit.MESSAGE_LENGTH);
        if (fullMessage == null || fullMessage.isBlank()) return "(no message)";
        int newline = fullMessage.indexOf('\n');
        String headline = newline > 0 ? fullMessage.substring(0, newline).trim() : fullMessage.trim();
        return CommitDetailsPersister.fit(headline, Commit.MESSAGE_LENGTH);
    }

    @Nullable
    private static String extractBody(@Nullable String fullMessage) {
        if (fullMessage == null) return null;
        int newline = fullMessage.indexOf('\n');
        if (newline < 0 || newline + 1 >= fullMessage.length()) return null;
        String body = fullMessage.substring(newline + 1).trim();
        return body.isEmpty() ? null : body;
    }

    private static Instant parseTimestamp(@Nullable String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return Instant.now();
        try {
            return OffsetDateTime.parse(timestamp).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private static void addFileChanges(
            Commit commit, @Nullable List<String> filenames, CommitFileChange.ChangeType changeType) {
        if (filenames == null) return;
        for (String filename : filenames) {
            if (filename == null || filename.isBlank()) continue;
            CommitFileChange fc = new CommitFileChange();
            fc.setFilename(CommitDetailsPersister.fit(filename, CommitFileChange.FILENAME_LENGTH));
            fc.setChangeType(changeType);
            commit.addFileChange(fc);
        }
    }

    private boolean isInitialPush(@Nullable String sha) {
        return sha == null || ZERO_SHA.equals(sha);
    }
}
