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
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister.Outcome;
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
    private final CommitDetailsPersister persister;
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
            CommitDetailsPersister persister,
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
        this.persister = persister;
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

        if (event.deleted()) {
            log.debug("Skipped push event: reason=branchDeleted, ref={}, repoName={}", event.ref(), repoName);
            return;
        }

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

        int[] failed = {0};
        try {
            String cloneUrl = "https://github.com/" + repository.getNameWithOwner() + ".git";
            String token = null;
            if (event.installation() != null && tokenService.isConfigured()) {
                token = tokenService.getInstallationToken(event.installation().id());
            }
            gitRepositoryManager.ensureRepository(key, cloneUrl, token);

            Long providerId = repository.getProvider().getId();
            var origin = new CommitDetailsPersister.Origin(
                    scopeId,
                    DataSource.WEBHOOK,
                    IdentityProviderType.GITHUB,
                    sha -> CommitUtils.buildCommitUrl(repository.getNameWithOwner(), sha),
                    email -> authorResolver.resolveByEmail(email, providerId));
            gitRepositoryManager.forEachCommitInRange(
                    key,
                    isInitialPush(beforeSha) ? null : beforeSha,
                    afterSha,
                    shas -> commitRepository.findGitDetailsCapturedShas(repository.getId(), shas),
                    info -> {
                        if (persister.persist(info, repository, origin) == Outcome.FAILED) failed[0]++;
                    });

            log.info("Processed push commits via local git: repoName={}, failed={}", repoName, failed[0]);
        } catch (Exception e) {
            log.error(
                    "Failed to process commits via local git, falling back to webhook: repoName={}, error={}",
                    repoName,
                    e.getMessage());
            failed[0]++;
        }
        if (failed[0] > 0) processCommitsViaWebhook(event, repository, true);
    }

    /**
     * Persists the push payload's commits, which carry file lists but no line statistics. As the
     * fallback after a local-git failure the statistics are withheld so {@code COALESCE} keeps what
     * native Git already captured.
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
            String message =
                    CommitDetailsPersister.fit(extractHeadline(webhookCommit.message()), Commit.MESSAGE_LENGTH);
            String messageBody = extractBody(webhookCommit.message());
            // webhookCommit.url() returns the API URL (api.github.com/...),
            // not the browser-facing HTML URL; build it ourselves.
            String htmlUrl = buildCommitUrl(repository.getNameWithOwner(), webhookCommit.sha());
            Instant authoredAt = webhookCommit.timestamp() != null ? webhookCommit.timestamp() : Instant.now();

            int added = webhookCommit.added() != null ? webhookCommit.added().size() : 0;
            int removed =
                    webhookCommit.removed() != null ? webhookCommit.removed().size() : 0;
            int modified =
                    webhookCommit.modified() != null ? webhookCommit.modified().size() : 0;
            int changedFiles = added + removed + modified;

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
                    asFallback ? null : 0,
                    asFallback ? null : 0,
                    asFallback ? null : changedFiles,
                    Instant.now(),
                    repository.getId(),
                    authorId,
                    committerId,
                    webhookCommit.author() != null ? webhookCommit.author().email() : null,
                    webhookCommit.committer() != null
                            ? webhookCommit.committer().email()
                            : null,
                    null);

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

    // Domain Event Publishing

    /** Publishes {@link ScmDomainEvent.CommitCreated} for a commit persisted from webhook data. */
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
