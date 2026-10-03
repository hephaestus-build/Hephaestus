package de.tum.cit.aet.hephaestus.integration.outline.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.documentation.DocumentReviewTrigger;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.outline.OutlineProperties;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiException;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineClientModels;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineRateLimitedException;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineDocumentModel;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.MirrorState;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.SyncStatus;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentSnapshot;
import de.tum.cit.aet.hephaestus.integration.outline.domain.signal.OutlineDocumentSignalRecorder;
import de.tum.cit.aet.hephaestus.integration.outline.lifecycle.OutlineWebhookRegistrar;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit coverage for the sync paths a real-Postgres test cannot cheaply pin. The core invariant is
 * <em>no-wipe</em>: a pass that dies mid-way (429, revoked token) must leave already-mirrored documents
 * intact, because the alternative is silent, irreversible data loss.
 *
 * <p>{@link OutlineMirrorWriter}, {@link OutlineMirrorTransactions} and {@link OutlineMirrorRetentionService}
 * are the real production objects over mocked repositories, so the load-or-create + mutate + save +
 * retry-in-a-fresh-transaction shape is under test rather than stubbed — and so the tombstone, sweep and
 * eviction assertions below still run through the erasure code that ships, not a stub of it. The
 * {@code @Transactional} boundaries themselves need a container and are pinned by
 * {@code OutlineDocumentSyncIntegrationTest}.
 */
class OutlineDocumentSyncServiceTest extends BaseUnitTest {

    private static final long WORKSPACE = 42L;
    private static final long CONNECTION = 7L;
    private static final String SERVER_URL = "https://outline.example.test";
    private static final String COLLECTION_ID = "col-1";
    private static final String COLLECTION_ID_B = "col-2";
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-02-01T00:00:00Z");

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

    private OutlineDocumentSyncService service(int exportBudget) {
        return service(exportBudget, 200);
    }

