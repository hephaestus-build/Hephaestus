package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabProjectEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitLab project webhook events for real-time repository lifecycle updates.
 * <p>
 * Processes {@code project_create}, {@code project_destroy}, {@code project_rename},
 * and {@code project_transfer} events that are normalized to the "project" event key
 * by the webhook receiver.
 * <p>
 * These events are only available on group-level webhooks.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabProjectEventMessageHandler extends AbstractIntegrationMessageHandler<GitLabProjectEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabProjectEventMessageHandler.class);

    private final RepositoryRepository repositoryRepository;
    private final OrganizationRepository organizationRepository;
    private final IdentityProviderRepository gitProviderRepository;
    private final GitLabProperties gitLabProperties;

    GitLabProjectEventMessageHandler(
            RepositoryRepository repositoryRepository,
            OrganizationRepository organizationRepository,
            IdentityProviderRepository gitProviderRepository,
            GitLabProperties gitLabProperties,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.PROJECT.getValue(),
                GitLabProjectEventDTO.class,
                deserializer,
                transactionTemplate);
        this.repositoryRepository = repositoryRepository;
        this.organizationRepository = organizationRepository;
        this.gitProviderRepository = gitProviderRepository;
        this.gitLabProperties = gitLabProperties;
    }

    @Override
    protected void handleEvent(GitLabProjectEventDTO event) {
        String safePath = sanitizeForLog(Objects.requireNonNull(event.pathWithNamespace()));
        log.debug(
                "Received project event: eventName={}, path={}, projectId={}",
                event.eventName(),
                safePath,
                event.projectId());

        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, gitLabProperties.defaultServerUrl())
                .orElse(null);

        if (provider == null) {
            log.warn("IdentityProvider not found for GITLAB, skipping project event");
            return;
        }

        // On a connection route admission has already stored the project as GitLab reports it to the connection and
        // adjusted this workspace's monitor; the payload's id and path are never written, and the shared repository
        // row is never deleted, since GitLab not reporting a project to one connection does not prove it is gone.
        if (GitLabRouteAdmission.current().isPresent()) {
            return;
        }

        if (event.isCreation()) {
            handleProjectCreate(event, provider);
        } else if (event.isDeletion()) {
            handleProjectDestroy(event, provider);
        } else if (event.isRename() || event.isTransfer()) {
            handleProjectRenameOrTransfer(event, provider);
        } else {
            log.debug("Unhandled project event action: eventName={}", event.eventName());
        }
    }

    private void handleProjectCreate(GitLabProjectEventDTO event, IdentityProvider provider) {
        Long providerId = Objects.requireNonNull(provider.getId());
        long nativeId = event.projectId();

        // Check if already exists
        if (repositoryRepository
                .findByNativeIdAndProviderId(nativeId, providerId)
                .isPresent()) {
            log.debug("Repository already exists, skipping create: nativeId={}", nativeId);
            return;
        }

        // Create new repository entity
        Repository repo = new Repository();
        repo.setNativeId(nativeId);
        repo.setProvider(provider);
        repo.setName(event.name());
        repo.setNameWithOwner(Objects.requireNonNull(event.pathWithNamespace()));
        repo.setPrivate("private".equalsIgnoreCase(event.projectVisibility()));

        // Construct HTML URL from server URL + path
        String serverUrl = gitLabProperties.defaultServerUrl();
        if (serverUrl != null && event.pathWithNamespace() != null) {
            String baseUrl = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
            repo.setHtmlUrl(baseUrl + Objects.requireNonNull(event.pathWithNamespace()));
        }

        // Link to organization if the group is already synced
        String groupPath = extractGroupPath(Objects.requireNonNull(event.pathWithNamespace()));
        if (groupPath != null) {
            Organization org = organizationRepository
                    .findByLoginIgnoreCaseAndProviderId(groupPath, providerId)
                    .orElse(null);
            if (org != null) {
                repo.setOrganization(org);
            }
        }

        repositoryRepository.save(repo);
        log.info(
                "Created repository from project event: nativeId={}, path={}",
                nativeId,
                sanitizeForLog(Objects.requireNonNull(event.pathWithNamespace())));
    }

    private void handleProjectDestroy(GitLabProjectEventDTO event, IdentityProvider provider) {
        Long providerId = Objects.requireNonNull(provider.getId());
        long nativeId = event.projectId();

        repositoryRepository
                .findByNativeIdAndProviderId(nativeId, providerId)
                .ifPresentOrElse(
                        repo -> {
                            repositoryRepository.delete(repo);
                            log.info(
                                    "Deleted repository from project event: nativeId={}, path={}",
                                    nativeId,
                                    sanitizeForLog(Objects.requireNonNull(event.pathWithNamespace())));
                        },
                        () -> log.debug(
                                "Repository not found for deletion: nativeId={}, path={}",
                                nativeId,
                                sanitizeForLog(Objects.requireNonNull(event.pathWithNamespace()))));
    }

    private void handleProjectRenameOrTransfer(GitLabProjectEventDTO event, IdentityProvider provider) {
        Long providerId = Objects.requireNonNull(provider.getId());
        long nativeId = event.projectId();

        repositoryRepository
                .findByNativeIdAndProviderId(nativeId, providerId)
                .ifPresentOrElse(
                        repo -> {
                            String oldPath = repo.getNameWithOwner();
                            String newPath = event.pathWithNamespace();
                            String newName = event.name();

                            if (newPath != null) {
                                repo.setNameWithOwner(newPath);
                            }
                            if (newName != null) {
                                repo.setName(newName);
                            }

                            // Update HTML URL
                            String serverUrl = gitLabProperties.defaultServerUrl();
                            if (serverUrl != null && newPath != null) {
                                String baseUrl = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
                                repo.setHtmlUrl(baseUrl + newPath);
                            }

                            // Update visibility if provided
                            if (event.projectVisibility() != null) {
                                repo.setPrivate("private".equalsIgnoreCase(event.projectVisibility()));
                            }

                            // Re-link organization for transfers (new group may differ)
                            if (event.isTransfer()) {
                                String groupPath = extractGroupPath(newPath);
                                if (groupPath != null) {
                                    Organization org = organizationRepository
                                            .findByLoginIgnoreCaseAndProviderId(groupPath, providerId)
                                            .orElse(null);
                                    if (org != null) {
                                        repo.setOrganization(org);
                                    }
                                }
                            }

                            repositoryRepository.save(repo);
                            log.info(
                                    "Updated repository from {} event: nativeId={}, oldPath={}, newPath={}",
                                    event.eventName(),
                                    nativeId,
                                    sanitizeForLog(oldPath),
                                    sanitizeForLog(newPath));
                        },
                        () -> log.debug(
                                "Repository not found for {}: nativeId={}, path={}",
                                event.eventName(),
                                nativeId,
                                sanitizeForLog(Objects.requireNonNull(event.pathWithNamespace()))));
    }

    /**
     * Extracts the parent group path from a project's path_with_namespace.
     */
    @org.jspecify.annotations.Nullable
    private static String extractGroupPath(@org.jspecify.annotations.Nullable String pathWithNamespace) {
        if (pathWithNamespace == null || pathWithNamespace.isBlank()) {
            return null;
        }
        int lastSlash = pathWithNamespace.lastIndexOf('/');
        if (lastSlash <= 0) {
            return null;
        }
        return pathWithNamespace.substring(0, lastSlash);
    }
}
