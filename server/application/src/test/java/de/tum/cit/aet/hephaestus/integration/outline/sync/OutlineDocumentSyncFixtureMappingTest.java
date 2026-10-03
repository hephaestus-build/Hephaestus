package de.tum.cit.aet.hephaestus.integration.outline.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.agent.documentation.DocumentReviewTrigger;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.OutlineProperties;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineClientConfig;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineEnvelope;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineCollectionModel;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineDocumentModel;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineNavigationNode;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.MirrorState;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.SyncStatus;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.signal.OutlineDocumentSignalRecorder;
import de.tum.cit.aet.hephaestus.integration.outline.lifecycle.OutlineWebhookRegistrar;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feeds the same real {@code documents.list} + {@code collections.documents} fixtures used by {@code
 * OutlineApiFixtureDeserializationTest} through {@link OutlineDocumentSyncService#syncWorkspace} and
 * pins the full upsert mapping against real wire data: slug derived from the real {@code url}, real
 * timestamps, real author/collaborator ids and names, and a parent/child relationship carried by an
 * actual nested collection tree. This catches mapping bugs ({@code @JsonProperty} typos, wrong nesting
 * precedence, slug derivation errors) that {@link OutlineDocumentSyncServiceTest}'s hand-built {@code
 * Meta} records cannot, since those are shaped to already match the code rather than the real payload.
 */
class OutlineDocumentSyncFixtureMappingTest extends BaseUnitTest {

    private static final long WORKSPACE = 42L;
    private static final long CONNECTION = 7L;
    private static final String SERVER_URL = "https://outline.example.test";

    /** Real ids captured live — the "Engineering Docs" collection and the two fixture-capture documents. */
    private static final String COLLECTION_ID = "fbe68839-b131-44e2-bb93-0bc533d39193";

    private static final String PARENT_DOC_ID = "7d11d73d-1b36-43e3-9f31-b43e98c69b5b";
    private static final String CHILD_DOC_ID = "cec98e59-623c-4392-a343-6e96b0995e51";
    private static final String ADMIN_SUBJECT = "99bdd8e2-176a-42fa-ba0c-4f9c4ce6caa9";
    private static final String ADMIN_NAME = "Felix Admin";

    // The exact tolerant policy the running Outline client uses (unknown fields + unknown enum values),
    // so the fixture round-trip exercises production deserialization, not a lax bespoke mapper.
    private static final JsonMapper MAPPER =
            OutlineClientConfig.tolerantMapper(JsonMapper.builder().build());

    @Mock
    private ConnectionService connectionService;

    @Mock
    private OutlineApiClient outlineApiClient;

    @Mock
    private OutlineDocumentRepository documentRepository;

    @Mock
    private OutlineCollectionRepository collectionRepository;

    @Mock
    private OutlineWebhookRegistrar webhookRegistrar;

    @Mock
    private Connection connection;

    private OutlineCollection collection;

    private OutlineDocumentSyncService service() {
        OutlineProperties properties = new OutlineProperties(
                new OutlineProperties.Sync(100), new OutlineProperties.Cache(200), Duration.ofDays(30));
        OutlineMirrorWriter mirrorWriter =
                new OutlineMirrorWriter(new OutlineMirrorTransactions(documentRepository, collectionRepository));
        return new OutlineDocumentSyncService(
                connectionService,
                outlineApiClient,
                documentRepository,
                collectionRepository,
                webhookRegistrar,
                properties,
                mirrorWriter,
                new OutlineMirrorRetentionService(documentRepository, mirrorWriter, properties),
                mock(OutlineDocumentSignalRecorder.class),
                // No review trigger: this suite is about mirroring, and a node that cannot submit a review
                // still records every signal — which is exactly the ObjectProvider's absent case.
                new StaticListableBeanFactory().getBeanProvider(DocumentReviewTrigger.class));
    }

