package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister.Outcome;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.util.CommitUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Backfills missing commits across fetched branches without holding a transaction during Git I/O. */
@Service
@Slf4j
@RequiredArgsConstructor
public class GitHubCommitBackfillService {

    private final GitRepositoryManager gitRepositoryManager;
    private final GitHubAppTokenService tokenService;
    private final CommitRepository commitRepository;
    private final CommitDetailsPersister persister;
    private final CommitAuthorResolver authorResolver;

    /**
     * Backfills commits for a repository from its local bare git clone. Idempotent: a commit whose
     * details are captured is skipped, and a commit whose capture failed is retried next cycle.
     *
     * @return number of commits captured, or -1 if skipped (disabled/error)
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

            Long providerId = repository.getProvider().getId();
            var origin = new CommitDetailsPersister.Origin(
                    scopeId,
                    DataSource.GRAPHQL_SYNC,
                    IdentityProviderType.GITHUB,
                    sha -> CommitUtils.buildCommitUrl(repository.getNameWithOwner(), sha),
                    email -> authorResolver.resolveByEmail(email, providerId));
            Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
            gitRepositoryManager.forEachMissingCommit(
                    key,
                    shas -> commitRepository.findGitDetailsCapturedShas(repoId, shas),
                    info -> outcomes.merge(persister.persist(info, repository, origin), 1, Integer::sum));
            log.info(
                    "Completed commit backfill: repoId={}, capturedCommits={}, failedCommits={}, scope=all-branches",
                    repoId,
                    outcomes.getOrDefault(Outcome.CAPTURED, 0),
                    outcomes.getOrDefault(Outcome.FAILED, 0));
            return outcomes.getOrDefault(Outcome.CAPTURED, 0);
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
}
