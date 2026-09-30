package de.tum.cit.aet.hephaestus.integration.outline.domain.signal;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentity;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * A document sits in its collection, and a reader is told which one by the name Outline shows — never by the
 * collection's url id, a random slug that means nothing to anybody.
 */
@Transactional
class DocumentIdentityResolverIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DocumentIdentityResolver resolver;

    @Autowired
    private OutlineDocumentRepository documents;

    @Autowired
    private OutlineCollectionRepository collections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Test
    void shouldNameADocumentsCollectionByItsNameAndNeverByItsUrlId() {
        long workspaceId = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("document-identity"))
                .getId();
        collection(workspaceId, "col-named", "Engineering", "hK3pQ9xZ2a");
        collection(workspaceId, "col-unnamed", null, "Zq81mNw3Lk");
        OutlineDocument named = document(workspaceId, "doc-named", "col-named", "hK3pQ9xZ2a", "Deployment runbook");
        OutlineDocument unnamed = document(workspaceId, "doc-unnamed", "col-unnamed", "Zq81mNw3Lk", " ");
        OutlineDocument orphaned = document(workspaceId, "doc-orphaned", "col-gone", "Pp09aaBbCc", "Old notes");

        Map<Long, ArtifactIdentity> resolved =
                resolver.resolve(workspaceId, List.of(named.getId(), unnamed.getId(), orphaned.getId()));

        ArtifactIdentity inNamedCollection = Objects.requireNonNull(resolved.get(named.getId()));
        assertThat(inNamedCollection.title()).isEqualTo("Deployment runbook");
        assertThat(inNamedCollection.container()).isEqualTo("Engineering");

        ArtifactIdentity inUnnamedCollection = Objects.requireNonNull(resolved.get(unnamed.getId()));
        assertThat(inUnnamedCollection.title()).isEqualTo("Document");
        assertThat(inUnnamedCollection.container())
                .as("a collection with no captured name")
                .isNull();

        assertThat(Objects.requireNonNull(resolved.get(orphaned.getId())).container())
                .as("a collection the mirror no longer holds")
                .isNull();
    }

    private void collection(long workspaceId, String collectionId, @Nullable String name, String urlId) {
        OutlineCollection collection = new OutlineCollection();
        collection.setWorkspaceId(workspaceId);
        collection.setConnectionId(1L);
        collection.setCollectionId(collectionId);
        collection.setName(name);
        collection.setUrlId(urlId);
        collections.save(collection);
    }

    private OutlineDocument document(
            long workspaceId, String documentId, String collectionId, String collectionSlug, String title) {
        OutlineDocument doc = new OutlineDocument();
        doc.setWorkspaceId(workspaceId);
        doc.setConnectionId(1L);
        doc.setDocumentId(documentId);
        doc.setCollectionId(collectionId);
        doc.setCollectionSlug(collectionSlug);
        doc.setSlug(documentId);
        doc.setTitle(title);
        return documents.save(doc);
    }
}
