package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.DeliveryInbox;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitCloneUrl;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO.CommitInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteWrites;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the commits of a push that {@link GitLabPushMessageHandler} admitted: from a walk of the local clone, with
 * line-level diff statistics, or from the payload, which is also the fallback when the walk fails. It also fetches the
 * clone for a push that is not walked, so practice reviews see fresh refs. Each commit is written in its own
 * transaction that first holds the project admission stored and the connection's lifecycle lock.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
class GitLabPushCommits {

    private static final Logger log = LoggerFactory.getLogger(GitLabPushCommits.class);
    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";

    private final CommitRepository commitRepository;
    private final CommitDetailsPersister persister;
    private final GitRepositoryManager gitRepositoryManager;
    private final GitLabTokenService tokenService;
    private final CommitAuthorResolver authorResolver;
    private final SyncTargetProvider syncTargetProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final GitLabRouteWrites routeWrites;

    private final TransactionTemplate transactions;
    private final GitLabProjectAdmission admission;

    GitLabPushCommits(
            CommitRepository commitRepository,
            CommitDetailsPersister persister,
            GitRepositoryManager gitRepositoryManager,
            GitLabTokenService tokenService,
            CommitAuthorResolver authorResolver,
            SyncTargetProvider syncTargetProvider,
            ApplicationEventPublisher eventPublisher,
            GitLabRouteWrites routeWrites,
            TransactionTemplate transactionTemplate,
            GitLabProjectAdmission admission) {
        this.commitRepository = commitRepository;
        this.persister = persister;
        this.gitRepositoryManager = gitRepositoryManager;
        this.tokenService = tokenService;
        this.authorResolver = authorResolver;
        this.syncTargetProvider = syncTargetProvider;
        this.eventPublisher = eventPublisher;
        this.routeWrites = routeWrites;
        this.transactions = transactionTemplate;
        this.admission = admission;
    }

    /** Fetches so the mirror is current for practice reviews; commits on other branches are not walked. */
    void fetchForNonDefaultBranch(
            GitLabPushEventDTO event, Repository repository, GitLabProjectAdmission.ProjectRead read) {
        try {
            long scopeId = read.connection().workspaceId();
            if (!syncTargetProvider.isScopeActiveForSync(scopeId)) {
                return;
            }
            String serverUrl = tokenService.resolveServerUrl(scopeId);
            String token = tokenService.getAccessToken(scopeId);
            String cloneUrl = GitCloneUrl.require(serverUrl, repository.getNameWithOwner());
            gitRepositoryManager.ensureRepository(new RepositoryKey(scopeId, repository.getId()), cloneUrl, token);
            log.debug(
                    "Fetched non-default branch push: ref={}, repo={}",
                    sanitizeForLog(event.ref()),
                    sanitizeForLog(repository.getNameWithOwner()));
        } catch (Exception e) {
            DeliveryInbox.rethrowRejectedWrite();
            log.warn(
                    "Non-default branch fetch failed: repo={}, error={}",
                    sanitizeForLog(repository.getNameWithOwner()),
                    e.getClass().getSimpleName());
        }
    }

    // Local git path (enriched with line-level diff stats)

    void processCommitsViaLocalGit(
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
            String cloneUrl = GitCloneUrl.require(serverUrl, repository.getNameWithOwner());
            RepositoryKey key = new RepositoryKey(scopeId, repository.getId());
            gitRepositoryManager.ensureRepository(key, cloneUrl, token);

            Long providerId = Objects.requireNonNull(repository.getProvider().getId());
            var origin = new CommitDetailsPersister.Origin(
                    scopeId,
                    DataSource.WEBHOOK,
                    IdentityProviderType.GITLAB,
                    sha -> CommitUtils.buildGitLabCommitUrl(serverUrl, repository.getNameWithOwner(), sha),
                    email -> authorResolver.resolveByEmail(email, providerId));
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
            DeliveryInbox.rethrowRejectedWrite();
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
    void processCommitsViaWebhook(
            GitLabPushEventDTO event,
            Repository repository,
            boolean asFallback,
            GitLabProjectAdmission.ProjectRead read) {
        List<CommitInfo> commits = event.commits();
        if (commits == null || commits.isEmpty()) {
            return;
        }

        int created = 0;
        RuntimeException failure = null;
        for (CommitInfo commit : commits) {
            if (commit.id() == null || commit.id().isBlank()) {
                continue;
            }
            try {
                Boolean written = transactions.execute(status -> {
                    if (!mayWrite(read)) return false;
                    return persistWebhookCommit(
                            commit, repository, asFallback, read.connection().workspaceId());
                });
                if (Boolean.TRUE.equals(written)) created++;
            } catch (RuntimeException e) {
                DeliveryInbox.rethrowRejectedWrite();
                if (failure == null) failure = e;
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
        if (failure != null) throw failure;
    }

    private boolean persistWebhookCommit(CommitInfo commit, Repository repository, boolean asFallback, long scopeId) {
        String sha = Objects.requireNonNull(commit.id());
        if (!DeliveryInbox.claim("gitlab.push.webhook." + repository.getId() + "." + sha)) return false;
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
                null,
                false);

        if (!asFallback) {
            persistWebhookFileChanges(sha, commit, repository);
        }
        publishCommitCreated(sha, repository, scopeId);
        return true;
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
                && GitLabRouteAdmission.current().map(routeWrites::holdActive).orElse(false);
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
