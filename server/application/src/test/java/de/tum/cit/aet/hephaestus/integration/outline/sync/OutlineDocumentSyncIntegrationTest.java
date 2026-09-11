package de.tum.cit.aet.hephaestus.integration.outline.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineClientModels;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineContentClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineWebhookClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineWebhookTestFixtures;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineDocumentModel;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineNavigationNode;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineUser;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.MirrorState;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.SyncStatus;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;

/**
 * Real-Postgres proof of the Outline document reconcile with the HTTP wire mocked at the client boundary
 * (the SSRF-guarded client cannot be pointed at a loopback mock server, so the seam under the network is
 * stubbed while the whole sync/mapping/persistence path runs for real).
 *
 * <p>Pinned properties: an initial sync mirrors every registered collection's documents and advances the
 * per-collection watermark to COMPLETE; a second sync with one changed {@code updatedAt} re-exports only
 * the changed document; a document removed upstream is tombstoned with its author fields cleared; a
 * collection that vanishes from {@code collections.list} records an error and keeps its documents.
 */
@TestPropertySource(properties = "hephaestus.integration.outline.enabled=true")
class OutlineDocumentSyncIntegrationTest extends BaseIntegrationTest {

    private static final String SERVER_URL = "https://outline.example.test";
    private static final String COLLECTION_ID = "col-1";
    private static final String DOC_ONE = "doc-1";
    private static final String DOC_TWO = "doc-2";
    private static final Instant T0 = Instant.parse("2025-12-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-02-01T00:00:00Z");
    private static final OutlineUser AUTHOR = OutlineClientModels.user("user-1", "Ada Lovelace");

    @Autowired
    private OutlineContentClient outlineApiClient;

    @Autowired
    private OutlineWebhookClient outlineWebhookClient;

    @Autowired
    private OutlineDocumentSyncScheduler scheduler;

    @Autowired
    private OutlineDocumentRepository documentRepository;

    @Autowired
    private OutlineCollectionRepository collectionRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private CredentialBundleConverter credentialConverter;

    private long workspaceId;
    private long connectionId;

    @AfterEach
    void resetOutlineClient() {
        reset(outlineApiClient, outlineWebhookClient);
    }

    @BeforeEach
    void setUp() {
        reset(outlineApiClient, outlineWebhookClient);
        OutlineWebhookTestFixtures.acceptsSubscriptions(outlineWebhookClient);
        databaseTestUtils.cleanDatabase();
        Workspace workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("outline-sync"));
        workspaceId = workspace.getId();

