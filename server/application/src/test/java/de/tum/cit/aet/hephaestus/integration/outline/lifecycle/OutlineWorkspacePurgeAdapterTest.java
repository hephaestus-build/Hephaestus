package de.tum.cit.aet.hephaestus.integration.outline.lifecycle;

import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class OutlineWorkspacePurgeAdapterTest extends BaseUnitTest {

    @Mock
    private OutlineDocumentRepository outlineDocumentRepository;

    @Mock
    private OutlineCollectionRepository outlineCollectionRepository;

    private OutlineWorkspacePurgeAdapter adapter() {
        return new OutlineWorkspacePurgeAdapter(outlineDocumentRepository, outlineCollectionRepository);
    }

    @Test
    void deleteWorkspaceData_erasesAllLocalOutlineData() {
        adapter().deleteWorkspaceData(789L);

        verify(outlineDocumentRepository).deleteByWorkspaceId(789L);
        verify(outlineCollectionRepository).deleteByWorkspaceId(789L);
    }
}
