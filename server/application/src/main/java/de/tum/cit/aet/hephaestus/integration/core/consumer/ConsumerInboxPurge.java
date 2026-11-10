package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Erases the workspace's provider events and their steps when its content is purged. Installation events belong to the instance. */
@Component
@RequiredArgsConstructor
public class ConsumerInboxPurge implements WorkspacePurgeContributor {
    private final ProviderEventStore store;

    @Override
    @Transactional
    public void deleteWorkspaceData(Long workspaceId) {
        store.deleteWorkspace(workspaceId);
    }
}
