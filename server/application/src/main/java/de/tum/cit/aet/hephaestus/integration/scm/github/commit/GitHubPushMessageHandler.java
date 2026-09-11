package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.util.CommitUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub push webhook events for commit synchronization.
 * <p>
 * When a push event is received:
 * <ol>
 *   <li>Validates the event (skips branch deletions, empty pushes, non-default branches)</li>
 *   <li>If local git checkout is enabled: clone/fetch, walk commits, extract file changes</li>
 *   <li>Otherwise: persist commits from webhook payload data only</li>
 * </ol>
 *
 * @see <a href="https://docs.github.com/en/webhooks/webhook-events-and-payloads#push">
 *      GitHub Push Event Documentation</a>
 */
@Slf4j
@Component
public class GitHubPushMessageHandler extends AbstractIntegrationMessageHandler<GitHubPushEventDTO> {

    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";

    private final GitRepositoryManager gitRepositoryManager;
    private final GitHubAppTokenService tokenService;
    private final RepositoryRepository repositoryRepository;
    private final CommitRepository commitRepository;
    private final CommitAuthorResolver authorResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final ScopeIdResolver scopeIdResolver;
    private final SyncTargetProvider syncTargetProvider;
    private final TransactionTemplate transactions;

    public GitHubPushMessageHandler(
            GitRepositoryManager gitRepositoryManager,
            GitHubAppTokenService tokenService,
            RepositoryRepository repositoryRepository,
            CommitRepository commitRepository,
            CommitAuthorResolver authorResolver,
            ApplicationEventPublisher eventPublisher,
            ScopeIdResolver scopeIdResolver,
            SyncTargetProvider syncTargetProvider,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.PUSH.getValue(),
                GitHubPushEventDTO.class,
                deserializer,
                transactionTemplate);
        this.gitRepositoryManager = gitRepositoryManager;
        this.tokenService = tokenService;
        this.repositoryRepository = repositoryRepository;
        this.commitRepository = commitRepository;
        this.authorResolver = authorResolver;
        this.eventPublisher = eventPublisher;
        this.scopeIdResolver = scopeIdResolver;
        this.syncTargetProvider = syncTargetProvider;
        this.transactions = transactionTemplate;
    }

    @Override
    protected void dispatchEvent(GitHubPushEventDTO event) {
        handleEvent(event);
    }

    @Override
    protected void handleEvent(GitHubPushEventDTO event) {
        String repoName =
                event.repository() != null ? sanitizeForLog(event.repository().fullName()) : "unknown";

        // Skip branch deletions - no commits to process
        if (event.deleted()) {
            log.debug("Skipped push event: reason=branchDeleted, ref={}, repoName={}", event.ref(), repoName);
            return;
        }

        // Skip if no commits
        if (event.commits() == null || event.commits().isEmpty()) {
            log.debug("Skipped push event: reason=noCommits, ref={}, repoName={}", event.ref(), repoName);
            return;
        }

        log.debug(
                "Received push event: branch={}, commitCount={}, forced={}, repoName={}",
                getBranchName(event.ref()),
                event.commits().size(),
                event.forced(),
                repoName);

        // Get repository from database by its GitHub ID
        Long repoId = event.repository() != null ? event.repository().id() : null;
        if (repoId == null) {
            log.warn("Skipped push event: reason=missingRepositoryId, repoName={}", repoName);
            return;
        }

        Repository repository =
                repositoryRepository.findByIdWithOrganization(repoId).orElse(null);
        if (repository == null) {
            log.debug("Skipped push event: reason=repositoryNotFound, repoId={}, repoName={}", repoId, repoName);
            return;
        }

        // Only process commits to the default branch
        String defaultBranch = repository.getDefaultBranch();
        if (defaultBranch == null || !isDefaultBranch(event.ref(), defaultBranch)) {
            log.debug(
                    "Skipped push event: reason=notDefaultBranch, branch={}, defaultBranch={}, repoName={}",
                    getBranchName(event.ref()),
                    defaultBranch,
                    repoName);
            return;
        }

        // Only use local git clone for repositories in active workspaces.
        // Without this check, pushes to repos belonging to inactive/archived/purged
        // workspaces would still trigger expensive clone operations.
        Long scopeId = resolveScopeId(repository);
        boolean scopeActive = scopeId != null && syncTargetProvider.isScopeActiveForSync(scopeId);

        // Process commits
        if (gitRepositoryManager.isEnabled() && scopeActive) {
            processCommitsViaLocalGit(event, repository, Objects.requireNonNull(scopeId));
        } else {
            if (gitRepositoryManager.isEnabled() && !scopeActive) {
                log.debug(
                        "Skipped local git processing: reason=scopeNotActive, scopeId={}, repoName={}",
                        scopeId,
                        repoName);
            }
            processCommitsViaWebhook(event, repository, false);
        }
    }

    private void processCommitsViaLocalGit(GitHubPushEventDTO event, Repository repository, Long scopeId) {
        RepositoryKey key = new RepositoryKey(scopeId, repository.getId());
        String repoName = sanitizeForLog(repository.getNameWithOwner());
        String beforeSha = event.before();
        String afterSha = event.after();

        try {
            // Get authentication token
            String cloneUrl = "https://github.com/" + repository.getNameWithOwner() + ".git";
            String token = null;
            if (event.installation() != null && tokenService.isConfigured()) {
                token = tokenService.getInstallationToken(event.installation().id());
            }

            // Ensure repository is cloned/fetched
            gitRepositoryManager.ensureRepository(key, cloneUrl, token);

            gitRepositoryManager.forEachCommitInRange(
                    key,
                    isInitialPush(beforeSha) ? null : beforeSha,
                    afterSha,
                    info -> transactions.execute(status -> processCommitInfo(info, repository)));

            log.info("Processed push commits via local git: repoName={}", repoName);
        } catch (Exception e) {
            log.error(
                    "Failed to process commits via local git, falling back to webhook: repoName={}, error={}",
                    repoName,
                    e.getMessage());
            // Fall back to webhook-only data (preserves any richer data already persisted)
            processCommitsViaWebhook(event, repository, true);
        }
    }

    /**
     * Process commits using only webhook payload data.
     * Does not provide file-level change information (only file lists).
     * <p>
     * Uses native {@code INSERT ... ON CONFLICT DO UPDATE} for idempotency,
     * avoiding the check-then-act race of {@code existsBy...} + {@code save}.
     * <p>
     * When called as fallback after local-git failure, uses {@code COALESCE}
     * semantics: additions/deletions/changedFiles pass null so the upsert
     * preserves any richer data already persisted by the local-git path.
     *
     * @param asFallback true when called as fallback after local-git failure,
     *                   which uses null for stats to preserve existing richer data
     */
    private void processCommitsViaWebhook(GitHubPushEventDTO event, Repository repository, boolean asFallback) {
        transactions.executeWithoutResult(status -> persistWebhookCommits(event, repository, asFallback));
    }

    private void persistWebhookCommits(GitHubPushEventDTO event, Repository repository, boolean asFallback) {
        String repoName = sanitizeForLog(repository.getNameWithOwner());
        int processed = 0;

        var commits = event.commits();
        if (commits == null) {
            return;
        }
        for (var webhookCommit : commits) {
            String message = extractHeadline(webhookCommit.message());
            String messageBody = extractBody(webhookCommit.message());
            // webhookCommit.url() returns the API URL (api.github.com/...),
            // not the browser-facing HTML URL; build it ourselves.
            String htmlUrl = buildCommitUrl(repository.getNameWithOwner(), webhookCommit.sha());
            Instant authoredAt = webhookCommit.timestamp() != null ? webhookCommit.timestamp() : Instant.now();

            // Count changed files from the file lists
            int added = webhookCommit.added() != null ? webhookCommit.added().size() : 0;
            int removed =
                    webhookCommit.removed() != null ? webhookCommit.removed().size() : 0;
            int modified =
                    webhookCommit.modified() != null ? webhookCommit.modified().size() : 0;
            int changedFiles = added + removed + modified;

            // Resolve author/committer by username
            Long providerId = repository.getProvider().getId();
            Long authorId = authorResolver.resolveByLogin(
                    webhookCommit.author() != null ? webhookCommit.author().username() : null, providerId);
            Long committerId = authorResolver.resolveByLogin(
                    webhookCommit.committer() != null
                            ? webhookCommit.committer().username()
                            : null,
                    providerId);

            commitRepository.upsertCommit(
                    webhookCommit.sha(),
                    message,
                    messageBody,
                    htmlUrl,
                    authoredAt,
                    authoredAt, // committedAt = authoredAt (webhook doesn't distinguish)
                    asFallback ? null : 0, // additions: null preserves existing richer data on fallback
                    asFallback ? null : 0, // deletions: null preserves existing richer data on fallback
                    asFallback ? null : changedFiles, // changedFiles: null preserves on fallback
                    Instant.now(),
                    repository.getId(),
                    authorId,
                    committerId,
                    webhookCommit.author() != null ? webhookCommit.author().email() : null,
                    webhookCommit.committer() != null
                            ? webhookCommit.committer().email()
                            : null);

            // Publish CommitCreated event after persisting
            publishCommitCreated(webhookCommit.sha(), repository);

            processed++;
        }

        log.info(
                "Processed push commits via webhook: processed={}, total={}, branch={}, fallback={}, repoName={}",
                processed,
                commits.size(),
                getBranchName(event.ref()),
                asFallback,
                repoName);
    }

    /**
     * Process a single commit from local git info.
     * <p>
     * Uses native {@code INSERT ... ON CONFLICT DO UPDATE} via
     * {@link CommitRepository#upsertCommit} for the commit row, then fetches
     * the persisted entity to attach file changes. This avoids the
     * {@code DataIntegrityViolationException} that would poison the
     * enclosing Spring transaction on duplicate inserts.
     */
    private boolean processCommitInfo(CommitDetails info, Repository repository) {
        // Fast-path: skip if already persisted (avoid building entity graph)
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

        // Publish CommitCreated event for new commits
        commitRepository.markGitDetailsCaptured(repository.getId(), info.sha(), Instant.now());
        if (newCommit) publishCommitCreated(info.sha(), repository);

        return true;
    }

    // Domain Event Publishing

    /**
     * Publishes a {@link ScmDomainEvent.CommitCreated} event for a newly persisted commit.
     * <p>
     * Looks up the persisted commit by SHA and repository ID to get its database ID,
     * then creates the event payload and publishes via {@link ApplicationEventPublisher}.
     * <p>
     * Since this runs inside the {@code TransactionTemplate} block from the base handler,
     * {@code @TransactionalEventListener(AFTER_COMMIT)} handlers will fire after the
     * transaction commits — matching the existing pattern.
     *
     * @param sha        the commit SHA
     * @param repository the repository entity (with organization eagerly loaded)
     */
    private void publishCommitCreated(String sha, Repository repository) {
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
                resolveScopeId(repository),
                RepositoryRef.from(repository),
                DataSource.WEBHOOK,
                null,
                UUID.randomUUID().toString(),
                IdentityProviderType.GITHUB);

        eventPublisher.publishEvent(new ScmDomainEvent.CommitCreated(commitData, context));
    }

    /**
     * Resolves the scope ID (workspace ID) for a repository.
     * <p>
     * Mirrors the resolution logic in {@link de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory}:
     * <ol>
     *   <li>For organization-owned repos: lookup by organization login</li>
     *   <li>For personal repos (no organization): lookup by repository nameWithOwner</li>
     *   <li>Fallback for org repos: if org lookup fails, try repository lookup</li>
     * </ol>
     *
     * @param repository the repository to resolve scope for
     * @return the scope ID, or null if no matching workspace found
     */
    @Nullable
    private Long resolveScopeId(Repository repository) {
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

    // Utility Methods

    private String getBranchName(String ref) {
        if (ref == null) return "unknown";
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        return ref;
    }

    private boolean isDefaultBranch(String ref, String defaultBranch) {
        if (ref == null || defaultBranch == null) return false;
        return getBranchName(ref).equals(defaultBranch);
    }

    private boolean isInitialPush(String sha) {
        return sha == null || ZERO_SHA.equals(sha);
    }

    private String extractHeadline(@Nullable String message) {
        if (message == null || message.isBlank()) return "";
        int newlineIndex = message.indexOf('\n');
        if (newlineIndex > 0) {
            return message.substring(0, newlineIndex).trim();
        }
        return message.trim();
    }

    private @Nullable String extractBody(@Nullable String message) {
        if (message == null || message.isBlank()) return null;
        int newlineIndex = message.indexOf('\n');
        if (newlineIndex > 0 && newlineIndex < message.length() - 1) {
            return message.substring(newlineIndex + 1).trim();
        }
        return null;
    }

    private String buildCommitUrl(String nameWithOwner, String sha) {
        return CommitUtils.buildCommitUrl(nameWithOwner, sha);
    }
}
