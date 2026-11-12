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
    public int getOrder() {
        // Handlers lock an event before its domain rows. Purge must take the same order.
        return Integer.MIN_VALUE;
    }

    @Override
    @Transactional
    public void deleteWorkspaceData(Long workspaceId) {
        store.deleteWorkspace(workspaceId);
    }
}
