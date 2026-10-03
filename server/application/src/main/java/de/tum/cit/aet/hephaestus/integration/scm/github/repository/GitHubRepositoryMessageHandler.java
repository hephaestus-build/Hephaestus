package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.ProjectIntegrityService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub repository webhook events: deletion, archive state, visibility, and
 * rename/transfer. Renamed/transferred repositories are re-keyed — the mirrored repository AND
 * every workspace's monitor — by the provider-stable {@code repository.id}, then the NATS
 * consumer filters are rebuilt.
 *
 * <p><b>Subject tier.</b> Registered on the {@code organization.} tier: {@code GitHubSubjectKeyDeriver}
 * emits {@code repository} lifecycle events as {@code github.<owner>.?.repository} because the repo-name
 * token is unstable across rename/transfer (a repo-tier subject built from the NEW name matches no
 * monitored-repo filter and is silently ACK-dropped). Nothing here consumes repository-tier subject
 * context — the handler is driven entirely by the payload — so the tier move costs it nothing and buys
 * delivery of events for repositories whose name has already moved.
 *
 * @see <a href="https://docs.github.com/en/webhooks/webhook-events-and-payloads#repository">
 *      GitHub Repository Webhook Events</a>
 */
@Component
public class GitHubRepositoryMessageHandler extends AbstractIntegrationMessageHandler<GitHubRepositoryEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubRepositoryMessageHandler.class);

    private static final String GITHUB_SERVER_URL = "https://github.com";

    private final ProvisioningListener provisioningListener;
    private final RepositoryRepository repositoryRepository;
    private final ProjectIntegrityService projectIntegrityService;
    private final IdentityProviderRepository gitProviderRepository;
    private final SyncTargetProvider syncTargetProvider;

    GitHubRepositoryMessageHandler(
            ProvisioningListener provisioningListener,
            RepositoryRepository repositoryRepository,
            ProjectIntegrityService projectIntegrityService,
            IdentityProviderRepository gitProviderRepository,
            SyncTargetProvider syncTargetProvider,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                // Org tier, not repository tier — see the class javadoc. Must stay in lockstep with
                // GitHubSubjectKeyDeriver's org-scoping of the `repository` event: registering this on
                // `repository.` again makes every repository lifecycle event resolve no handler and be
                // ACK-dropped silently (WebhookFixtureHandlerResolutionTest guards exactly that).
                "organization." + GitHubEventType.REPOSITORY.getValue(),
                GitHubRepositoryEventDTO.class,
                deserializer,
                transactionTemplate);
        this.provisioningListener = provisioningListener;
        this.repositoryRepository = repositoryRepository;
        this.projectIntegrityService = projectIntegrityService;
        this.gitProviderRepository = gitProviderRepository;
        this.syncTargetProvider = syncTargetProvider;
    }

    @Override
    protected void handleEvent(GitHubRepositoryEventDTO event) {
        var repositoryRef = event.repository();

        if (repositoryRef == null) {
            log.warn("Received repository event with missing repository data: action={}", event.action());
            return;
        }

        String fullName = repositoryRef.fullName();
        if (fullName == null) {
            log.warn("Received repository event with missing repository name: action={}", event.action());
            return;
        }
        String safeFullName = Objects.requireNonNull(sanitizeForLog(fullName));
        Long repositoryId = repositoryRef.id();
        GitHubEventAction.Repository action = event.actionType();

        log.debug(
                "Received repository event: action={}, repoName={}, repoId={}",
                event.action(),
                safeFullName,
                repositoryId);

        switch (action) {
            case DELETED -> handleRepositoryDeleted(event, fullName, safeFullName);
            case ARCHIVED -> handleRepositoryArchived(fullName, safeFullName, true);
            case UNARCHIVED -> handleRepositoryArchived(fullName, safeFullName, false);
            // A transfer carries the same payload shape as a rename (only the changed component
            // differs: changes.owner vs changes.repository.name) and needs the identical healing.
            case RENAMED, TRANSFERRED -> handleRepositoryMoved(event, safeFullName);
            case PRIVATIZED -> handleVisibilityChanged(fullName, safeFullName, true);
            case PUBLICIZED -> handleVisibilityChanged(fullName, safeFullName, false);
            default ->
                log.debug(
                        "Skipped repository event: reason=unhandledAction, action={}, repoName={}",
                        event.action(),
                        safeFullName);
        }
    }

    /**
     * Cleans up local state on repository deletion, via the ProvisioningListener SPI, preventing
     * future sync attempts from failing with NOT_FOUND errors.
     */
    private void handleRepositoryDeleted(GitHubRepositoryEventDTO event, String fullName, String safeFullName) {
        var installation = event.installation();

        if (installation == null || installation.id() == null) {
            log.warn("Cannot clean up deleted repository - missing installation ID: repoName={}", safeFullName);
            deleteRepositoryByName(fullName, safeFullName);
            return;
        }

        Long installationId = installation.id();

        log.info("Processing repository deletion: repoName={}, installationId={}", safeFullName, installationId);

        provisioningListener.onRepositoriesRemoved(installationId, List.of(fullName));

        log.info("Completed repository deletion cleanup: repoName={}, installationId={}", safeFullName, installationId);
    }

    /**
     * Fallback deletion when installation ID is not available.
     * <p>
     * Cascades deletion to projects owned by this repository before deleting
     * the repository itself. This maintains referential integrity for the
     * polymorphic project ownership model.
     */
    private void deleteRepositoryByName(String fullName, String safeFullName) {
        repositoryRepository.findByNameWithOwner(fullName).ifPresent(repository -> {
            Long repoId = repository.getId();

            int deletedProjects = projectIntegrityService.cascadeDeleteProjectsForRepository(repoId);
            if (deletedProjects > 0) {
                log.info(
                        "Cascade deleted projects for repository: repoId={}, repoName={}, projectCount={}",
                        repoId,
                        safeFullName,
                        deletedProjects);
            }

            repositoryRepository.delete(repository);
            log.info("Deleted repository directly: repoName={}", safeFullName);
        });
    }

    private void handleRepositoryArchived(String fullName, String safeFullName, boolean archived) {
        repositoryRepository
                .findByNameWithOwner(fullName)
                .ifPresentOrElse(
                        repository -> {
                            repository.setArchived(archived);
                            repositoryRepository.save(repository);
                            log.info(
                                    "Updated repository archived status: repoName={}, archived={}",
                                    safeFullName,
                                    archived);
                        },
                        () -> log.debug(
                                "Repository not found locally for archive update: repoName={}, archived={}",
                                safeFullName,
                                archived));
    }

    /**
     * Heals a rename or transfer in real time.
     * <p>
     * Two rows go stale at once and both are re-keyed off the provider-stable {@code repository.id},
     * never off the name (the name is precisely the value that just moved):
     * <ol>
     *   <li>the mirrored domain {@code Repository}, so name-keyed sync lookups keep resolving;</li>
     *   <li>every {@code RepositoryToMonitor} tracking this repository — across <em>all</em> workspaces,
     *       since a repository can be monitored by several tenants — via
     *       {@code reconcileSyncTargetsForRepository}, which also rebuilds each affected workspace's
     *       NATS consumer filters. Without step 2 the repo-scoped filter stays pinned to the old name
     *       and every subsequent issue/PR/review/push event for the repository is silently ACK-dropped
     *       until the next reconcile cycle.
     * </ol>
     * <b>Residual:</b> a transfer to a <em>different</em> owner derives the new owner's subject and so
     * never reaches the old workspace's org filter; that case heals on the next reconcile pass, which
     * resolves the monitor by {@code nativeId}.
     */
    private void handleRepositoryMoved(GitHubRepositoryEventDTO event, String safeNewFullName) {
        GitHubRepositoryRefDTO repositoryRef = event.repository();
        if (repositoryRef == null) {
            return;
        }
        String newFullName = repositoryRef.fullName();
        String newName = repositoryRef.name();
        Long nativeId = repositoryRef.id();
        String previousNameWithOwner = event.getPreviousNameWithOwner();
        String safeOldFullName = sanitizeForLog(previousNameWithOwner);

        if (nativeId == null && previousNameWithOwner == null) {
            log.warn(
                    "Cannot process repository {} - payload carries neither a repository id nor a previous name: newRepoName={}",
                    event.action(),
                    safeNewFullName);
            return;
        }

        resolveMovedRepository(nativeId, previousNameWithOwner)
                .ifPresentOrElse(
                        repository -> {
                            repository.setName(newName);
                            repository.setNameWithOwner(newFullName);
                            repository.setHtmlUrl(GITHUB_SERVER_URL + "/" + newFullName);
                            repositoryRepository.save(repository);
                            log.info(
                                    "Renamed repository: action={}, oldName={}, newName={}, nativeId={}",
                                    event.action(),
                                    safeOldFullName,
                                    safeNewFullName,
                                    nativeId);
                        },
                        () -> log.debug(
                                "Repository not found locally for {}: oldName={}, newName={}, nativeId={}",
                                event.action(),
                                safeOldFullName,
                                safeNewFullName,
                                nativeId));

        // Always attempt the monitor re-key, even when no domain row is mirrored yet: the monitor is
        // what the NATS filter is built from, so leaving it stale is the actual data-loss path.
        syncTargetProvider.reconcileSyncTargetsForRepository(nativeId, newFullName);
    }

    /**
     * Locates the mirrored row for a repository whose name has just moved. Prefers the stable
     * {@code (nativeId, providerId)} key — the only identity a transfer preserves — and falls back to
     * the previous {@code owner/name} for legacy rows whose {@code native_id} was never captured.
     */
    private Optional<Repository> resolveMovedRepository(
            @Nullable Long nativeId, @Nullable String previousNameWithOwner) {
        if (nativeId != null) {
            Long providerId = gitProviderRepository
                    .findByTypeAndServerUrl(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                    .map(IdentityProvider::getId)
                    .orElse(null);
            if (providerId != null) {
                Optional<Repository> byNativeId =
                        repositoryRepository.findByNativeIdAndProviderId(nativeId, providerId);
                if (byNativeId.isPresent()) {
                    return byNativeId;
                }
            }
        }
        return previousNameWithOwner == null
                ? Optional.empty()
                : repositoryRepository.findByNameWithOwner(previousNameWithOwner);
    }

    private void handleVisibilityChanged(String fullName, String safeFullName, boolean isPrivate) {
        repositoryRepository
                .findByNameWithOwner(fullName)
                .ifPresentOrElse(
                        repository -> {
                            repository.setVisibility(
                                    isPrivate ? Repository.Visibility.PRIVATE : Repository.Visibility.PUBLIC);
                            repositoryRepository.save(repository);
                            log.info(
                                    "Updated repository visibility: repoName={}, visibility={}",
                                    safeFullName,
                                    isPrivate ? "PRIVATE" : "PUBLIC");
                        },
                        () -> log.debug(
                                "Repository not found locally for visibility update: repoName={}, private={}",
                                safeFullName,
                                isPrivate));
    }
}
