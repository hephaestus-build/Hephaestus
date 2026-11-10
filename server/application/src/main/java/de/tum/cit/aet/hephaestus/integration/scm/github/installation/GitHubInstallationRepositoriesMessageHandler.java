package de.tum.cit.aet.hephaestus.integration.scm.github.installation;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener.RepositorySnapshot;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.dto.GitHubInstallationRepositoriesEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub installation_repositories webhook events.
 * <p>
 * When repositories are added or removed from a GitHub App installation,
 * this handler notifies the consuming module via SPI to update monitored repositories.
 */
@Component
@ConditionalOnServerRole
public class GitHubInstallationRepositoriesMessageHandler
        extends AbstractIntegrationMessageHandler<GitHubInstallationRepositoriesEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationRepositoriesMessageHandler.class);

    private final ProvisioningListener provisioningListener;

    GitHubInstallationRepositoriesMessageHandler(
            EventPayloadDeserializer deserializer,
            ProvisioningListener provisioningListener,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "installation." + GitHubEventType.INSTALLATION_REPOSITORIES.getValue(),
                GitHubInstallationRepositoriesEventDTO.class,
                deserializer,
                transactionTemplate);
        this.provisioningListener = provisioningListener;
    }

    @Override
    protected void handleEvent(GitHubInstallationRepositoriesEventDTO event) {
        var installation = event.installation();

        if (installation == null) {
            log.warn("Received installation_repositories event with missing data: action={}", event.action());
            return;
        }

        List<GitHubRepositoryRefDTO> added = event.repositoriesAdded() != null ? event.repositoriesAdded() : List.of();
        List<GitHubRepositoryRefDTO> removed =
                event.repositoriesRemoved() != null ? event.repositoriesRemoved() : List.of();

        log.debug(
                "Received installation_repositories event: action={}, installationId={}, addedCount={}, removedCount={}",
                event.action(),
                installation.id(),
                added.size(),
                removed.size());

        long installationId = installation.id();

        // Notify consuming module via SPI for added repositories
        if (!added.isEmpty()) {
            List<RepositorySnapshot> addedSnapshots = added.stream()
                    .map(ref -> new RepositorySnapshot(
                            Objects.requireNonNull(ref.id()), ref.fullName(), ref.name(), ref.isPrivate()))
                    .toList();
            provisioningListener.onRepositoriesAdded(installationId, addedSnapshots);
            log.info(
                    "Added repositories to installation: installationId={}, repoCount={}",
                    installationId,
                    addedSnapshots.size());
        }

        // Notify consuming module via SPI for removed repositories
        if (!removed.isEmpty()) {
            List<ProvisioningListener.RepositoryRemoval> removedNames = removed.stream()
                    .map(ref -> new ProvisioningListener.RepositoryRemoval(ref.id(), ref.fullName()))
                    .toList();
            provisioningListener.onRepositoriesRemoved(installationId, removedNames);
            log.info(
                    "Removed repositories from installation: installationId={}, repoCount={}",
                    installationId,
                    removedNames.size());
        }
    }
}
