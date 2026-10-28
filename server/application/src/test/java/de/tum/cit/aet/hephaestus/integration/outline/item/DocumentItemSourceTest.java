package de.tum.cit.aet.hephaestus.integration.outline.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentContentSource;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentEvents;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentProjection.ProjectedDocument;
import de.tum.cit.aet.hephaestus.integration.outline.documentation.OutlineDocumentPermission;
import de.tum.cit.aet.hephaestus.integration.outline.documentation.OutlineDocumentProjector;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class DocumentItemSourceTest extends BaseUnitTest {
    private final OutlineDocumentRepository documents = mock(OutlineDocumentRepository.class);
    private final OutlineDocumentProjector projection = mock(OutlineDocumentProjector.class);
    private final ConnectionService connections = mock(ConnectionService.class);
    private final OutlineDocumentPermission permission = mock(OutlineDocumentPermission.class);
    private final ItemRef item = new ItemRef(7L, 41L, DocumentEvents.DOCUMENT, 19L, null, null);
    private final OutlineDocument document = new OutlineDocument();
    private DocumentItemSource source;

    @BeforeEach
    void setUp() {
        source = new DocumentItemSource(
                documents,
                projection,
                new DocumentContentSource(JsonMapper.builder().build(), Clock.systemUTC()),
                JsonMapper.builder().build(),
                connections,
                permission);
    }

    private void readyDocument() {
        var workspace = new Workspace();
        workspace.setId(7L);
        var connection = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                "team-a",
                new ConnectionConfig.OutlineConfig("https://wiki.example.com", null, null, Set.of()));
        ReflectionTestUtils.setField(connection, "id", 21L);
        connection.setState(IntegrationState.ACTIVE);
        when(connections.findInWorkspace(7L, 21L)).thenReturn(Optional.of(connection));
        document.setId(19L);
        document.setWorkspaceId(7L);
        document.setConnectionId(21L);
        document.setContentHash("body-hash");
        document.setTitle("Document");
        document.setBodyMarkdown("Full body");
        when(documents.findByWorkspaceIdAndIdForProjection(7L, 19L)).thenReturn(Optional.of(document));
        when(permission.identities(7L, 19L)).thenReturn(List.of());
        when(permission.permits(7L, 19L)).thenReturn(true);
    }

    @Test
    void shouldUseTheOwningConnectionAndWholeBodyWhenTheDocumentIsVisible() {
        readyDocument();
        when(projection.documentById(7L, 19L))
                .thenReturn(Optional.of(
                        ProjectedDocument.withoutAuthors("collection", "document", "Document", "Full body", false)));
        var pinned = source.pin(item, null);
        assertThat(pinned.integration().instanceKey()).isEqualTo("team-a");
        var capture = source.capture(pinned, request());
        assertThat(new String(
                        Objects.requireNonNull(capture.files().get(DocumentContentSource.BODY_KEY)),
                        StandardCharsets.UTF_8))
                .isEqualTo("Full body");
        assertThat(capture.immutableIdentities())
                .containsEntry(DocumentContentSource.SOURCE, pinned.version().value());
        assertThat(capture.citableTexts())
                .extracting(CitableText::path)
                .contains("context/document.md", "context/document.json", "context/document.json#/title");
    }

    @Test
    void shouldRejectAnOldVersionWhenTheBodyChanges() {
        readyDocument();
        var pinned = source.pin(item, null);
        document.setContentHash("changed-body-hash");
        assertThatThrownBy(() -> source.capture(pinned, request())).isInstanceOf(ItemCaptureException.class);
    }

    @Test
    void shouldWithholdEvidenceWhenAnyCreatorEditorOrCollaboratorIsDenied() {
        readyDocument();
        var pinned = source.pin(item, null);
        when(documents.findByWorkspaceIdAndIdForProjection(7L, 19L)).thenReturn(Optional.empty());
        when(documents.existsByWorkspaceIdAndId(7L, 19L)).thenReturn(true);
        var capture = source.capture(pinned, request());
        assertThat(capture.files()).isEmpty();
        assertThat(capture.citableTexts()).isEmpty();
        assertThat(capture.completeness()).isEmpty();
        assertThat(capture.stateOverrides())
                .containsEntry(
                        DocumentContentSource.SOURCE,
                        new SourceCaptureState.Redacted(SourceAbsenceReason.ACCESS_NOT_PERMITTED));
    }

    @Test
    void shouldPreserveEvictedContentAsUnavailableRatherThanAnEmptyBody() {
        readyDocument();
        var pinned = source.pin(item, null);
        when(projection.documentById(7L, 19L))
                .thenReturn(Optional.of(
                        ProjectedDocument.withoutAuthors("collection", "document", "Document", null, false)));
        var capture = source.capture(pinned, request());
        assertThat(capture.files()).isEmpty();
        assertThat(capture.citableTexts()).isEmpty();
        assertThat(capture.stateOverrides())
                .containsEntry(
                        DocumentContentSource.SOURCE,
                        new SourceCaptureState.Unavailable(SourceAbsenceReason.CONTENT_EVICTED));
    }

    @Test
    void shouldReturnNoSnapshotForAnItemOwnedByAnotherWorkspace() {
        assertThat(source.current(new ItemRef(8L, 41L, DocumentEvents.DOCUMENT, 19L, null, null)))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(SourceUsePurpose.class)
    void shouldCheckDocumentPermissionWithoutLoadingOrCopyingItsBody(SourceUsePurpose purpose) {
        when(permission.permits(7L, 19L)).thenReturn(true);
        assertThat(source.kinds()).containsExactly(DocumentEvents.DOCUMENT);
        assertThat(source.permits(item, purpose)).isTrue();
        verify(documents, never()).findByWorkspaceIdAndIdForProjection(anyLong(), anyLong());
        verify(projection, never()).documentById(anyLong(), anyLong());
    }

    @Test
    void shouldRefuseDocumentPermissionAfterConsentOrOriginAccessIsRemoved() {
        when(permission.permits(7L, 19L)).thenReturn(false);
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
        when(permission.permits(7L, 19L)).thenReturn(true);
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isTrue();
        when(permission.permits(7L, 19L)).thenReturn(false);
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
        assertThat(source.permits(
                        new ItemRef(8L, 41L, DocumentEvents.DOCUMENT, 19L, null, null),
                        SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
    }

    @Test
    void shouldOfferOnlyPrivateDocumentDestinationsAndNoScanMembers() {
        var descriptor = new DocumentItemDescriptor();
        assertThat(descriptor.copyCapabilities())
                .containsExactlyInAnyOrder(CopyCapability.PRACTICE_PAGE, CopyCapability.CONVERSATION);
        assertThat(descriptor.memberKinds()).isEmpty();
        assertThat(descriptor.reviewCapabilities().scanItemKinds()).isEmpty();
    }

    @Test
    void shouldReturnTheGovernedDocumentRecordEvenAfterTheMirrorChanges() {
        readyDocument();
        when(projection.documentById(7L, 19L))
                .thenReturn(Optional.of(
                        ProjectedDocument.withoutAuthors("collection", "document", "Document", "Full body", false)));
        var pinned = source.pin(item, null);
        var captured = source.capture(pinned, request());
        document.setTitle("New title after capture");
        clearInvocations(documents, projection, connections, permission);
        var record = source.record(pinned, captured.files());
        assertThat(record.path("title").asString()).isEqualTo("Document");
        assertThat(record.has("createdAt")).isTrue();
        assertThat(record.path("createdAt").isNull()).isTrue();
        assertThat(record.has("updatedAt")).isTrue();
        assertThat(record.path("updatedAt").isNull()).isTrue();
        assertThat(record)
                .isEqualTo(JsonMapper.builder()
                        .build()
                        .readTree(Objects.requireNonNull(captured.files().get(DocumentContentSource.METADATA_KEY))));
        verifyNoInteractions(documents, projection, connections, permission);
        assertThatThrownBy(() -> source.record(pinned, Map.of()))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.SOURCE_NOT_READY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "null", "not json", "{} {}"})
    void shouldRefuseMissingOrMalformedCapturedDocumentRecords(String bytes) {
        readyDocument();
        var pinned = source.pin(item, null);
        assertThatThrownBy(() -> source.record(
                        pinned, Map.of(DocumentContentSource.METADATA_KEY, bytes.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.SOURCE_NOT_READY));
    }

    @Test
    void shouldPreserveActualCapturedDocumentDatesInTheKitRecord() {
        readyDocument();
        Instant created = Instant.parse("2026-01-01T09:00:00Z");
        Instant updated = Instant.parse("2026-10-01T10:00:00Z");
        when(projection.documentById(7L, 19L))
                .thenReturn(Optional.of(new ProjectedDocument(
                        "collection",
                        "document",
                        "Document",
                        "Full body",
                        false,
                        created,
                        updated,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        false,
                        null,
                        null,
                        null,
                        null)));
        var pinned = source.pin(item, null);
        var capture = source.capture(pinned, request());
        var record = source.record(pinned, capture.files());
        assertThat(record.path("createdAt").asString()).isEqualTo(created.toString());
        assertThat(record.path("updatedAt").asString()).isEqualTo(updated.toString());
        assertThat(capture.citableTexts())
                .extracting(CitableText::path)
                .contains("context/document.json#/createdAt", "context/document.json#/updatedAt");
    }

    private static CaptureRequest request() {
        return new CaptureRequest(
                UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(DocumentContentSource.SOURCE), "3.0.0", 3);
    }
}
