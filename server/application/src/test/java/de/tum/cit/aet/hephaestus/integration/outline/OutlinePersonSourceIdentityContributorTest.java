package de.tum.cit.aet.hephaestus.integration.outline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.outline.documentation.OutlineDocumentPermission;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;

class OutlinePersonSourceIdentityContributorTest extends BaseUnitTest {
    @Mock
    private ObjectProvider<OutlineDocumentPermission> permission;

    @Mock
    private IdentityProviderLookup providers;

    @Test
    void sourceInventoryDoesNotInitializeItsOwnSuppressionDependency() {
        var documents = mock(OutlineDocumentRepository.class);
        var identity = new PersonIdentity(31L, "creator", "team-a");
        var first = mock(OutlineDocumentRepository.PersonSourceDocument.class);
        var second = mock(OutlineDocumentRepository.PersonSourceDocument.class);
        when(first.getId()).thenReturn(19L);
        when(second.getId()).thenReturn(12L);
        when(documents.findPersonSourceIds(31L, "creator", "team-a")).thenReturn(List.of(first, second, first));
        var contributor = new OutlinePersonSourceIdentityContributor(documents, providers, permission);
        var scope = new PersonScope(null, List.of(identity), List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(contributor.itemKinds()).containsExactly("docs.document");
        assertThat(contributor.sourceIds(scope)).containsExactly(12L, 19L);
        verifyNoInteractions(permission);
    }

    @Test
    void exactAttributionRequiresTheRealPermissionReaderAfterInitialization() {
        var documents = mock(OutlineDocumentRepository.class);
        var reader = mock(OutlineDocumentPermission.class);
        var identity = new PersonIdentity(31L, "creator", "team-a");
        when(permission.getObject()).thenReturn(reader);
        when(reader.identities(7L, 19L)).thenReturn(List.of(identity));
        var contributor = new OutlinePersonSourceIdentityContributor(documents, providers, permission);

        assertThat(contributor.identitiesForSource(7L, "docs.document", 19L)).containsExactly(identity);
        verify(permission).getObject();
        verifyNoInteractions(documents);
    }
}
