package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.util.CommitUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Backfills missing commits across fetched branches without holding a transaction during Git I/O. */
@Service
@Slf4j
@RequiredArgsConstructor
public class GitHubCommitBackfillService {

    private final GitRepositoryManager gitRepositoryManager;
    private final GitHubAppTokenService tokenService;
    private final CommitRepository commitRepository;
    private final CommitAuthorResolver authorResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * Backfills commits for a repository from its local bare git clone.
     * <p>
     * This method is safe to call repeatedly — it is idempotent. Commits that
     * already exist are skipped via the {@code existsByShaAndRepositoryId} fast-path.
     * <p>
     * Git clone/fetch operations run OUTSIDE any transaction to avoid holding
     * database connections during potentially slow I/O.
     *
     * @param syncTarget the sync target (provides auth info)
     * @param repository the repository entity (provides ID, name, default branch)
     * @param scopeId    the scope ID for event context
     * @return number of new commits persisted, or -1 if skipped (disabled/error)
     */
    public int backfillCommits(SyncTarget syncTarget, Repository repository, Long scopeId) {
        if (!gitRepositoryManager.isEnabled()) {
            return -1;
        }

        Long repoId = repository.getId();
        RepositoryKey key = new RepositoryKey(scopeId, repoId);
        String repoName = sanitizeForLog(repository.getNameWithOwner());
        String defaultBranch = repository.getDefaultBranch();

        if (defaultBranch == null || defaultBranch.isBlank()) {
            log.debug("Skipped commit backfill: reason=noDefaultBranch, repoId={}, repoName={}", repoId, repoName);
            return -1;
        }

        try {
            String cloneUrl = "https://github.com/" + repository.getNameWithOwner() + ".git";
            String token = resolveToken(syncTarget);
            gitRepositoryManager.ensureRepository(key, cloneUrl, token);

            String headSha = gitRepositoryManager.resolveBranchHead(key, defaultBranch);
            if (headSha == null) {
                log.warn(
                        "Skipped commit backfill: reason=cannotResolveHead, repoId={}, repoName={}, branch={}",
                        repoId,
                        repoName,
                        defaultBranch);
                return -1;
            }

            int[] processed = {0};
            gitRepositoryManager.forEachMissingCommit(
                    key, shas -> commitRepository.findGitDetailsCapturedShas(repoId, shas), info -> {
                        if (processCommitInfo(info, repository, scopeId)) {
                            processed[0]++;
                        }
                    });
            log.info(
                    "Completed commit backfill: repoId={}, capturedCommits={}, scope=all-branches",
                    repoId,
                    processed[0]);
            return processed[0];
        } catch (GitRepositoryManager.GitOperationException e) {
            log.error(
                    "Commit backfill failed (git operation): repoId={}, repoName={}, error={}",
                    repoId,
                    repoName,
                    e.getMessage());
            return -1;
        } catch (Exception e) {
            log.error("Commit backfill failed: repoId={}, repoName={}, error={}", repoId, repoName, e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Resolves the authentication token for git operations.
     *
     * @param syncTarget the sync target with auth info
     * @return the token, or null for public repos
     */
    @Nullable
    private String resolveToken(SyncTarget syncTarget) {
        if (syncTarget.authMode() == AuthMode.PERSONAL_ACCESS_TOKEN) {
            return syncTarget.personalAccessToken();
        }
        if (syncTarget.installationId() != null && tokenService.isConfigured()) {
            try {
                return tokenService.getInstallationToken(syncTarget.installationId());
            } catch (Exception e) {
                log.warn("Failed to get installation token for commit backfill: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    private boolean processCommitInfo(CommitDetails info, Repository repository, Long scopeId) {
        Boolean result = transactionTemplate.execute(status -> {
            // Fast-path: skip if already persisted
            if (commitRepository.existsByShaAndRepositoryIdAndGitDetailsCapturedAtIsNotNull(
                    info.sha(), repository.getId())) {
                return false;
            }

            boolean newCommit = !commitRepository.existsByShaAndRepositoryId(info.sha(), repository.getId());

            // Resolve author/committer IDs by email (with noreply fallback)
            Long providerId = repository.getProvider().getId();
            Long authorId = authorResolver.resolveByEmail(info.authorEmail(), providerId);
            Long committerId = authorResolver.resolveByEmail(info.committerEmail(), providerId);

            // Upsert commit via native SQL (no exception on conflict)
            // Defense-in-depth: git_commit.message is NOT NULL; default to empty string
            String message = info.message() != null ? info.message() : "";
            commitRepository.upsertCommit(
                    info.sha(),
                    message,
                    info.messageBody(),
                    buildCommitUrl(repository.getNameWithOwner(), info.sha()),
                    info.authoredAt(),
                    info.committedAt(),
                    info.additions(),
                    info.deletions(),
                    info.changedFiles(),
                    Instant.now(),
                    repository.getId(),
                    authorId,
                    committerId,
                    info.authorEmail(),
                    info.committerEmail());

            commitRepository.deleteFileChanges(repository.getId(), info.sha());

            // Attach file changes if present
            if (!info.fileChanges().isEmpty()) {
                Commit commit = commitRepository
                        .findByShaAndRepositoryId(info.sha(), repository.getId())
                        .orElseThrow(() -> new IllegalStateException("Commit missing after upsert"));
                if (commit != null) {
                    commit.getFileChanges().clear();
                    for (CommitDetails.FileChange fc : info.fileChanges()) {
                        CommitFileChange fileChange = new CommitFileChange();
                        fileChange.setFilename(fc.filename());
                        fileChange.setChangeType(fc.changeType());
                        fileChange.setAdditions(fc.additions());
                        fileChange.setDeletions(fc.deletions());
                        fileChange.setChanges(fc.changes());
                        fileChange.setPreviousFilename(fc.previousFilename());
                        commit.addFileChange(fileChange);
                    }
                    commitRepository.save(commit);
                }
            }

            // Publish CommitCreated event (fires after transaction commits)
            commitRepository.markGitDetailsCaptured(repository.getId(), info.sha(), Instant.now());
            if (newCommit) publishCommitCreated(info.sha(), repository, scopeId);

            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    /**
     * Publishes a {@link ScmDomainEvent.CommitCreated} event for a newly persisted commit.
     */
    private void publishCommitCreated(String sha, Repository repository, Long scopeId) {
        Commit commit = commitRepository
                .findByShaAndRepositoryId(sha, repository.getId())
                .orElse(null);
        if (commit == null) {
            log.debug("Cannot publish CommitCreated: commit not found after upsert: sha={}", sha);
            return;
        }

        ScmEventPayload.CommitData commitData = ScmEventPayload.CommitData.from(commit);
        EventContext context = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                scopeId,
                RepositoryRef.from(repository),
                DataSource.GRAPHQL_SYNC,
                null,
                UUID.randomUUID().toString(),
                IdentityProviderType.GITHUB);

        eventPublisher.publishEvent(new ScmDomainEvent.CommitCreated(commitData, context));
    }

    private String buildCommitUrl(String nameWithOwner, String sha) {
        return CommitUtils.buildCommitUrl(nameWithOwner, sha);
    }
}