    @BeforeEach
    void setUp() throws IOException {
        lenient().when(connection.getId()).thenReturn(CONNECTION);
        lenient()
                .when(connection.getConfig())
                .thenReturn(new ConnectionConfig.OutlineConfig(SERVER_URL, "sub-1", "secret", Set.of()));
        lenient()
                .when(connectionService.findActive(WORKSPACE, IntegrationKind.OUTLINE))
                .thenReturn(Optional.of(connection));
        lenient()
                .when(connectionService.findActiveBearerToken(WORKSPACE, IntegrationKind.OUTLINE))
                .thenReturn(Optional.of(new BearerToken("token", null)));
        lenient()
                .when(documentRepository.findSnapshotsByWorkspaceIdAndConnectionId(WORKSPACE, CONNECTION))
                .thenReturn(List.of());
        lenient().when(documentRepository.sumBodySizeByWorkspaceId(WORKSPACE)).thenReturn(0L);
        lenient().when(documentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(collectionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        collection = new OutlineCollection();
        collection.setWorkspaceId(WORKSPACE);
        collection.setConnectionId(CONNECTION);
        collection.setCollectionId(COLLECTION_ID);
        collection.setState(MirrorState.ENABLED);
        collection.setSyncStatus(SyncStatus.PENDING);
        lenient()
                .when(collectionRepository.findForSync(WORKSPACE, CONNECTION, MirrorState.ENABLED))
                .thenReturn(List.of(collection));
        lenient()
                .when(collectionRepository.findByWorkspaceIdOrderByCreatedAtAsc(WORKSPACE))
                .thenReturn(List.of(collection));
        // The bookkeeping write re-reads the registry row inside its own transaction.
        lenient()
                .when(collectionRepository.findByWorkspaceIdAndConnectionIdAndCollectionId(
                        WORKSPACE, CONNECTION, COLLECTION_ID))
                .thenReturn(Optional.of(collection));

        OutlineEnvelope<List<OutlineCollectionModel>> collectionsList =
                readFixture("/outline-api/collections.list.json", new TypeReference<>() {});
        assertNotNull(collectionsList.data());
        OutlineCollectionModel engineeringDocs = collectionsList.data().stream()
                .filter(c -> COLLECTION_ID.equals(c.getId()))
                .findFirst()
                .orElseThrow();
        lenient().when(outlineApiClient.listCollections(SERVER_URL, "token")).thenReturn(List.of(engineeringDocs));

        OutlineEnvelope<List<OutlineDocumentModel>> documentsList =
                readFixture("/outline-api/documents.list.json", new TypeReference<>() {});
        lenient()
                .when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(documentsList.data());

        OutlineEnvelope<List<OutlineNavigationNode>> tree =
                readFixture("/outline-api/collections.documents.json", new TypeReference<>() {});
        lenient()
                .when(outlineApiClient.listCollectionDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(tree.data());

        // Export bodies: real Markdown text captured for the two fixture-capture documents; a
        // deterministic placeholder for the rest of the (unrelated, pre-existing) documents in the
        // shared live collection so the pass completes without asserting on them.
        lenient()
                .when(outlineApiClient.exportDocument(eq(SERVER_URL), eq("token"), anyString()))
                .thenAnswer(inv -> "# placeholder body for " + inv.getArgument(2));
        lenient()
                .when(outlineApiClient.exportDocument(SERVER_URL, "token", PARENT_DOC_ID))
                .thenReturn(realBodyText("/outline-api/documents.info.json"));
        lenient()
                .when(outlineApiClient.exportDocument(SERVER_URL, "token", CHILD_DOC_ID))
                .thenReturn(realBodyText("/outline-api/documents.info.child.json"));
    }

    @Test
    void syncWorkspace_mapsTheRealParentDocument_slugTimestampsAndAuthor() {
        service().syncWorkspace(WORKSPACE);

        OutlineDocument parent = savedDocument(PARENT_DOC_ID);
        assertThat(parent.getCollectionId()).isEqualTo(COLLECTION_ID);
        assertThat(parent.getTitle()).isEqualTo("Fixture Capture Doc Renamed");
        // Slug is the trailing segment of the real Outline url, not the bare urlId.
        assertThat(parent.getSlug()).isEqualTo("fixture-capture-doc-renamed-JpRHHJuY8M");
        assertThat(parent.getParentDocumentId()).isNull();
        assertThat(parent.getOutlineCreatedAt()).isEqualTo(Instant.parse("2026-07-11T05:23:17.240Z"));
        assertThat(parent.getOutlineUpdatedAt()).isEqualTo(Instant.parse("2026-07-11T05:23:18.441Z"));
        assertThat(parent.getCreatedBySubject()).isEqualTo(ADMIN_SUBJECT);
        assertThat(parent.getCreatedByName()).isEqualTo(ADMIN_NAME);
        assertThat(parent.getUpdatedBySubject()).isEqualTo(ADMIN_SUBJECT);
        assertThat(parent.getCollaboratorSubjects()).contains(ADMIN_SUBJECT);
        assertThat(parent.getBodyMarkdown())
                .isEqualTo("# Fixture Capture Doc Renamed\n\nUpdated body for fixture capture (rename).");
    }

    @Test
    void syncWorkspace_mapsTheRealNestedChildDocument_parentIdAndSlugFromTheRealTree() {
        service().syncWorkspace(WORKSPACE);

        OutlineDocument child = savedDocument(CHILD_DOC_ID);
        assertThat(child.getTitle()).isEqualTo("Fixture Capture Child 2");
        assertThat(child.getSlug()).isEqualTo("fixture-capture-child-2-OHQpaAib7z");
        // The parent id comes from the collections.documents nesting (the child sits under the parent's
        // node in the live tree), matching documents.list metadata's parentDocumentId.
        assertThat(child.getParentDocumentId()).isEqualTo(PARENT_DOC_ID);
        assertThat(child.getCollectionId()).isEqualTo(COLLECTION_ID);
        assertThat(child.getBodyMarkdown()).isEqualTo("# Fixture Capture Child 2\n\nSecond child doc body.");
    }

    @Test
    void syncWorkspace_refreshesTheCollectionCatalogFieldsFromTheRealCollectionsListFixture() {
        service().syncWorkspace(WORKSPACE);

        assertThat(collection.getName()).isEqualTo("Engineering Docs");
        assertThat(collection.getUrlId()).isEqualTo("j4Gxqv1NCn");
    }

    private OutlineDocument savedDocument(String documentId) {
        ArgumentCaptor<OutlineDocument> captor = ArgumentCaptor.forClass(OutlineDocument.class);
        verify(documentRepository, atLeastOnce()).saveAndFlush(captor.capture());
        return captor.getAllValues().stream()
                .filter(d -> documentId.equals(d.getDocumentId()))
                .reduce((first, second) -> second) // the last save wins (the export path saves once per doc)
                .orElseThrow(() -> new AssertionError("document " + documentId + " was never saved"));
    }

    private static <T> T readFixture(String classpath, TypeReference<T> type) throws IOException {
        try (InputStream in = OutlineDocumentSyncFixtureMappingTest.class.getResourceAsStream(classpath)) {
            assertThat(in).as("fixture %s must be on the classpath", classpath).isNotNull();
            return MAPPER.readValue(in.readAllBytes(), type);
        }
    }

    /** Pulls the real {@code data.text} Markdown out of a {@code documents.info} fixture (not part of the DTO). */
    private static String realBodyText(String classpath) throws IOException {
        try (InputStream in = OutlineDocumentSyncFixtureMappingTest.class.getResourceAsStream(classpath)) {
            assertThat(in).as("fixture %s must be on the classpath", classpath).isNotNull();
            JsonNode root = MAPPER.readTree(in.readAllBytes());
            return root.path("data").path("text").asString("");
        }
    }
}
