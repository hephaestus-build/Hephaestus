package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.util.CommitUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit.GitLabCommitMergeRequestLinker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO.CommitInfo;
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
 * Handles GitLab push webhook events.
 * <p>
 * On each push event, upserts the project as a {@link Repository}
 * using the embedded project metadata from the webhook payload. This ensures the repository
 * entity exists before any commit processing.
 * <p>
 * When local git checkout is enabled ({@code hephaestus.git.enabled=true}), pushes to the
 * default branch trigger a local clone/fetch and native Git commit walk, providing line-level
 * diff statistics (additions/deletions per file). Falls back to webhook-only processing
 * on error or for non-default branches.
 * <p>
 * Also ensures the parent group is linked as an Organization via DB lookup.
 * If the organization doesn't exist yet (push arrives before full sync), it is left unlinked
 * and will be resolved during the next scheduled sync.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabPushMessageHandler extends AbstractIntegrationMessageHandler<GitLabPushEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabPushMessageHandler.class);
    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";

    private final GitLabProjectProcessor projectProcessor;
    private final OrganizationRepository organizationRepository;
    private final RepositoryRepository repositoryRepository;
    private final CommitRepository commitRepository;
    private final CommitDetailsPersister persister;
    private final IdentityProviderRepository gitProviderRepository;
    private final GitLabProperties gitLabProperties;
    private final GitRepositoryManager gitRepositoryManager;
    private final GitLabTokenService tokenService;
    private final CommitAuthorResolver authorResolver;
    private final ScopeIdResolver scopeIdResolver;
    private final SyncTargetProvider syncTargetProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final GitLabCommitMergeRequestLinker commitMergeRequestLinker;

    private final TransactionTemplate transactions;

    GitLabPushMessageHandler(
            GitLabProjectProcessor projectProcessor,
            OrganizationRepository organizationRepository,
            RepositoryRepository repositoryRepository,
            CommitRepository commitRepository,
            CommitDetailsPersister persister,
            IdentityProviderRepository gitProviderRepository,
            GitLabProperties gitLabProperties,
            GitRepositoryManager gitRepositoryManager,
            GitLabTokenService tokenService,
            CommitAuthorResolver authorResolver,
            ScopeIdResolver scopeIdResolver,
            SyncTargetProvider syncTargetProvider,
            ApplicationEventPublisher eventPublisher,
            GitLabCommitMergeRequestLinker commitMergeRequestLinker,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.PUSH.getValue(),
                GitLabPushEventDTO.class,
                deserializer,
                transactionTemplate);
        this.projectProcessor = projectProcessor;
        this.organizationRepository = organizationRepository;
        this.repositoryRepository = repositoryRepository;
        this.commitRepository = commitRepository;
        this.persister = persister;
        this.gitProviderRepository = gitProviderRepository;
        this.gitLabProperties = gitLabProperties;
        this.gitRepositoryManager = gitRepositoryManager;
        this.tokenService = tokenService;
        this.authorResolver = authorResolver;
        this.scopeIdResolver = scopeIdResolver;
        this.syncTargetProvider = syncTargetProvider;
        this.eventPublisher = eventPublisher;
        this.commitMergeRequestLinker = commitMergeRequestLinker;
        this.transactions = transactionTemplate;
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

        var repository = transactions.execute(status -> {
            IdentityProvider provider = gitProviderRepository
                    .findByTypeAndServerUrl(IdentityProviderType.GITLAB, gitLabProperties.defaultServerUrl())
                    .orElseThrow(() -> new IllegalStateException("GitLab identity provider is unavailable"));
            var prepared = projectProcessor.processPushEvent(event.project(), provider);
            if (prepared == null) return null;
            ensureOrganizationLinked(prepared, projectPath, provider);
            return repositoryRepository
                    .findByIdWithOrganization(prepared.getId())
                    .orElseThrow();
        });

        if (repository != null) {
            log.debug(
                    "Upserted project from push event: projectPath={}, repoId={}", safeProjectPath, repository.getId());

            Long scopeId = resolveScopeId(repository);

            // Only a default-branch push is walked for line-level statistics; every other push still
            // fetches so practice reviews see fresh refs.
            if (event.isDefaultBranch() && gitRepositoryManager.isEnabled()) {
                boolean scopeActive = scopeId != null && syncTargetProvider.isScopeActiveForSync(scopeId);

                if (scopeActive) {
                    processCommitsViaLocalGit(event, repository, Objects.requireNonNull(scopeId));
                } else {
                    log.debug("Skipped local git: reason=scopeNotActive, scopeId={}", scopeId);
                    processCommitsViaWebhook(event, repository, false);
                }
            } else {
                if (gitRepositoryManager.isEnabled()) fetchForNonDefaultBranch(event, repository);
                processCommitsViaWebhook(event, repository, false);
            }

            linkCommitsToMergeRequests(scopeId, repository);
        } else {
            log.warn("Failed to upsert project from push event: projectPath={}", safeProjectPath);
        }
    }

    /** One GraphQL round trip links every MR updated in the push window, rather than one call per commit. */
    private void linkCommitsToMergeRequests(@Nullable Long scopeId, Repository repository) {
        if (scopeId == null) {
            return;
        }
        try {
            commitMergeRequestLinker.linkCommits(
                    scopeId, repository, OffsetDateTime.now().minusHours(1));
        } catch (Exception e) {
            log.debug("Push-time commit→MR link failed: repoId={}, error={}", repository.getId(), e.getMessage());
        }
    }

    /** Fetches so the mirror is current for practice reviews; commits on other branches are not walked. */
    private void fetchForNonDefaultBranch(GitLabPushEventDTO event, Repository repository) {
        try {
            Long scopeId = resolveScopeId(repository);
            if (scopeId == null || !syncTargetProvider.isScopeActiveForSync(scopeId)) {
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
                    e.getMessage());
        }
    }

    // Local git path (enriched with line-level diff stats)

    private void processCommitsViaLocalGit(GitLabPushEventDTO event, Repository repository, Long scopeId) {
        String repoName = sanitizeForLog(repository.getNameWithOwner());
        String beforeSha = event.before();
        String afterSha = event.after();
        if (afterSha == null) {
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
        // Webhook data keeps the row for a commit whose capture failed; COALESCE preserves the rest.
        if (failed[0] > 0) processCommitsViaWebhook(event, repository, true);
    }

    // Webhook-only path (file lists without line-level stats)

    /**
     * Persists the push payload's commits, one transaction each so one bad commit does not roll back
     * its neighbours. As the fallback after a local-git failure the statistics and file lists are
     * withheld so {@code COALESCE} keeps what native Git already captured.
     */
    private void processCommitsViaWebhook(GitLabPushEventDTO event, Repository repository, boolean asFallback) {
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
                transactions.executeWithoutResult(status -> persistWebhookCommit(commit, repository, asFallback));
                created++;
            } catch (Exception e) {
                log.warn(
                        "Failed to upsert commit: sha={}, repoId={}, error={}",
                        commit.id(),
                        repository.getId(),
                        e.getMessage());
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

    private void persistWebhookCommit(CommitInfo commit, Repository repository, boolean asFallback) {
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
        publishCommitCreated(sha, repository);
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

    private void publishCommitCreated(String sha, Repository repository) {
        Commit commit = commitRepository
                .findByShaAndRepositoryId(sha, repository.getId())
                .orElse(null);
        if (commit == null) {
            return;
        }

        Long scopeId = resolveScopeId(repository);

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

    // Organization linking

    private void ensureOrganizationLinked(Repository repository, String projectPath, IdentityProvider provider) {
        if (repository.getOrganization() != null) {
            return;
        }

        String groupPath = extractGroupPath(projectPath);
        if (groupPath == null) {
            return;
        }

        Organization org = organizationRepository
                .findByLoginIgnoreCaseAndProviderId(groupPath, Objects.requireNonNull(provider.getId()))
                .orElse(null);

        if (org != null) {
            repository.setOrganization(org);
            repositoryRepository.save(repository);
            log.debug(
                    "Linked org to repository: repoId={}, orgLogin={}", repository.getId(), sanitizeForLog(groupPath));
        } else {
            log.debug("Organization not yet synced: groupPath={}", sanitizeForLog(groupPath));
        }
    }

    // Utility methods

    @Nullable
    static String extractGroupPath(@Nullable String projectPath) {
        if (projectPath == null || projectPath.isBlank()) return null;
        int lastSlash = projectPath.lastIndexOf('/');
        return lastSlash <= 0 ? null : projectPath.substring(0, lastSlash);
    }

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
