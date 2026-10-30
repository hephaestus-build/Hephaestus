package de.tum.cit.aet.hephaestus.integration.outline.lifecycle;

import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutlineWorkspacePurgeAdapter implements WorkspacePurgeContributor {

    static final int PURGE_ORDER = -200;

    private final OutlineDocumentRepository outlineDocumentRepository;
    private final OutlineCollectionRepository outlineCollectionRepository;

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        outlineDocumentRepository.deleteByWorkspaceId(workspaceId);
        outlineCollectionRepository.deleteByWorkspaceId(workspaceId);
    }

    @Override
    public int getOrder() {
        return PURGE_ORDER;
    }
}