    private OutlineDocumentSyncService service(int exportBudget, int cacheMaxSizeMb) {
        OutlineProperties properties = new OutlineProperties(
                new OutlineProperties.Sync(exportBudget),
                new OutlineProperties.Cache(cacheMaxSizeMb),
                Duration.ofDays(30));
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
    void setUp() {
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
        lenient()
                .when(outlineApiClient.listCollections(SERVER_URL, "token"))
                .thenReturn(List.of(OutlineClientModels.collection(COLLECTION_ID, "Design", "col1", null, null, null)));
        lenient()
                .when(outlineApiClient.listCollectionDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(List.of());
        lenient()
                .when(outlineApiClient.listArchivedDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(List.of());
    }

    private static OutlineDocumentModel meta(String id, Instant updatedAt) {
        return OutlineClientModels.document(
                id, null, id, T1, updatedAt, id, null, COLLECTION_ID, null, null, null, null);
    }

    private static OutlineDocumentModel metaWithUrl(String id, Instant updatedAt, String url) {
        return OutlineClientModels.document(
                id, url, id, T1, updatedAt, id, null, COLLECTION_ID, null, null, null, null);
    }

    private static OutlineDocumentModel archivedMeta(String id, Instant updatedAt, Instant archivedAt) {
        return OutlineClientModels.document(
                id, "/doc/" + id, id, T1, updatedAt, id, null, COLLECTION_ID, null, null, null, archivedAt);
    }

    private OutlineDocument mirrored(String documentId) {
        OutlineDocument doc = new OutlineDocument();
        doc.setWorkspaceId(WORKSPACE);
        doc.setConnectionId(CONNECTION);
        doc.setDocumentId(documentId);
        doc.setCollectionId(COLLECTION_ID);
        return doc;
    }

    /**
     * Puts {@code docs} in the mirror: the reconcile reads them as body-free snapshots, and a write
     * re-reads the row it is about to mutate — so both lookups must resolve to the same instances the
     * test then asserts on.
     */
    private void inMirror(OutlineDocument... docs) {
        List<OutlineDocumentSnapshot> snapshots =
                Arrays.stream(docs).map(OutlineDocumentSnapshot::of).toList();
        lenient()
                .when(documentRepository.findSnapshotsByWorkspaceIdAndConnectionId(WORKSPACE, CONNECTION))
                .thenReturn(snapshots);
        for (OutlineDocument doc : docs) {
            lenient()
                    .when(documentRepository.findSnapshotByDocumentId(WORKSPACE, CONNECTION, doc.getDocumentId()))
                    .thenReturn(Optional.of(OutlineDocumentSnapshot.of(doc)));
            lenient()
                    .when(documentRepository.findByWorkspaceIdAndConnectionIdAndDocumentId(
                            WORKSPACE, CONNECTION, doc.getDocumentId()))
                    .thenReturn(Optional.of(doc));
        }
    }

    @Test
    void budgetExhaustion_keepsCollectionPendingWithoutWatermarkOrTombstones() {
        // Two docs need an export but only one budget unit exists; a stale mirrored row would be
        // tombstone bait if the pass wrongly counted as clean.
        OutlineDocument staleRow = mirrored("doc-stale");
        inMirror(staleRow);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(List.of(meta("doc-1", T2), meta("doc-2", T1)));
        when(outlineApiClient.exportDocument(eq(SERVER_URL), eq("token"), anyString()))
                .thenReturn("# body");

        service(1).syncWorkspace(WORKSPACE);

        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(collection.getDocumentsSyncedAt()).isNull();
        assertThat(staleRow.isDeleted()).isFalse();
        // Coverage counters are written even on a budget-exhausted pass — the enumeration completed
        // (upstream = doc-1 + doc-2; the local-only stale row is not upstream).
        assertThat(collection.getDocumentsUpstream()).isEqualTo(2);
        assertThat(collection.getExportsSkippedForBudget()).isEqualTo(1);
        // Newest-first ordering means the single budget unit went to doc-1.
        verify(outlineApiClient).exportDocument(SERVER_URL, "token", "doc-1");
        verify(outlineApiClient, never()).exportDocument(SERVER_URL, "token", "doc-2");
    }

    @Test
    void cleanPass_marksCompleteAndTombstonesVanishedRows() {
        OutlineDocument staleRow = mirrored("doc-stale");
        staleRow.setBodyMarkdown("# old");
        staleRow.setCreatedBySubject("user-1");
        inMirror(staleRow);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID)).thenReturn(List.of(meta("doc-1", T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# body");

        service(10).syncWorkspace(WORKSPACE);

        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.COMPLETE);
        assertThat(collection.getDocumentsSyncedAt()).isNotNull();
        assertThat(collection.getDocumentsUpstream()).isEqualTo(1);
        assertThat(collection.getExportsSkippedForBudget()).isZero();
        assertThat(staleRow.isDeleted()).isTrue();
        assertThat(staleRow.getBodyMarkdown()).isNull();
        assertThat(staleRow.getCreatedBySubject()).isNull();
    }

    // Upstream deletion is inferred only on RECONCILIATION, in every integration (ADR 0024).

    @Test
    void initialPass_tombstonesNothing_evenWhenACleanEnumerationOmitsAMirroredDocument() {
        // Identical fixture to cleanPass_marksCompleteAndTombstonesVanishedRows: the enumeration is clean
        // and doc-stale is absent upstream. An INITIAL job must still not infer a deletion — the mirror is
        // being populated, so "absent from the mirror's own half-built state" is not evidence of anything.
        // This is the rule every SCM sweep already obeys.
        OutlineDocument staleRow = mirrored("doc-stale");
        staleRow.setBodyMarkdown("# old");
        staleRow.setCreatedBySubject("user-1");
        inMirror(staleRow);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID)).thenReturn(List.of(meta("doc-1", T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# body");

        service(10).syncWorkspace(WORKSPACE, null, SyncJobType.INITIAL);

        // Everything else about the pass is unchanged: it still upserts and still completes cleanly.
        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.COMPLETE);
        assertThat(collection.getDocumentsSyncedAt()).isNotNull();
        assertThat(collection.getDocumentsUpstream()).isEqualTo(1);
        assertThat(collection.getExportsSkippedForBudget()).isZero();
        // Only the tombstone is withheld — body, authorship and the marker itself survive untouched.
        assertThat(staleRow.isDeleted()).isFalse();
        assertThat(staleRow.getBodyMarkdown()).isEqualTo("# old");
        assertThat(staleRow.getCreatedBySubject()).isEqualTo("user-1");
    }

    @Test
    void reconciliationPass_withTheSameFixture_doesTombstoneTheVanishedDocument() {
        // Same fixture as the INITIAL test but RECONCILIATION: the tombstone lands, proving the gate
        // discriminates on job type alone.
        OutlineDocument staleRow = mirrored("doc-stale");
        staleRow.setBodyMarkdown("# old");
        staleRow.setCreatedBySubject("user-1");
        inMirror(staleRow);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID)).thenReturn(List.of(meta("doc-1", T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# body");

        service(10).syncWorkspace(WORKSPACE, null, SyncJobType.RECONCILIATION);

        assertThat(staleRow.isDeleted()).isTrue();
        assertThat(staleRow.getBodyMarkdown()).isNull();
        assertThat(staleRow.getCreatedBySubject()).isNull();
    }

    @Test
    void reconciliationPass_withAnIncompleteEnumeration_stillTombstonesNothing() {
        // The two guards are independent and both must hold. Here the job type permits a sweep, but the
        // enumeration ran out of export budget, so it is not provably clean — fail closed, delete nothing.
        OutlineDocument staleRow = mirrored("doc-stale");
        staleRow.setBodyMarkdown("# old");
        inMirror(staleRow);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(List.of(meta("doc-1", T2), meta("doc-2", T1)));
        when(outlineApiClient.exportDocument(eq(SERVER_URL), eq("token"), anyString()))
                .thenReturn("# body");

        service(1).syncWorkspace(WORKSPACE, null, SyncJobType.RECONCILIATION);

        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(staleRow.isDeleted()).isFalse();
        assertThat(staleRow.getBodyMarkdown()).isEqualTo("# old");
    }

    @Test
    void cleanPass_unchangedDocumentWithMatchingMetadata_touchesNoDocumentRowAtAll() {
        // Steady state: a stable wiki must not re-read or re-write a document whose body is current AND whose
        // denormalized metadata already matches. The reconcile decides this off the body-free snapshot alone —
        // no full-entity load (findByWorkspaceIdAndConnectionIdAndDocumentId) and no saveAndFlush.
        OutlineDocument settled = mirrored("doc-1");
        settled.setContentHash("hash-1");
        settled.setOutlineUpdatedAt(T1);
        settled.setBodyMarkdown("# body");
        settled.setTitle("doc-1"); // meta("doc-1", ...) carries title == id
        settled.setSlug("doc-1"); // no url on meta → slug falls back to urlId == id
        settled.setCollectionSlug("col1"); // catalog refresh sets the collection urlId to "col1"
        inMirror(settled);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID)).thenReturn(List.of(meta("doc-1", T1)));

        service(10).syncWorkspace(WORKSPACE);

        // Not exported, not loaded as an entity, not written — the whole per-document write transaction is skipped.
        verify(outlineApiClient, never()).exportDocument(anyString(), anyString(), anyString());
        verify(documentRepository, never())
                .findByWorkspaceIdAndConnectionIdAndDocumentId(anyLong(), anyLong(), eq("doc-1"));
        verify(documentRepository, never()).saveAndFlush(any());
        // The pass still completes cleanly and does not tombstone the settled row.
        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.COMPLETE);
        assertThat(settled.isDeleted()).isFalse();
    }

    @Test
    void cleanPass_unchangedBodyButRenamedTitle_rewritesMetadataWithoutReExporting() {
        // Body current (hash + updatedAt match) but the title drifted upstream: the row must be rewritten to
        // pick up the new metadata, yet never re-exported — the export budget is for changed bodies only.
        OutlineDocument renamed = mirrored("doc-1");
        renamed.setContentHash("hash-1");
        renamed.setOutlineUpdatedAt(T1);
        renamed.setBodyMarkdown("# body");
        renamed.setTitle("stale title");
        renamed.setSlug("doc-1");
        renamed.setCollectionSlug("col1");
        inMirror(renamed);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID)).thenReturn(List.of(meta("doc-1", T1)));

