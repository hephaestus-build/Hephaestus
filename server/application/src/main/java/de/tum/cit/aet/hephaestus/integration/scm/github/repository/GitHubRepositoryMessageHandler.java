package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore.Observation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * every workspace's monitor — by the provider-stable {@code repository.id}, then the provider inbox
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
@ConditionalOnServerRole
public class GitHubRepositoryMessageHandler extends AbstractIntegrationMessageHandler<GitHubRepositoryEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubRepositoryMessageHandler.class);

    private static final String GITHUB_SERVER_URL = "https://github.com";

    private final ProvisioningListener provisioningListener;
    private final RepositoryRepository repositoryRepository;
    private final RepositoryObservationStore observations;
    private final IdentityProviderLookup identityProviders;
    private final SyncTargetProvider syncTargetProvider;

    GitHubRepositoryMessageHandler(
            ProvisioningListener provisioningListener,
            RepositoryRepository repositoryRepository,
            RepositoryObservationStore observations,
            IdentityProviderLookup identityProviders,
            SyncTargetProvider syncTargetProvider,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                // Org tier, not repository tier — see the class javadoc. Must stay in lockstep with
                // GitHubSubjectKeyDeriver's org-scoping of the `repository` event: registering this
                // on `repository.` again makes every repository lifecycle event resolve no handler
                // and be ACK-dropped silently (WebhookFixtureHandlerResolutionTest guards exactly
                // that).
                "organization." + GitHubEventType.REPOSITORY.getValue(),
                GitHubRepositoryEventDTO.class,
                deserializer,
                transactionTemplate);
        this.provisioningListener = provisioningListener;
        this.repositoryRepository = repositoryRepository;
        this.observations = observations;
        this.identityProviders = identityProviders;
        this.syncTargetProvider = syncTargetProvider;
    }

    @Override
    protected void dispatchEvent(GitHubRepositoryEventDTO event, Instant arrivedAt) {
        handleOnce(() -> handleEvent(event, arrivedAt));
    }

    @Override
    protected void handleEvent(GitHubRepositoryEventDTO event) {
        handleEvent(event, Instant.EPOCH);
    }

    private void handleEvent(GitHubRepositoryEventDTO event, Instant arrivedAt) {
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
            case DELETED -> handleRepositoryDeleted(event, fullName, safeFullName, arrivedAt);
            case ARCHIVED ->
                handleRepositoryArchived(
                        repositoryRef.id(), fullName, safeFullName, true, repositoryRef.isPrivate(), arrivedAt);
            case UNARCHIVED ->
                handleRepositoryArchived(
                        repositoryRef.id(), fullName, safeFullName, false, repositoryRef.isPrivate(), arrivedAt);
            // A transfer carries the same payload shape as a rename (only the changed component
            // differs: changes.owner vs changes.repository.name) and needs the identical healing.
            case RENAMED, TRANSFERRED -> handleRepositoryMoved(event, safeFullName, arrivedAt);
            case PRIVATIZED -> handlePrivatized(repositoryRef.id(), fullName, arrivedAt);
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
    private void handleRepositoryDeleted(
            GitHubRepositoryEventDTO event, String fullName, String safeFullName, Instant arrivedAt) {
        var installation = event.installation();

        if (installation == null || installation.id() == null) {
            log.warn("Cannot clean up deleted repository - missing installation ID: repoName={}", safeFullName);
            deleteRepository(
                    event.repository() == null ? null : event.repository().id(), fullName, safeFullName, arrivedAt);
            return;
        }

        Long installationId = installation.id();

        log.info("Processing repository deletion: repoName={}, installationId={}", safeFullName, installationId);

        // The mirror row stays while another workspace monitors the repository. A deleted repository is visible to no
        // one, so the row stops showing it public, which narrows every workspace's monitor in the same write.
        handlePrivatized(event.repository() == null ? null : event.repository().id(), fullName, arrivedAt);

        provisioningListener.onRepositoriesRemoved(
                installationId,
                List.of(new ProvisioningListener.RepositoryRemoval(
                        event.repository() == null ? null : event.repository().id(), fullName)));

        log.info("Completed repository deletion cleanup: repoName={}, installationId={}", safeFullName, installationId);
    }

    /**
     * Fallback deletion when installation ID is not available.
     */
    private void deleteRepository(@Nullable Long nativeId, String fullName, String safeFullName, Instant arrivedAt) {
        findGitHubRepository(nativeId, fullName).ifPresent(repository -> {
            observations.observe(
                    RepositoryObservationStore.providerEvent(githubProviderId(), repository.getNativeId(), arrivedAt),
                    Observation.deleted());
            log.info("Deleted repository directly: repoName={}", safeFullName);
        });
    }

    private void handleRepositoryArchived(
            @Nullable Long nativeId,
            String fullName,
            String safeFullName,
            boolean archived,
            boolean isPrivate,
            Instant arrivedAt) {
        findGitHubRepository(nativeId, fullName)
                .ifPresentOrElse(
                        repository -> {
                            var read = RepositoryObservationStore.providerEvent(
                                    githubProviderId(), repository.getNativeId(), arrivedAt);
                            var patch = Map.<String, @Nullable Object>of("is_archived", archived);
                            var stored = observations.observe(
                                    read,
                                    isPrivate
                                            ? Observation.read(read, Repository.Visibility.PRIVATE, patch)
                                            : Observation.metadata(patch));
                            if (!stored.applied()) return;
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

    private long githubProviderId() {
        return identityProviders
                .findId(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                .orElseThrow();
    }

    private Optional<Repository> findGitHubRepository(@Nullable Long nativeId, String nameWithOwner) {
        return identityProviders
                .findId(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                .flatMap(provider -> nativeId == null
                        ? repositoryRepository.findByNameWithOwnerAndProviderId(
                                nameWithOwner, Objects.requireNonNull(provider))
                        : repositoryRepository.findByNativeIdAndProviderId(nativeId, Objects.requireNonNull(provider)));
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
     *       inbox consumer filters. Without step 2 the repo-scoped filter stays pinned to the old name
     *       and every subsequent issue/PR/review/push event for the repository is silently ACK-dropped
     *       until the next reconcile cycle.
     * </ol>
     * <b>Residual:</b> a transfer to a <em>different</em> owner derives the new owner's subject and so
     * never reaches the old workspace's org filter; that case heals on the next reconcile pass, which
     * resolves the monitor by {@code nativeId}.
     */
    private void handleRepositoryMoved(GitHubRepositoryEventDTO event, String safeNewFullName, Instant arrivedAt) {
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

        // Native ids and repository names are unique only within a provider instance.
        Long providerId = identityProviders
                .findId(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                .orElse(null);
        if (providerId == null) {
            log.debug(
                    "No provider registered for repository {}; nothing of it is mirrored: newRepoName={}",
                    event.action(),
                    safeNewFullName);
            return;
        }

        var resolved = resolveMovedRepository(providerId, nativeId, previousNameWithOwner);
        if (resolved.isPresent()) {
            var repository = resolved.orElseThrow();
            var read = RepositoryObservationStore.providerEvent(providerId, repository.getNativeId(), arrivedAt);
            var patch = RepositoryObservationStore.path(newFullName, newName, GITHUB_SERVER_URL + "/" + newFullName);
            var result = observations.observe(
                    read,
                    repositoryRef.isPrivate()
                            ? Observation.read(read, Repository.Visibility.PRIVATE, patch)
                            : Observation.metadata(patch));
            if (!result.applied()) return;
            log.info(
                    "Renamed repository: action={}, oldName={}, newName={}, nativeId={}",
                    event.action(),
                    safeOldFullName,
                    safeNewFullName,
                    nativeId);
        }

        // Always attempt the monitor re-key, even when no domain row is mirrored yet: the monitor
        // is what the provider inbox filter is built from, so leaving it stale is the actual data-loss path.
        syncTargetProvider.reconcileSyncTargetsForRepository(providerId, nativeId, newFullName);
    }

    /**
     * Locates this provider's mirrored row for a repository whose name has just moved. Prefers the stable
     * {@code (nativeId, providerId)} key — the only identity a transfer preserves — and falls back to
     * the previous {@code owner/name} on the same provider only when the event itself has no native id.
     */
    private Optional<Repository> resolveMovedRepository(
            Long providerId, @Nullable Long nativeId, @Nullable String previousNameWithOwner) {
        if (nativeId != null) {
            return repositoryRepository.findByNativeIdAndProviderId(nativeId, providerId);
        }
        return previousNameWithOwner == null
                ? Optional.empty()
                : repositoryRepository.findByNameWithOwnerAndProviderId(previousNameWithOwner, providerId);
    }

    private void handlePrivatized(@Nullable Long nativeId, String fullName, Instant arrivedAt) {
        Long resolved = nativeId != null
                ? nativeId
                : findGitHubRepository(null, fullName)
                        .map(Repository::getNativeId)
                        .orElse(null);
        Long providerId = identityProviders
                .findId(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                .orElse(null);
        if (providerId != null && resolved != null) {
            observations.observe(
                    RepositoryObservationStore.providerEvent(providerId, resolved, arrivedAt),
                    Observation.privateRepository());
        }
    }
}
