package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Erases the workspace's delivery keys when its content is purged. Installation keys belong to the instance. */
@Component
@RequiredArgsConstructor
public class ConsumerInboxPurge implements WorkspacePurgeContributor {
    private final ConsumerInboxStepRepository repository;

    @Override
    @Transactional
    public void deleteWorkspaceData(Long workspaceId) {
        repository.deleteAllByWorkspaceId(workspaceId);
    }
}