        service(10).syncWorkspace(WORKSPACE);

        verify(outlineApiClient, never()).exportDocument(anyString(), anyString(), anyString());
        assertThat(renamed.getTitle()).isEqualTo("doc-1");
        assertThat(renamed.getBodyMarkdown()).isEqualTo("# body");
        verify(documentRepository).saveAndFlush(argThat(d -> "doc-1".equals(d.getDocumentId())));
    }

    // A pass that dies mid-way must never take the mirror with it.

    @Test
    void rateLimitMidPass_keepsEveryMirroredDocument_noTombstoneNoBodyLoss() {
        // The 429 arrives on doc-1's export, AFTER the enumeration returned a list that no longer
        // contains doc-2. If the pass were treated as clean, doc-2 would be tombstoned — its body, hash
        // and authorship irreversibly dropped — because a document Outline throttled us out of listing is
        // indistinguishable from one that was deleted. It must not be.
        OutlineDocument docOne = mirrored("doc-1");
        docOne.setBodyMarkdown("# one");
        docOne.setContentHash("hash-1");
        docOne.setOutlineUpdatedAt(T1);
        OutlineDocument docTwo = mirrored("doc-2");
        docTwo.setBodyMarkdown("# two");
        docTwo.setContentHash("hash-2");
        docTwo.setOutlineUpdatedAt(T1);
        docTwo.setCreatedBySubject("user-2");
        inMirror(docOne, docTwo);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(
                        List.of(meta("doc-1", T2)) // doc-1 changed upstream; doc-2 is simply absent from this listing
                        );
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1"))
                .thenThrow(new OutlineRateLimitedException(Duration.ofSeconds(30), null));

        service(10).syncWorkspace(WORKSPACE);

        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(collection.getDocumentsSyncedAt()).isNull();
        assertThat(docTwo.isDeleted()).isFalse();
        assertThat(docTwo.getBodyMarkdown()).isEqualTo("# two");
        assertThat(docTwo.getContentHash()).isEqualTo("hash-2");
        assertThat(docTwo.getCreatedBySubject()).isEqualTo("user-2");
        assertThat(docOne.isDeleted()).isFalse();
        assertThat(docOne.getBodyMarkdown()).isEqualTo("# one");
        verify(documentRepository, never()).saveAndFlush(argThat(OutlineDocument::isDeleted));
    }

    @Test
    void tokenRevokedMidSync_keepsEveryMirroredDocument_andRecordsTheError() {
        // A 401 surfaces as a permanent OutlineApiException. The collection records it and is skipped;
        // "the token can no longer see these documents" is emphatically not "these documents are gone".
        OutlineDocument docOne = mirrored("doc-1");
        docOne.setBodyMarkdown("# one");
        docOne.setContentHash("hash-1");
        docOne.setCreatedBySubject("user-1");
        inMirror(docOne);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenThrow(new OutlineApiException("Outline /api/documents.list failed (HTTP 401)"));

        service(10).syncWorkspace(WORKSPACE);

        assertThat(collection.getLastSyncError()).contains("401");
        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(collection.getDocumentsSyncedAt()).isNull();
        assertThat(docOne.isDeleted()).isFalse();
        assertThat(docOne.getBodyMarkdown()).isEqualTo("# one");
        assertThat(docOne.getContentHash()).isEqualTo("hash-1");
        assertThat(docOne.getCreatedBySubject()).isEqualTo("user-1");
        verify(documentRepository, never()).saveAndFlush(argThat(OutlineDocument::isDeleted));
    }

    @Test
    void rateLimit_abortsThePassWithoutMarkingComplete() {
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenThrow(new OutlineRateLimitedException(Duration.ofSeconds(30), null));

        service(10).syncWorkspace(WORKSPACE);

        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(collection.getDocumentsSyncedAt()).isNull();
    }

    @Test
    void syncOneCollection_apiFailureMessageOverColumnWidth_truncatesLastSyncErrorAtTheBoundary() {
        // outline_collection.last_sync_error is 2048 chars wide; an oversized exception message must be
        // clamped, not rejected or silently widened.
        String overLongMessage = "x".repeat(2100);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenThrow(new OutlineApiException(overLongMessage));

        service(10).syncWorkspace(WORKSPACE);

        assertThat(collection.getLastSyncError()).hasSize(2048);
        assertThat(collection.getLastSyncError()).isEqualTo(overLongMessage.substring(0, 2048));
    }

    @Test
    void refreshDocument_deleteEvent_tombstonesWithoutAnyApiCall() {
        OutlineDocument row = mirrored("doc-1");
        row.setBodyMarkdown("# body");
        inMirror(row);

        service(10).refreshDocument(WORKSPACE, "documents.delete", "doc-1");

        assertThat(row.isDeleted()).isTrue();
        assertThat(row.getBodyMarkdown()).isNull();
        verify(outlineApiClient, never()).getDocumentInfo(anyString(), anyString(), anyString());
        verify(outlineApiClient, never()).exportDocument(anyString(), anyString(), anyString());
    }

    // Archive is not delete: soft, recoverable, content kept.

    @Test
    void refreshDocument_archiveEvent_keepsContentAndStampsArchivedAt_noApiCall() {
        OutlineDocument row = mirrored("doc-1");
        row.setBodyMarkdown("# body");
        row.setContentHash("hash");
        row.setCreatedBySubject("user-1");
        inMirror(row);

        service(10).refreshDocument(WORKSPACE, "documents.archive", "doc-1");

        assertThat(row.isDeleted()).isFalse();
        assertThat(row.getArchivedAt()).isNotNull();
        // Unlike a tombstone, archiving keeps the body, hash, and authors.
        assertThat(row.getBodyMarkdown()).isEqualTo("# body");
        assertThat(row.getContentHash()).isEqualTo("hash");
        assertThat(row.getCreatedBySubject()).isEqualTo("user-1");
        verify(outlineApiClient, never()).getDocumentInfo(anyString(), anyString(), anyString());
        verify(outlineApiClient, never()).exportDocument(anyString(), anyString(), anyString());
    }

    @Test
    void refreshDocument_archiveEvent_noMirroredRow_isNoOp() {
        when(documentRepository.findSnapshotByDocumentId(WORKSPACE, CONNECTION, "doc-1"))
                .thenReturn(Optional.empty());

        service(10).refreshDocument(WORKSPACE, "documents.archive", "doc-1");

        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void refreshDocument_unarchiveEvent_refreshesLiveAndClearsArchivedAt() {
        OutlineDocument row = mirrored("doc-1");
        row.setArchivedAt(Instant.parse("2026-01-15T00:00:00Z"));
        row.setBodyMarkdown("# stale");
        row.setContentHash("stale-hash");
        row.setOutlineUpdatedAt(T1);
        inMirror(row);
        when(outlineApiClient.getDocumentInfo(SERVER_URL, "token", "doc-1")).thenReturn(Optional.of(meta("doc-1", T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# fresh");

        service(10).refreshDocument(WORKSPACE, "documents.unarchive", "doc-1");

        assertThat(row.getArchivedAt()).isNull();
        assertThat(row.getBodyMarkdown()).isEqualTo("# fresh");
    }

    @Test
    void refreshDocument_withUsablePrefetchedMeta_skipsDocumentsInfo() {
        when(documentRepository.findSnapshotByDocumentId(WORKSPACE, CONNECTION, "doc-1"))
                .thenReturn(Optional.empty());
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# fresh");

        service(10).refreshDocument(WORKSPACE, "documents.update", "doc-1", meta("doc-1", T2));

        verify(outlineApiClient, never()).getDocumentInfo(anyString(), anyString(), anyString());
        verify(outlineApiClient).exportDocument(SERVER_URL, "token", "doc-1");
        verify(documentRepository)
                .saveAndFlush(argThat(d -> "doc-1".equals(d.getDocumentId()) && "# fresh".equals(d.getBodyMarkdown())));
    }

    @Test
    void refreshDocument_prefetchedMetaMissingCollectionId_fallsBackToDocumentsInfo() {
        when(documentRepository.findSnapshotByDocumentId(WORKSPACE, CONNECTION, "doc-1"))
                .thenReturn(Optional.empty());
        OutlineDocumentModel incomplete = OutlineClientModels.document(
                "doc-1", null, "Doc 1", T1, T2, "doc-1", null, null, // no collectionId — unusable
                null, null, null, null);
        when(outlineApiClient.getDocumentInfo(SERVER_URL, "token", "doc-1")).thenReturn(Optional.of(meta("doc-1", T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# fresh");

        service(10).refreshDocument(WORKSPACE, "documents.update", "doc-1", incomplete);

        verify(outlineApiClient).getDocumentInfo(SERVER_URL, "token", "doc-1");
    }

    @Test
    void refreshDocument_nullPrefetchedMeta_fallsBackToDocumentsInfo() {
        when(documentRepository.findSnapshotByDocumentId(WORKSPACE, CONNECTION, "doc-1"))
                .thenReturn(Optional.empty());
        when(outlineApiClient.getDocumentInfo(SERVER_URL, "token", "doc-1")).thenReturn(Optional.of(meta("doc-1", T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# fresh");

        service(10).refreshDocument(WORKSPACE, "documents.update", "doc-1", null);

        verify(outlineApiClient).getDocumentInfo(SERVER_URL, "token", "doc-1");
    }

    @Test
    void syncWorkspace_archivedDocument_isSeenNotTombstoned_andNotReExportedWhenBodyPresent() {
        OutlineDocument existingArchived = mirrored("doc-archived");
        existingArchived.setBodyMarkdown("# already have it");
        existingArchived.setContentHash("hash");
        inMirror(existingArchived);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID)).thenReturn(List.of());
        when(outlineApiClient.listArchivedDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(List.of(archivedMeta("doc-archived", T1, T2)));

        service(10).syncWorkspace(WORKSPACE);

        assertThat(existingArchived.isDeleted()).isFalse();
        assertThat(existingArchived.getArchivedAt()).isEqualTo(T2);
        assertThat(existingArchived.getBodyMarkdown()).isEqualTo("# already have it");
        // A body is already present — never re-export just because it is archived.
        verify(outlineApiClient, never()).exportDocument(anyString(), anyString(), eq("doc-archived"));
        // The clean pass still completes: the archived doc counted into the seen set.
        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.COMPLETE);
        assertThat(collection.getDocumentsUpstream()).isEqualTo(1);
    }

    @Test
    void syncWorkspace_archivedDocumentWithNoBody_exportsOnceToBackfillIt() {
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID)).thenReturn(List.of());
        when(outlineApiClient.listArchivedDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(List.of(archivedMeta("doc-archived", T1, T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-archived"))
                .thenReturn("# backfilled");

        service(10).syncWorkspace(WORKSPACE);

        verify(outlineApiClient).exportDocument(SERVER_URL, "token", "doc-archived");
        verify(documentRepository)
                .saveAndFlush(argThat(
                        d -> "doc-archived".equals(d.getDocumentId()) && "# backfilled".equals(d.getBodyMarkdown())));
    }

    @Test
    void syncWorkspace_archivedOnlyPass_stillCompletesCleanly() {
        // Only the archived doc is seen (no live docs); the pass is still clean — archived documents are
        // counted into the seen set, so completion is not blocked by their separate enumeration.
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID)).thenReturn(List.of());
        when(outlineApiClient.listArchivedDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(List.of(archivedMeta("doc-archived", T1, T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-archived"))
                .thenReturn("# backfilled");

        service(10).syncWorkspace(WORKSPACE);

        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.COMPLETE);
        assertThat(collection.getDocumentsSyncedAt()).isNotNull();
        assertThat(collection.getDocumentsUpstream()).isEqualTo(1);
    }

    @Test
    void refreshDocument_updateOutsideMirroredCollections_isIgnored() {
        when(documentRepository.findSnapshotByDocumentId(WORKSPACE, CONNECTION, "doc-x"))
                .thenReturn(Optional.empty());
        when(outlineApiClient.getDocumentInfo(SERVER_URL, "token", "doc-x"))
                .thenReturn(Optional.of(OutlineClientModels.document(
                        "doc-x", null, "X", T1, T1, "doc-x", null, "other-col", null, null, null, null)));
        when(collectionRepository.findByWorkspaceIdAndConnectionIdAndCollectionId(WORKSPACE, CONNECTION, "other-col"))
                .thenReturn(Optional.empty());

        service(10).refreshDocument(WORKSPACE, "documents.update", "doc-x");

        verify(outlineApiClient, never()).exportDocument(anyString(), anyString(), anyString());
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void refreshDocument_updateInMirroredCollection_exportsAndUpserts() {
        when(documentRepository.findSnapshotByDocumentId(WORKSPACE, CONNECTION, "doc-1"))
                .thenReturn(Optional.empty());
        when(outlineApiClient.getDocumentInfo(SERVER_URL, "token", "doc-1")).thenReturn(Optional.of(meta("doc-1", T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# fresh");

        service(10).refreshDocument(WORKSPACE, "documents.update", "doc-1");

        verify(documentRepository)
                .saveAndFlush(argThat(d -> "doc-1".equals(d.getDocumentId()) && "# fresh".equals(d.getBodyMarkdown())));
    }

    @Test
    void refreshDocument_storesTheFullUrlSlug_matchingTheFullReconcilePath() {
        // The webhook targeted-refresh path must store the same slug format as full reconcile — the
        // full "<title-slug>-<urlId>" trailing URL segment, not the bare urlId — or a document linked
        // from a PR/issue right after creation won't resolve until the next full sync.
        when(documentRepository.findSnapshotByDocumentId(WORKSPACE, CONNECTION, "doc-1"))
                .thenReturn(Optional.empty());
        when(outlineApiClient.getDocumentInfo(SERVER_URL, "token", "doc-1"))
                .thenReturn(Optional.of(metaWithUrl("doc-1", T2, "/doc/setup-guide-psUl8qCles")));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# fresh");

        service(10).refreshDocument(WORKSPACE, "documents.update", "doc-1");

        verify(documentRepository).saveAndFlush(argThat(d -> "setup-guide-psUl8qCles".equals(d.getSlug())));
    }

    @Test
    void refreshDocument_fallsBackToUrlId_whenMetaHasNoUrl() {
        // Outline's documents.info always carries `url` in practice, but the DTO field is nullable — a
        // tolerant reader must still produce a usable (if short) slug rather than null.
        when(documentRepository.findSnapshotByDocumentId(WORKSPACE, CONNECTION, "doc-1"))
                .thenReturn(Optional.empty());
        when(outlineApiClient.getDocumentInfo(SERVER_URL, "token", "doc-1"))
                .thenReturn(
                        Optional.of(meta("doc-1", T2)) // url is null; urlId is "doc-1"
                        );
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# fresh");

        service(10).refreshDocument(WORKSPACE, "documents.update", "doc-1");

        verify(documentRepository).saveAndFlush(argThat(d -> "doc-1".equals(d.getSlug())));
    }

    @Test
    void refreshDocument_vanishedUpstream_tombstonesTheMirroredRow() {
        OutlineDocument row = mirrored("doc-1");
        row.setBodyMarkdown("# body");
        inMirror(row);
        when(outlineApiClient.getDocumentInfo(SERVER_URL, "token", "doc-1")).thenReturn(Optional.empty());

        service(10).refreshDocument(WORKSPACE, "documents.update", "doc-1");

        assertThat(row.isDeleted()).isTrue();
        assertThat(row.getBodyMarkdown()).isNull();
    }

    @Test
    void refreshCollectionCatalog_deleteEvent_tombstonesTheCollectionsDocuments() {
        OutlineDocument row = mirrored("doc-1");
        row.setBodyMarkdown("# body");
        inMirror(row);
        when(documentRepository.findSnapshotsByCollectionId(WORKSPACE, CONNECTION, COLLECTION_ID))
                .thenReturn(List.of(OutlineDocumentSnapshot.of(row)));

        service(10).refreshCollectionCatalog(WORKSPACE, "collections.delete", COLLECTION_ID);

        assertThat(row.isDeleted()).isTrue();
        assertThat(collection.getLastSyncError()).contains("deleted in Outline");
    }

    @Test
    void export_capturesTimestampsAndCollaborators_andClearsTheEvictionMarker() {
        OutlineDocument evicted = mirrored("doc-1");
        evicted.setContentHash("stale-hash"); // an evicted row keeps its hash…
        evicted.setOutlineUpdatedAt(T1);
        evicted.setBodyEvictedAt(T1); // …and carries the eviction marker
        inMirror(evicted);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(List.of(OutlineClientModels.document(
                        "doc-1",
                        null,
                        "Doc 1",
                        T1,
                        T2, // upstream changed → re-export
                        "doc-1",
                        null,
                        COLLECTION_ID,
                        OutlineClientModels.user("user-1", "Ada"),
                        OutlineClientModels.user("user-2", "Grace"),
                        List.of("user-1", "user-2", "user-3"),
                        null)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# fresh");

        service(10).syncWorkspace(WORKSPACE);

        assertThat(evicted.getBodyMarkdown()).isEqualTo("# fresh");
        assertThat(evicted.getBodyEvictedAt()).isNull(); // the body is back — the marker must go
        assertThat(evicted.getOutlineCreatedAt()).isEqualTo(T1);
        assertThat(evicted.getOutlineUpdatedAt()).isEqualTo(T2);
        assertThat(evicted.getCollaboratorSubjects()).containsExactly("user-1", "user-2", "user-3");
    }

    @Test
    void evictedButUnmodifiedDocument_isNotReExported_theRetainedHashHoldsTheCap() {
        OutlineDocument evicted = mirrored("doc-1");
        evicted.setContentHash("kept-hash");
        evicted.setOutlineUpdatedAt(T1);
        evicted.setBodyEvictedAt(T1);
        inMirror(evicted);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(
                        List.of(meta("doc-1", T1)) // upstream unchanged
                        );

        service(10).syncWorkspace(WORKSPACE);

        // No thrash loop: the unchanged fast path holds because eviction kept the hash.
        verify(outlineApiClient, never()).exportDocument(anyString(), anyString(), anyString());
        assertThat(evicted.getBodyEvictedAt()).isEqualTo(T1);
    }

    @Test
    void tombstone_clearsCollaboratorSubjectsAlongsideAuthors() {
        OutlineDocument row = mirrored("doc-1");
        row.setBodyMarkdown("# body");
        row.setContentHash("hash");
        row.setCollaboratorSubjects(List.of("user-1", "user-3"));
        inMirror(row);

        service(10).refreshDocument(WORKSPACE, "documents.delete", "doc-1");

        assertThat(row.isDeleted()).isTrue();
        assertThat(row.getBodyMarkdown()).isNull();
        assertThat(row.getContentHash()).isNull();
        assertThat(row.getCollaboratorSubjects()).isNull();
    }

    @Test
    void syncPendingCollections_enforcesTheSizeCapAfterThePass() {
        when(collectionRepository.findByWorkspaceIdAndStateAndSyncStatus(
                        WORKSPACE, MirrorState.ENABLED, SyncStatus.PENDING))
                .thenReturn(List.of(collection));
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID)).thenReturn(List.of(meta("doc-1", T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# body");
        // Cap of 0 MB with a non-empty mirror → the eviction path must run.
        when(documentRepository.sumBodySizeByWorkspaceId(WORKSPACE)).thenReturn(1_000L);
        when(documentRepository.findEvictionCandidates(WORKSPACE, 1000))
                .thenReturn(List.of(new Object[][] {{11L, 1_000L}}));

        service(10, 0).syncPendingCollections(WORKSPACE);

        verify(documentRepository).evictBodies(WORKSPACE, List.of(11L));
    }

    @Test
    void sizeCap_chunksTheEviction_neverBuildingOneUnboundedInList() {
        // 2500 over-cap bodies. The candidate query is paged (never "give me every row that has a body")
        // and evictBodies is called per page — one IN-list per 1000 ids, not a single 2500-parameter
        // statement that Postgres would refuse well before its 65 535-parameter ceiling.
        when(collectionRepository.findByWorkspaceIdAndStateAndSyncStatus(
                        WORKSPACE, MirrorState.ENABLED, SyncStatus.PENDING))
                .thenReturn(List.of(collection));
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID)).thenReturn(List.of());
        when(documentRepository.sumBodySizeByWorkspaceId(WORKSPACE)).thenReturn(2_500L);

        List<Object[]> remaining = new ArrayList<>();
        for (long id = 1; id <= 2_500; id++) {
            remaining.add(new Object[] {id, 1L});
        }
        // Evicted rows drop out of the candidate query — model that by handing back successive pages.
        when(documentRepository.findEvictionCandidates(eq(WORKSPACE), eq(1000)))
                .thenAnswer(inv -> List.copyOf(remaining.subList(0, Math.min(1000, remaining.size()))));
        when(documentRepository.evictBodies(eq(WORKSPACE), any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(1);
            remaining.subList(0, ids.size()).clear();
            return ids.size();
        });

        service(10, 0).syncPendingCollections(WORKSPACE);

        verify(documentRepository, times(3)).evictBodies(eq(WORKSPACE), argThat(ids -> ids.size() <= 1000));
        assertThat(remaining).isEmpty();
    }

    @Test
    void syncPendingCollections_makesNoApiCallsWhenNothingIsPending() {
        when(collectionRepository.findByWorkspaceIdAndStateAndSyncStatus(
                        WORKSPACE, MirrorState.ENABLED, SyncStatus.PENDING))
                .thenReturn(List.of());

        service(10).syncPendingCollections(WORKSPACE);

        verify(outlineApiClient, never()).listCollections(anyString(), anyString());
        verify(outlineApiClient, never()).listDocuments(anyString(), anyString(), anyString());
    }

    @Test
    void syncWorkspace_multiCollectionPartialFailure_collectionBStillSyncsWhileACarriesTheError() {
        OutlineCollection collectionB = new OutlineCollection();
        collectionB.setWorkspaceId(WORKSPACE);
        collectionB.setConnectionId(CONNECTION);
        collectionB.setCollectionId(COLLECTION_ID_B);
        collectionB.setState(MirrorState.ENABLED);
        collectionB.setSyncStatus(SyncStatus.PENDING);

        when(collectionRepository.findForSync(WORKSPACE, CONNECTION, MirrorState.ENABLED))
                .thenReturn(List.of(collection, collectionB));
        when(collectionRepository.findByWorkspaceIdOrderByCreatedAtAsc(WORKSPACE))
                .thenReturn(List.of(collection, collectionB));
        when(collectionRepository.findByWorkspaceIdAndConnectionIdAndCollectionId(
                        WORKSPACE, CONNECTION, COLLECTION_ID_B))
                .thenReturn(Optional.of(collectionB));
        when(outlineApiClient.listCollections(SERVER_URL, "token"))
                .thenReturn(List.of(
                        OutlineClientModels.collection(COLLECTION_ID, "Design", "col1", null, null, null),
                        OutlineClientModels.collection(COLLECTION_ID_B, "Eng", "col2", null, null, null)));
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenThrow(new OutlineApiException("collection A is unreachable"));
        when(outlineApiClient.listCollectionDocuments(SERVER_URL, "token", COLLECTION_ID_B))
                .thenReturn(List.of());
        when(outlineApiClient.listArchivedDocuments(SERVER_URL, "token", COLLECTION_ID_B))
                .thenReturn(List.of());
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID_B))
                .thenReturn(List.of(meta("doc-b", T1)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-b")).thenReturn("# b body");

        service(10).syncWorkspace(WORKSPACE);

        // The service's own documented resilience claim: one collection's API failure never blocks the rest.
        assertThat(collection.getLastSyncError()).isEqualTo("collection A is unreachable");
        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.PENDING); // never reached a clean pass
        assertThat(collectionB.getSyncStatus()).isEqualTo(SyncStatus.COMPLETE);
        assertThat(collectionB.getLastSyncError()).isNull();
    }

    @Test
    void syncPendingCollections_rateLimit_abortsThePassWithoutMarkingComplete() {
        when(collectionRepository.findByWorkspaceIdAndStateAndSyncStatus(
                        WORKSPACE, MirrorState.ENABLED, SyncStatus.PENDING))
                .thenReturn(List.of(collection));
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenThrow(new OutlineRateLimitedException(Duration.ofSeconds(30), null));

        service(10).syncPendingCollections(WORKSPACE);

        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(collection.getDocumentsSyncedAt()).isNull();
        // An under-cap mirror needs no eviction, so the abort never reaches the candidate query.
        verify(documentRepository, never()).findEvictionCandidates(anyLong(), anyInt());
    }

    @Test
    void syncCollection_rateLimit_abortsWithoutMarkingComplete() {
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenThrow(new OutlineRateLimitedException(Duration.ofSeconds(30), null));

        service(10).syncCollection(WORKSPACE, COLLECTION_ID);

        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(collection.getDocumentsSyncedAt()).isNull();
    }

    @Test
    void refreshDocument_optimisticLockConflict_retriesInAFreshTransactionThenSucceeds() {
        // The retry must be a whole new unit of work, not a second save inside the failed one: an
        // optimistic-lock failure marks its transaction rollback-only, so a same-transaction retry could
        // never commit. Re-running re-reads the row at the winner's version and re-applies our mutation.
        OutlineDocument stale = mirrored("doc-1");
        stale.setId(99L);
        stale.setVersion(0L);
        OutlineDocument currentInDb = mirrored("doc-1");
        currentInDb.setId(99L);
        currentInDb.setVersion(5L);
        when(documentRepository.findSnapshotByDocumentId(WORKSPACE, CONNECTION, "doc-1"))
                .thenReturn(Optional.of(OutlineDocumentSnapshot.of(stale)));
        // First read hands back our snapshot's row; the retry's read sees what the winner committed.
        when(documentRepository.findByWorkspaceIdAndConnectionIdAndDocumentId(WORKSPACE, CONNECTION, "doc-1"))
                .thenReturn(Optional.of(stale))
                .thenReturn(Optional.of(currentInDb));
        when(outlineApiClient.getDocumentInfo(SERVER_URL, "token", "doc-1")).thenReturn(Optional.of(meta("doc-1", T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# fresh");
        when(documentRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(OutlineDocument.class, 99L))
                .thenAnswer(inv -> inv.getArgument(0));

        service(10).refreshDocument(WORKSPACE, "documents.update", "doc-1");

        verify(documentRepository, times(2)).saveAndFlush(any());
        // The mutation landed on the row as the winner left it — not on our stale copy.
        assertThat(currentInDb.getVersion()).isEqualTo(5L);
        assertThat(currentInDb.getBodyMarkdown()).isEqualTo("# fresh");
        // The export is NOT repeated: the replay is a pure re-application of the payload we already have.
        verify(outlineApiClient, times(1)).exportDocument(SERVER_URL, "token", "doc-1");
    }

    @Test
    void refreshDocument_optimisticLockConflict_bothAttemptsFail_logsAndSkipsWithoutThrowing() {
        OutlineDocument row = mirrored("doc-1");
        row.setId(99L);
        inMirror(row);
        when(outlineApiClient.getDocumentInfo(SERVER_URL, "token", "doc-1")).thenReturn(Optional.of(meta("doc-1", T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# fresh");
        when(documentRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(OutlineDocument.class, 99L));

        // The second conflict must be swallowed (logged + skipped) — never escape and abort the caller.
        assertThatCode(() -> service(10).refreshDocument(WORKSPACE, "documents.update", "doc-1"))
                .doesNotThrowAnyException();

        verify(documentRepository, times(2)).saveAndFlush(any());
    }

    @Test
    void refreshDocument_optimisticLockConflict_rowVanishedDuringRetry_skipsWithoutThrowing() {
        OutlineDocument row = mirrored("doc-1");
        row.setId(99L);
        when(documentRepository.findSnapshotByDocumentId(WORKSPACE, CONNECTION, "doc-1"))
                .thenReturn(Optional.of(OutlineDocumentSnapshot.of(row)));
        // The row was hard-deleted (e.g. its collection was removed from the mirror) between our conflict
        // and our retry: the retry's re-read finds nothing, so the upsert re-creates it rather than
        // resurrecting a stale version — and above all it does not throw.
        when(documentRepository.findByWorkspaceIdAndConnectionIdAndDocumentId(WORKSPACE, CONNECTION, "doc-1"))
                .thenReturn(Optional.of(row))
                .thenReturn(Optional.empty());
        when(outlineApiClient.getDocumentInfo(SERVER_URL, "token", "doc-1")).thenReturn(Optional.of(meta("doc-1", T2)));
        when(outlineApiClient.exportDocument(SERVER_URL, "token", "doc-1")).thenReturn("# fresh");
        when(documentRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(OutlineDocument.class, 99L))
                .thenAnswer(inv -> inv.getArgument(0));

        service(10).refreshDocument(WORKSPACE, "documents.update", "doc-1");

        // The retry re-created the vanished row: the second, successful save carries the freshly
        // exported body and is not a tombstone — proving it rebuilt from the export rather than
        // re-persisting the stale pre-conflict entity.
        ArgumentCaptor<OutlineDocument> saved = ArgumentCaptor.forClass(OutlineDocument.class);
        verify(documentRepository, times(2)).saveAndFlush(saved.capture());
        OutlineDocument recreated = saved.getAllValues().get(1);
        assertThat(recreated.getBodyMarkdown()).isEqualTo("# fresh");
        assertThat(recreated.isDeleted()).isFalse();
    }

    @Test
    void syncWorkspace_collectionOptimisticLockConflict_retriesOnceThenSucceeds() {
        collection.setId(55L);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID)).thenReturn(List.of());
        when(collectionRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(OutlineCollection.class, 55L))
                .thenAnswer(inv -> inv.getArgument(0));

        service(10).syncWorkspace(WORKSPACE);

        // The catalog write lost its race and was replayed; the pass still completes the collection.
        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.COMPLETE);
    }

    /**
     * Cancellation is checked per document, not just between collections, so a job cancelled while inside a
     * single large collection stops promptly rather than exporting up to the whole export budget of
     * documents after the admin pressed Cancel.
     */
    @Test
    void cancellationMidCollection_stopsExportingAtTheNextDocument() {
        SyncExecutionHandle handle = mock(SyncExecutionHandle.class);
        AtomicInteger polls = new AtomicInteger();
        // false for the collection-loop check and for doc-1, true from doc-2 onwards.
        when(handle.isCancellationRequested()).thenAnswer(inv -> polls.getAndIncrement() >= 2);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(List.of(meta("doc-1", T1), meta("doc-2", T1), meta("doc-3", T1), meta("doc-4", T1)));
        when(outlineApiClient.exportDocument(anyString(), anyString(), anyString()))
                .thenReturn("# body");

        service(500).syncWorkspace(WORKSPACE, handle, SyncJobType.RECONCILIATION);

        verify(outlineApiClient, times(1)).exportDocument(SERVER_URL, "token", "doc-1");
        verify(outlineApiClient, never()).exportDocument(SERVER_URL, "token", "doc-2");
        verify(outlineApiClient, never()).exportDocument(SERVER_URL, "token", "doc-3");
    }

    /**
     * An aborted enumeration is an incomplete one, so it must take the same exit a budget-exhausted pass
     * takes: PENDING (so the catch-up tick resumes it), never COMPLETE, and above all never the
     * tombstone-by-absence sweep — its {@code seen} set is short by every document it did not reach.
     */
    @Test
    void cancellationMidCollection_leavesThePassPending_andTombstonesNothing() {
        // Start from COMPLETE so PENDING has to be written by the abort rather than merely left in place.
        collection.setSyncStatus(SyncStatus.COMPLETE);
        OutlineDocument reached = mirrored("doc-1");
        reached.setBodyMarkdown("# one");
        reached.setContentHash("hash-1");
        reached.setOutlineUpdatedAt(T1);
        // Mirrored, upstream, but never enumerated because the cancel landed first. A pass that wrongly
        // counted as clean would read it as vanished and tombstone it.
        OutlineDocument unreached = mirrored("doc-4");
        unreached.setBodyMarkdown("# four");
        unreached.setContentHash("hash-4");
        unreached.setOutlineUpdatedAt(T1);
        inMirror(reached, unreached);

        SyncExecutionHandle handle = mock(SyncExecutionHandle.class);
        AtomicInteger polls = new AtomicInteger();
        when(handle.isCancellationRequested()).thenAnswer(inv -> polls.getAndIncrement() >= 2);
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(List.of(meta("doc-1", T2), meta("doc-4", T2)));
        when(outlineApiClient.exportDocument(anyString(), anyString(), anyString()))
                .thenReturn("# body");

        service(500).syncWorkspace(WORKSPACE, handle, SyncJobType.RECONCILIATION);

        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(collection.getDocumentsSyncedAt()).isNull();
        assertThat(unreached.isDeleted()).isFalse();
        assertThat(unreached.getBodyMarkdown()).isEqualTo("# four");
        verify(documentRepository, never()).saveAndFlush(argThat(OutlineDocument::isDeleted));
    }

    /** Without a handle (scheduled tick, webhook, targeted kick) nothing is interruptible and the pass completes. */
    @Test
    void withoutAHandle_everyDocumentIsStillExported() {
        when(outlineApiClient.listDocuments(SERVER_URL, "token", COLLECTION_ID))
                .thenReturn(List.of(meta("doc-1", T1), meta("doc-2", T1), meta("doc-3", T1)));
        when(outlineApiClient.exportDocument(anyString(), anyString(), anyString()))
                .thenReturn("# body");

        service(500).syncWorkspace(WORKSPACE);

        verify(outlineApiClient, times(3)).exportDocument(anyString(), anyString(), anyString());
        assertThat(collection.getSyncStatus()).isEqualTo(SyncStatus.COMPLETE);
    }
}