        Connection connection = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                "team-1",
                new ConnectionConfig.OutlineConfig(SERVER_URL, null, null, Set.of()));
        connection.setCredentials(new BearerToken("outline-token", null), credentialConverter);
        connection.setState(IntegrationState.ACTIVE);
        connectionId = connectionRepository.save(connection).getId();

        OutlineCollection registered = new OutlineCollection();
        registered.setWorkspaceId(workspaceId);
        registered.setConnectionId(connectionId);
        registered.setCollectionId(COLLECTION_ID);
        registered.setState(MirrorState.ENABLED);
        registered.setSyncStatus(SyncStatus.PENDING);
        collectionRepository.save(registered);

        // The registered collection is visible to the token unless a test says otherwise.
        lenient()
                .when(outlineApiClient.listCollections(anyString(), anyString()))
                .thenReturn(List.of(
                        OutlineClientModels.collection(COLLECTION_ID, "Design", "col1", null, null, "Design docs")));
        lenient()
                .when(outlineApiClient.exportDocument(anyString(), anyString(), eq(DOC_ONE)))
                .thenReturn("# Alpha");
        lenient()
                .when(outlineApiClient.exportDocument(anyString(), anyString(), eq(DOC_TWO)))
                .thenReturn("# Beta");
    }

    private void stubCollection(List<String> docIds, Instant docOneUpdatedAt, Instant docTwoUpdatedAt) {
        List<OutlineNavigationNode> tree = docIds.stream()
                .map(id -> OutlineClientModels.node(id, id.toUpperCase(java.util.Locale.ROOT), "/doc/" + id, List.of()))
                .toList();
        when(outlineApiClient.listCollectionDocuments(anyString(), anyString(), eq(COLLECTION_ID)))
                .thenReturn(tree);

        List<OutlineDocumentModel> metas = docIds.stream()
                .map(id -> OutlineClientModels.document(
                        id,
                        "/doc/" + id,
                        id,
                        T0,
                        id.equals(DOC_ONE) ? docOneUpdatedAt : docTwoUpdatedAt,
                        id,
                        null,
                        COLLECTION_ID,
                        AUTHOR,
                        AUTHOR,
                        List.of("user-1", "user-2"),
                        null))
                .toList();
        when(outlineApiClient.listDocuments(anyString(), anyString(), eq(COLLECTION_ID)))
                .thenReturn(metas);
    }

    @Test
    void initialSync_mirrorsEveryRegisteredDocument_andCompletesTheCollection() {
        stubCollection(List.of(DOC_ONE, DOC_TWO), T1, T2);

        scheduler.syncAllNow();

        List<OutlineDocument> rows = mirroredRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getBodyMarkdown()).isNotBlank();
            assertThat(r.getContentHash()).isNotBlank();
            assertThat(r.isDeleted()).isFalse();
            assertThat(r.getCollectionId()).isEqualTo(COLLECTION_ID);
            assertThat(r.getCreatedBySubject()).isEqualTo("user-1");
            assertThat(r.getCreatedByName()).isEqualTo("Ada Lovelace");
            // Research-fitness columns land through the whole wire→JPA→Postgres path (jsonb included).
            assertThat(r.getOutlineCreatedAt()).isEqualTo(T0);
            assertThat(r.getCollaboratorSubjects()).containsExactly("user-1", "user-2");
            assertThat(r.getBodyEvictedAt()).isNull();
        });
        verify(outlineApiClient, times(1)).exportDocument(anyString(), anyString(), eq(DOC_ONE));
        verify(outlineApiClient, times(1)).exportDocument(anyString(), anyString(), eq(DOC_TWO));

        OutlineCollection collection = onlyCollection();
        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.COMPLETE);
        assertThat(collection.getDocumentsSyncedAt()).isNotNull();
        assertThat(collection.getLastSyncError()).isNull();
        assertThat(collection.getName()).isEqualTo("Design");
        assertThat(collection.getUrlId()).isEqualTo("col1");
        assertThat(collection.getDescription()).isEqualTo("Design docs");
        assertThat(collection.getDocumentsUpstream()).isEqualTo(2);
        assertThat(collection.getExportsSkippedForBudget()).isZero();
    }

    @Test
    void secondSync_reExportsOnlyTheChangedDocument() {
        stubCollection(List.of(DOC_ONE, DOC_TWO), T1, T1);
        scheduler.syncAllNow();
        clearInvocations(outlineApiClient);

        // doc-2's updatedAt moves; doc-1 stays put.
        stubCollection(List.of(DOC_ONE, DOC_TWO), T1, T2);
        when(outlineApiClient.exportDocument(anyString(), anyString(), eq(DOC_TWO)))
                .thenReturn("# Beta v2");

        scheduler.syncAllNow();

        verify(outlineApiClient, never()).exportDocument(anyString(), anyString(), eq(DOC_ONE));
        verify(outlineApiClient, times(1)).exportDocument(anyString(), anyString(), eq(DOC_TWO));

        OutlineDocument docTwo = documentById(DOC_TWO);
        assertThat(docTwo.getBodyMarkdown()).isEqualTo("# Beta v2");
    }

    @Test
    void documentRemovedUpstream_isTombstonedWithAuthorsCleared() {
        stubCollection(List.of(DOC_ONE, DOC_TWO), T1, T1);
        scheduler.syncAllNow();

        // doc-2 vanishes upstream: only doc-1 remains in the tree + metadata.
        stubCollection(List.of(DOC_ONE), T1, T1);
        scheduler.syncAllNow();

        OutlineDocument docTwo = documentById(DOC_TWO);
        assertThat(docTwo.isDeleted()).isTrue();
        assertThat(docTwo.getDeletedAt()).isNotNull();
        assertThat(docTwo.getBodyMarkdown()).isNull();
        assertThat(docTwo.getContentHash()).isNull();
        assertThat(docTwo.getCreatedBySubject()).isNull();
        assertThat(docTwo.getCreatedByName()).isNull();
        assertThat(docTwo.getUpdatedBySubject()).isNull();
        // The tombstone CHECK (ck_outline_document_tombstone) demands the collaborator list goes too.
        assertThat(docTwo.getCollaboratorSubjects()).isNull();

        OutlineDocument docOne = documentById(DOC_ONE);
        assertThat(docOne.isDeleted()).isFalse();
        assertThat(docOne.getBodyMarkdown()).isNotBlank();
    }

    @Test
    void archivedDocument_isNotTombstonedByAbsence_realPostgresRoundTrip() {
        stubCollection(List.of(DOC_ONE, DOC_TWO), T1, T1);
        scheduler.syncAllNow();
        clearInvocations(outlineApiClient);

        // doc-2 is archived upstream (soft, recoverable): it vanishes from documents.list/collections.documents
        // (Outline's default listing excludes archived docs) but is enumerated separately via
        // listArchivedDocuments — the second per-collection call the reconcile makes.
        stubCollection(List.of(DOC_ONE), T1, T1);
        Instant archivedAt = Instant.parse("2026-03-01T00:00:00Z");
        when(outlineApiClient.listArchivedDocuments(anyString(), anyString(), eq(COLLECTION_ID)))
                .thenReturn(List.of(OutlineClientModels.document(
                        DOC_TWO,
                        "/doc/" + DOC_TWO,
                        DOC_TWO.toUpperCase(java.util.Locale.ROOT),
                        T0,
                        T1,
                        DOC_TWO,
                        null,
                        COLLECTION_ID,
                        AUTHOR,
                        AUTHOR,
                        List.of("user-1"),
                        archivedAt)));

        scheduler.syncAllNow();

        OutlineDocument docTwo = documentById(DOC_TWO);
        // Archived is not tombstoned: the row survives, with its real content intact — the tombstone CHECK
        // (ck_outline_document_tombstone) is unaffected since deleted_at stays null.
        assertThat(docTwo.isDeleted()).isFalse();
        assertThat(docTwo.getArchivedAt()).isEqualTo(archivedAt);
        assertThat(docTwo.getBodyMarkdown()).isNotBlank();
        // A body was already present from the first sync — the archived-enumeration pass must not re-export it.
        verify(outlineApiClient, never()).exportDocument(anyString(), anyString(), eq(DOC_TWO));

        OutlineCollection collection = onlyCollection();
        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.COMPLETE);
        assertThat(collection.getLastSyncError()).isNull();
    }

    @Test
    void eviction_keepsTheHashAndStampsBodyEvictedAt_reExportClearsIt() {
        stubCollection(List.of(DOC_ONE), T1, T1);
        scheduler.syncAllNow();
        OutlineDocument mirrored = documentById(DOC_ONE);
        String hashBeforeEviction = mirrored.getContentHash();

        // The cap eviction is a native bulk UPDATE — exercise the real SQL against real Postgres.
        int evicted = documentRepository.evictBodies(workspaceId, List.of(mirrored.getId()));

        assertThat(evicted).isEqualTo(1);
        OutlineDocument afterEviction = documentById(DOC_ONE);
        assertThat(afterEviction.getBodyMarkdown()).isNull();
        assertThat(afterEviction.getContentHash()).isEqualTo(hashBeforeEviction); // kept — no re-export thrash
        assertThat(afterEviction.getBodyEvictedAt()).isNotNull();

        // Same updatedAt upstream → the retained hash keeps the unchanged fast path: no export happens.
        clearInvocations(outlineApiClient);
        scheduler.syncAllNow();
        verify(outlineApiClient, never()).exportDocument(anyString(), anyString(), eq(DOC_ONE));
        assertThat(documentById(DOC_ONE).getBodyEvictedAt()).isNotNull();

        // Upstream change → re-export brings the body back and clears the eviction marker.
        stubCollection(List.of(DOC_ONE), T2, T2);
        when(outlineApiClient.exportDocument(anyString(), anyString(), eq(DOC_ONE)))
                .thenReturn("# Alpha v2");
        scheduler.syncAllNow();

        OutlineDocument reExported = documentById(DOC_ONE);
        assertThat(reExported.getBodyMarkdown()).isEqualTo("# Alpha v2");
        assertThat(reExported.getBodyEvictedAt()).isNull();
    }

    @Test
    void collectionNoLongerVisible_recordsErrorAndKeepsDocuments() {
        stubCollection(List.of(DOC_ONE, DOC_TWO), T1, T1);
        scheduler.syncAllNow();

        // The token loses the collection: collections.list no longer returns it.
        when(outlineApiClient.listCollections(anyString(), anyString())).thenReturn(List.of());
        scheduler.syncAllNow();

        OutlineCollection collection = onlyCollection();
        assertThat(collection.getLastSyncError()).contains("no longer visible");
        List<OutlineDocument> rows = mirroredRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.isDeleted()).isFalse());
    }

    @Test
    void versionColumn_engagesOptimisticLockingAgainstRealPostgres_andIncrementsOnEveryWrite() {
        // The lost-update the @Version column guards against is a webhook refreshDocument and a mid-flight
        // reconcile upsert (each its own REQUIRES_NEW transaction, unserialized per workspace) both doing a
        // full-column save of the same row. The service's retry-once recovery from that conflict is pinned
        // deterministically by OutlineDocumentSyncServiceTest (mock save throws once then succeeds). Only
        // real Postgres can prove the column is actually wired: the NOT NULL DEFAULT 0 lands, the version
        // increments on every write, and a stale-snapshot save genuinely raises
        // ObjectOptimisticLockingFailureException rather than silently clobbering.
        stubCollection(List.of(DOC_ONE), T1, T1);
        scheduler.syncAllNow();

        OutlineDocument afterFirstSync = documentById(DOC_ONE);
        assertThat(afterFirstSync.getVersion()).isZero(); // NOT NULL DEFAULT 0 on the fresh insert

        // A real re-export (upstream updatedAt moves) is a full-column save — the version must advance.
        stubCollection(List.of(DOC_ONE), T2, T2);
        when(outlineApiClient.exportDocument(anyString(), anyString(), eq(DOC_ONE)))
                .thenReturn("# Alpha v2");
        scheduler.syncAllNow();
        assertThat(documentById(DOC_ONE).getVersion()).isGreaterThan(0L);

        // Two independent detached snapshots at the same version model the two concurrent writers. The
        // first save wins and bumps the version; the second, now stale, must be rejected by the optimistic
        // lock — the exact StaleObjectState the service's saveDocument catches and retries.
        OutlineDocument staleSnapshot = documentById(DOC_ONE);
        OutlineDocument winner = documentById(DOC_ONE);
        assertThat(staleSnapshot.getVersion()).isEqualTo(winner.getVersion());

        winner.setBodyMarkdown("# concurrent winner");
        documentRepository.saveAndFlush(winner);

        staleSnapshot.setTitle("stale writer's title");
        assertThatThrownBy(() -> documentRepository.saveAndFlush(staleSnapshot))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // The winner's write survived; the stale write was rejected, not silently merged.
        assertThat(documentById(DOC_ONE).getBodyMarkdown()).isEqualTo("# concurrent winner");
    }

    private OutlineCollection onlyCollection() {
        List<OutlineCollection> collections = collectionRepository.findByWorkspaceIdOrderByCreatedAtAsc(workspaceId);
        assertThat(collections).hasSize(1);
        return collections.get(0);
    }

    private OutlineDocument documentById(String documentId) {
        return mirroredRows().stream()
                .filter(d -> d.getDocumentId().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No mirrored row for " + documentId));
    }

    /** This install's mirrored rows, bodies included — the full-entity read the assertions need. */
    private List<OutlineDocument> mirroredRows() {
        return documentRepository.findAll().stream()
                .filter(d -> d.getWorkspaceId() == workspaceId && d.getConnectionId() == connectionId)
                .toList();
    }
}
