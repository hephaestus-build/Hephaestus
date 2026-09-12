package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister.Outcome;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.util.CommitUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Backfills missing commits across fetched branches without holding a transaction during Git I/O. */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabCommitBackfillService {

    private static final Logger log = LoggerFactory.getLogger(GitLabCommitBackfillService.class);

    /**
     * Matches a {@code Co-authored-by: Name <email>} trailer line. Case-insensitive
     * to tolerate both {@code Co-authored-by:} and {@code Co-Authored-By:} variants
     * that different clients emit.
     */
    private static final Pattern CO_AUTHORED_BY_PATTERN =
            Pattern.compile("(?im)^\\s*co-authored-by:\\s*([^<]+?)\\s*<([^>]+)>\\s*$");

    private final GitRepositoryManager gitRepositoryManager;
    private final GitLabTokenService tokenService;
    private final CommitRepository commitRepository;
    private final CommitDetailsPersister persister;
    private final CommitContributorRepository contributorRepository;
    private final CommitAuthorResolver authorResolver;

    public GitLabCommitBackfillService(
            GitRepositoryManager gitRepositoryManager,
            GitLabTokenService tokenService,
            CommitRepository commitRepository,
            CommitDetailsPersister persister,
            CommitContributorRepository contributorRepository,
            CommitAuthorResolver authorResolver) {
        this.gitRepositoryManager = gitRepositoryManager;
        this.tokenService = tokenService;
        this.commitRepository = commitRepository;
        this.persister = persister;
        this.contributorRepository = contributorRepository;
        this.authorResolver = authorResolver;
    }

    /**
     * Backfills commits for a GitLab repository from its local git clone. Idempotent: a commit whose
     * details are captured is skipped, and a commit whose capture failed is retried next cycle.
     *
     * @return sync result with the count of commits captured; an error result with count 0 when
     *     native Git is disabled
     */
    public SyncResult backfillCommits(Long scopeId, Repository repository) {
        if (!gitRepositoryManager.isEnabled()) {
            log.warn(
                    "Skipped native Git commit backfill: reason=gitDisabled, repoId={}, repoName={} — caller should fall through to REST commit sync",
                    repository.getId(),
                    sanitizeForLog(repository.getNameWithOwner()));
            return SyncResult.abortedError(0);
        }

        Long repoId = repository.getId();
        RepositoryKey key = new RepositoryKey(scopeId, repoId);
        String repoName = sanitizeForLog(repository.getNameWithOwner());
        String defaultBranch = repository.getDefaultBranch();

        if (defaultBranch == null || defaultBranch.isBlank()) {
            log.debug("Skipped commit backfill: reason=noDefaultBranch, repoId={}, repoName={}", repoId, repoName);
            return SyncResult.abortedError(0);
        }

        try {
            String serverUrl = tokenService.resolveServerUrl(scopeId);
            String token = tokenService.getAccessToken(scopeId);
            String cloneUrl = serverUrl + "/" + repository.getNameWithOwner() + ".git";
            gitRepositoryManager.ensureRepository(key, cloneUrl, token);

            String headSha = gitRepositoryManager.resolveBranchHead(key, defaultBranch);
            if (headSha == null) {
                log.warn(
                        "Skipped commit backfill: reason=cannotResolveHead, repoId={}, repoName={}, branch={}",
                        repoId,
                        repoName,
                        defaultBranch);
                return SyncResult.abortedError(0);
            }

            Long providerId = Objects.requireNonNull(repository.getProvider().getId());
            var origin = new CommitDetailsPersister.Origin(
                    scopeId,
                    DataSource.GRAPHQL_SYNC,
                    IdentityProviderType.GITLAB,
                    sha -> CommitUtils.buildGitLabCommitUrl(serverUrl, repository.getNameWithOwner(), sha),
                    email -> authorResolver.resolveAndBackfillByEmail(email, providerId),
                    (commit, details, authorId, committerId) -> {
                        persistParents(repoId, details);
                        upsertContributors(commit.getId(), details, authorId, committerId, providerId);
                    });
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
            return SyncResult.completed(outcomes.getOrDefault(Outcome.CAPTURED, 0));
        } catch (GitRepositoryManager.GitOperationException e) {
            log.error(
                    "Commit backfill failed (git operation): repoId={}, repoName={}, error={}",
                    repoId,
                    repoName,
                    e.getMessage());
            return SyncResult.abortedError(0);
        } catch (Exception e) {
            log.error("Commit backfill failed: repoId={}, repoName={}, error={}", repoId, repoName, e.getMessage(), e);
            return SyncResult.abortedError(0);
        }
    }

    /**
     * The REST-first path in {@link GitLabCommitSyncService} feeds the same columns from
     * {@code parent_ids}; both derive the same values, so order does not matter. A root commit writes
     * {@code parent_count = 0} so "no parents" is distinguishable from "not populated".
     */
    private void persistParents(Long repositoryId, CommitDetails details) {
        List<String> parents = details.parentShas();
        commitRepository.updateParentMetadataBySha(
                repositoryId, details.sha(), parents.size(), parents.isEmpty() ? null : String.join(",", parents));
    }

    /**
     * Writes contributor rows for the primary author, committer, and any
     * {@code Co-authored-by:} trailers in the commit body.
     *
     * <p>Mirrors {@code CommitMetadataEnrichmentService} on the GitHub side:
     * <ul>
     *   <li>ordinal 0 / role {@code AUTHOR} — primary git author</li>
     *   <li>ordinal 0 / role {@code COMMITTER} — git committer (same email as
     *       author on most commits but distinct for merge/rebase/cherry-pick)</li>
     *   <li>ordinal 1+ / role {@code CO_AUTHOR} — parsed from
     *       {@code Co-authored-by: Name <email>} trailers, deduplicated on email
     *       against the primary author</li>
     * </ul>
     */
    private void upsertContributors(
            Long commitId,
            CommitDetails info,
            @Nullable Long authorId,
            @Nullable Long committerId,
            @Nullable Long providerId) {
        if (info.authorEmail() != null && !info.authorEmail().isBlank()) {
            contributorRepository.upsertContributor(
                    commitId, authorId, CommitContributor.Role.AUTHOR.name(), info.authorName(), info.authorEmail(), 0);
        }

        if (info.committerEmail() != null && !info.committerEmail().isBlank()) {
            contributorRepository.upsertContributor(
                    commitId,
                    committerId,
                    CommitContributor.Role.COMMITTER.name(),
                    info.committerName(),
                    info.committerEmail(),
                    0);
        }

        List<CoAuthor> coAuthors = parseCoAuthors(info.messageBody(), info.authorEmail());
        for (int i = 0; i < coAuthors.size(); i++) {
            CoAuthor ca = coAuthors.get(i);
            Long coAuthorUserId = authorResolver.resolveAndBackfillByEmail(ca.email(), providerId);
            contributorRepository.upsertContributor(
                    commitId, coAuthorUserId, CommitContributor.Role.CO_AUTHOR.name(), ca.name(), ca.email(), i + 1);
        }
    }

    /**
     * Parse {@code Co-authored-by:} trailers out of a commit message body,
     * lower-casing the email and deduplicating against the primary author's
     * email so the primary author is never double-counted as a co-author.
     */
    private List<CoAuthor> parseCoAuthors(@Nullable String messageBody, @Nullable String primaryAuthorEmail) {
        if (messageBody == null || messageBody.isBlank()) {
            return List.of();
        }

        String primaryLower = primaryAuthorEmail != null ? primaryAuthorEmail.toLowerCase() : null;
        Set<String> seenEmails = new HashSet<>();
        if (primaryLower != null) {
            seenEmails.add(primaryLower);
        }

        List<CoAuthor> result = new ArrayList<>();
        Matcher matcher = CO_AUTHORED_BY_PATTERN.matcher(messageBody);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String email = matcher.group(2).trim();
            if (email.isEmpty()) {
                continue;
            }
            String emailLower = email.toLowerCase();
            if (!seenEmails.add(emailLower)) {
                continue;
            }
            result.add(new CoAuthor(name.isEmpty() ? null : name, email));
        }
        return result;
    }

    private record CoAuthor(@Nullable String name, String email) {}
}
