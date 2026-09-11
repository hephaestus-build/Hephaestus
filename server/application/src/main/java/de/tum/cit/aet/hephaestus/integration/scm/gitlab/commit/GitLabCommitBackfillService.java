package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.util.CommitUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final CommitContributorRepository contributorRepository;
    private final CommitAuthorResolver authorResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public GitLabCommitBackfillService(
            GitRepositoryManager gitRepositoryManager,
            GitLabTokenService tokenService,
            CommitRepository commitRepository,
            CommitContributorRepository contributorRepository,
            CommitAuthorResolver authorResolver,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate) {
        this.gitRepositoryManager = gitRepositoryManager;
        this.tokenService = tokenService;
        this.commitRepository = commitRepository;
        this.contributorRepository = contributorRepository;
        this.authorResolver = authorResolver;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Backfills commits for a GitLab repository from its local git clone.
     *
     * <p>Idempotent: commits already in the database are skipped via
     * {@code existsByShaAndRepositoryId} fast-path. Returns immediately with
     * count 0 when local git is disabled.
     *
     * @param scopeId    the workspace scope ID (for token resolution)
     * @param repository the repository entity
     * @return sync result with count of new commits persisted
     */
    public SyncResult backfillCommits(Long scopeId, Repository repository) {
        if (!gitRepositoryManager.isEnabled()) {
            // An error result lets the caller use REST when local Git is disabled.
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

            int[] processed = {0};
            gitRepositoryManager.forEachMissingCommit(
                    key, shas -> commitRepository.findGitDetailsCapturedShas(repoId, shas), info -> {
                        if (processCommitInfo(info, repository, scopeId, serverUrl)) {
                            processed[0]++;
                        }
                    });
            log.info(
                    "Completed commit backfill: repoId={}, capturedCommits={}, scope=all-branches",
                    repoId,
                    processed[0]);
            return SyncResult.completed(processed[0]);
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

    private boolean processCommitInfo(CommitDetails info, Repository repository, Long scopeId, String serverUrl) {
        Boolean result = transactionTemplate.execute(status -> {
            if (commitRepository.existsByShaAndRepositoryIdAndGitDetailsCapturedAtIsNotNull(
                    info.sha(), repository.getId())) {
                return false;
            }

            boolean newCommit = !commitRepository.existsByShaAndRepositoryId(info.sha(), repository.getId());

            Long providerId = Objects.requireNonNull(
                    repository.getProvider() != null
                            ? Objects.requireNonNull(repository.getProvider().getId())
                            : null);
            Long authorId = authorResolver.resolveAndBackfillByEmail(info.authorEmail(), providerId);
            Long committerId = authorResolver.resolveAndBackfillByEmail(info.committerEmail(), providerId);

            String message = info.message() != null ? info.message() : "";
            String htmlUrl = CommitUtils.buildGitLabCommitUrl(serverUrl, repository.getNameWithOwner(), info.sha());

            commitRepository.upsertCommit(
                    info.sha(),
                    message,
                    info.messageBody(),
                    htmlUrl,
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

            // Persist parent topology from the local clone walk. The REST-first
            // path in GitLabCommitSyncService also sets these via the
            // parent_ids field; both paths feed the same columns so whichever
            // runs first wins and subsequent runs are COALESCE-idempotent.
            if (info.parentShas() != null && !info.parentShas().isEmpty()) {
                commitRepository.updateParentMetadataBySha(
                        repository.getId(), info.sha(), info.parentShas().size(), String.join(",", info.parentShas()));
            } else if (info.parentShas() != null) {
                // Root commit (parent_count = 0). Write the count so downstream
                // queries can distinguish "populated=0 parents" from "unpopulated".
                commitRepository.updateParentMetadataBySha(repository.getId(), info.sha(), 0, null);
            }

            Commit commit = commitRepository
                    .findByShaAndRepositoryId(info.sha(), repository.getId())
                    .orElse(null);
            if (commit == null) {
                throw new IllegalStateException("Commit missing after upsert");
            }

            commit.getFileChanges().clear();
            if (!info.fileChanges().isEmpty()) {
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

            upsertContributors(commit.getId(), info, authorId, committerId, providerId);

            commitRepository.markGitDetailsCaptured(repository.getId(), info.sha(), Instant.now());
            if (newCommit) publishCommitCreated(commit, repository, scopeId);
            return true;
        });
        return Boolean.TRUE.equals(result);
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

    private void publishCommitCreated(Commit commit, Repository repository, Long scopeId) {
        ScmEventPayload.CommitData commitData = ScmEventPayload.CommitData.from(commit);
        EventContext context = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                scopeId,
                Objects.requireNonNull(RepositoryRef.from(repository)),
                DataSource.GRAPHQL_SYNC,
                null,
                UUID.randomUUID().toString(),
                IdentityProviderType.GITLAB);

        eventPublisher.publishEvent(new ScmDomainEvent.CommitCreated(commitData, context));
    }
}
