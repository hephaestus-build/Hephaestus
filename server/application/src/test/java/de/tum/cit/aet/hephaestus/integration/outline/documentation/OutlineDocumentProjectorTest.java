package de.tum.cit.aet.hephaestus.integration.outline.documentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection.ProjectedDocument;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.outline.identity.OutlineIdentityResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.springframework.data.domain.Pageable;

class OutlineDocumentProjectorTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 42L;
    private static final String SERVER_URL = "https://wiki.example.com";
    private static final String TEAM_ID = "9ff8ee7d-team";

    @Mock
    private OutlineDocumentRepository documentRepository;

    @Mock
    private OutlineCollectionRepository collectionRepository;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private OutlineIdentityResolver identityResolver;

    @Mock
    private OutlineDocumentSelector documentSelector;

    @Captor
    private ArgumentCaptor<Set<String>> refsCaptor;

    private OutlineDocumentProjector projector;

    @BeforeEach
    void setUp() {
        projector = new OutlineDocumentProjector(
                documentRepository,
                collectionRepository,
                connectionService,
                identityResolver,
                documentSelector,
                new OutlineOriginPolicy(Set.of(SERVER_URL)));
        lenient()
                .when(connectionService.findActiveOutlineConfig(WORKSPACE_ID))
                .thenReturn(Optional.of(new ConnectionConfig.OutlineConfig(SERVER_URL, null, null, Set.of())));
        lenient()
                .when(connectionService.findActive(WORKSPACE_ID, IntegrationKind.OUTLINE))
                .thenReturn(Optional.of(activeConnection()));
        lenient()
                .when(identityResolver.resolveMemberId(anyLong(), anyString(), any(), anyString()))
                .thenReturn(Optional.empty());
        lenient()
                .when(collectionRepository.findByWorkspaceIdOrderByCreatedAtAsc(WORKSPACE_ID))
                .thenReturn(List.of());
    }

    private static Connection activeConnection() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        return new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                TEAM_ID,
                new ConnectionConfig.OutlineConfig(SERVER_URL, null, null, Set.of()));
    }

    private static final Instant CREATED = Instant.parse("2025-11-01T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-02-01T00:00:00Z");

    private static OutlineDocument liveDocument() {
        OutlineDocument doc = new OutlineDocument();
        doc.setWorkspaceId(WORKSPACE_ID);
        doc.setConnectionId(7L);
        doc.setDocumentId("doc-uuid-1");
        doc.setCollectionId("col-uuid-1");
        doc.setCollectionSlug("architecture");
        doc.setSlug("design-doc");
        doc.setTitle("Design Doc");
        doc.setBodyMarkdown("# Design\n\nContent.");
        doc.setOutlineCreatedAt(CREATED);
        doc.setOutlineUpdatedAt(UPDATED);
        return doc;
    }

    private static OutlineDocument authoredDocument(String documentId, String subject, String name) {
        OutlineDocument doc = liveDocument();
        doc.setDocumentId(documentId);
        doc.setCreatedBySubject(subject);
        doc.setCreatedByName(name);
        doc.setUpdatedBySubject(subject);
        doc.setUpdatedByName(name);
        return doc;
    }

    private static OutlineDocument tombstonedDocument() {
        OutlineDocument doc = new OutlineDocument();
        doc.setWorkspaceId(WORKSPACE_ID);
        doc.setConnectionId(7L);
        doc.setDocumentId("doc-uuid-2");
        doc.setCollectionId("col-uuid-1");
        doc.setCollectionSlug("architecture");
        doc.setSlug("removed-doc");
        doc.setTitle("Removed Doc");
        // Removed upstream: body dropped, deleted_at stamped.
        doc.setBodyMarkdown(null);
        doc.setDeletedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return doc;
    }

    private static OutlineDocument archivedDocument() {
        OutlineDocument doc = liveDocument();
        doc.setDocumentId("doc-uuid-3");
        doc.setSlug("archived-doc");
        // Archived, not deleted: body/hash stay — only archivedAt is set.
        doc.setArchivedAt(Instant.parse("2026-03-01T00:00:00Z"));
        return doc;
    }

    private static OutlineCollection registeredCollection(String collectionId, String name) {
        OutlineCollection collection = new OutlineCollection();
        collection.setWorkspaceId(WORKSPACE_ID);
        collection.setConnectionId(7L);
        collection.setCollectionId(collectionId);
        collection.setName(name);
        return collection;
    }

    @Test
    @DisplayName("documentsForWorkspace maps entity fields onto the projected document")
    void documentsForWorkspace_mapsFieldsCorrectly() {
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(liveDocument()));

        List<ProjectedDocument> result = projector.documentsForWorkspace(WORKSPACE_ID);

        assertThat(result).singleElement().satisfies(projected -> {
            assertThat(projected.collectionSlug()).isEqualTo("architecture");
            assertThat(projected.slug()).isEqualTo("design-doc");
            assertThat(projected.title()).isEqualTo("Design Doc");
            assertThat(projected.bodyMarkdown()).isEqualTo("# Design\n\nContent.");
            assertThat(projected.deleted()).isFalse();
            // Vendor-neutral names on the SPI; the vendor prefix stays on the entity columns.
            assertThat(projected.createdAt()).isEqualTo(CREATED);
            assertThat(projected.updatedAt()).isEqualTo(UPDATED);
        });
    }

    @Test
    @DisplayName("a tombstoned row projects as deleted=true with a null body")
    void documentsForWorkspace_tombstonedRowIsDeletedWithNullBody() {
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(tombstonedDocument()));

        List<ProjectedDocument> result = projector.documentsForWorkspace(WORKSPACE_ID);

        assertThat(result).singleElement().satisfies(projected -> {
            assertThat(projected.deleted()).isTrue();
            assertThat(projected.bodyMarkdown()).isNull();
            assertThat(projected.slug()).isEqualTo("removed-doc");
        });
    }

    @Test
    @DisplayName("an archived (soft, recoverable) row projects archived=true and keeps its body, unlike a tombstone")
    void documentsForWorkspace_archivedRowProjectsArchivedTrueWithBodyIntact() {
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(archivedDocument()));

        List<ProjectedDocument> result = projector.documentsForWorkspace(WORKSPACE_ID);

        assertThat(result).singleElement().satisfies(projected -> {
            assertThat(projected.archived()).isTrue();
            assertThat(projected.deleted()).isFalse();
            assertThat(projected.bodyMarkdown()).isEqualTo("# Design\n\nContent.");
        });
    }

    @Test
    @DisplayName("a live (never-archived) row projects archived=false")
    void documentsForWorkspace_liveRowProjectsArchivedFalse() {
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(liveDocument()));

        List<ProjectedDocument> result = projector.documentsForWorkspace(WORKSPACE_ID);

        assertThat(result)
                .singleElement()
                .satisfies(projected -> assertThat(projected.archived()).isFalse());
    }

    @Test
    @DisplayName("collectionName resolves from the registered collection's captured name, keyed by collection id")
    void documentsForWorkspace_resolvesCollectionNameFromTheRegistry() {
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(liveDocument()));
        when(collectionRepository.findByWorkspaceIdOrderByCreatedAtAsc(WORKSPACE_ID))
                .thenReturn(List.of(registeredCollection("col-uuid-1", "Architecture")));

        List<ProjectedDocument> result = projector.documentsForWorkspace(WORKSPACE_ID);

        assertThat(result).singleElement().satisfies(projected -> {
            // collectionSlug (path identity) stays distinct from collectionName (display label).
            assertThat(projected.collectionSlug()).isEqualTo("architecture");
            assertThat(projected.collectionName()).isEqualTo("Architecture");
        });
    }

    @Test
    @DisplayName("an unregistered/unnamed collection degrades to a null collectionName")
    void documentsForWorkspace_unknownCollection_collectionNameIsNull() {
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(liveDocument()));
        when(collectionRepository.findByWorkspaceIdOrderByCreatedAtAsc(WORKSPACE_ID))
                .thenReturn(List.of());

        List<ProjectedDocument> result = projector.documentsForWorkspace(WORKSPACE_ID);

        assertThat(result)
                .singleElement()
                .satisfies(projected -> assertThat(projected.collectionName()).isNull());
    }

    @Test
    @DisplayName("author substrate projects and a linked author resolves to their workspace member id")
    void authorFields_projectedAndResolved() {
        OutlineDocument doc = authoredDocument("doc-uuid-1", "0aa1bb2c-user", "Ada Lovelace");
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(doc));
        when(identityResolver.resolveMemberId(WORKSPACE_ID, SERVER_URL, TEAM_ID, "0aa1bb2c-user"))
                .thenReturn(Optional.of(555L));

        List<ProjectedDocument> result = projector.documentsForWorkspace(WORKSPACE_ID);

        assertThat(result).singleElement().satisfies(projected -> {
            assertThat(projected.createdByName()).isEqualTo("Ada Lovelace");
            assertThat(projected.createdBySubject()).isEqualTo("0aa1bb2c-user");
            assertThat(projected.createdByMemberId()).isEqualTo(555L);
            assertThat(projected.updatedByName()).isEqualTo("Ada Lovelace");
            assertThat(projected.updatedByMemberId()).isEqualTo(555L);
        });
    }

    @Test
    @DisplayName("the resolver is consulted once per distinct subject, not once per field or per document")
    void resolverConsultedOncePerDistinctSubject() {
        // Two documents by the same author (creator == last editor on both) → four subject slots, one lookup.
        OutlineDocument first = authoredDocument("doc-uuid-1", "0aa1bb2c-user", "Ada Lovelace");
        OutlineDocument second = authoredDocument("doc-uuid-2", "0aa1bb2c-user", "Ada Lovelace");
        second.setSlug("second-doc");
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        projector.documentsForWorkspace(WORKSPACE_ID);

        verify(identityResolver, times(1)).resolveMemberId(WORKSPACE_ID, SERVER_URL, TEAM_ID, "0aa1bb2c-user");
    }

    @Test
    @DisplayName("an unlinked author degrades to name-only (memberId null) — the graceful floor")
    void unlinkedAuthor_degradesToNameOnly() {
        OutlineDocument doc = authoredDocument("doc-uuid-1", "0aa1bb2c-user", "Ada Lovelace");
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(doc));
        when(identityResolver.resolveMemberId(WORKSPACE_ID, SERVER_URL, TEAM_ID, "0aa1bb2c-user"))
                .thenReturn(Optional.empty());

        List<ProjectedDocument> result = projector.documentsForWorkspace(WORKSPACE_ID);

        assertThat(result).singleElement().satisfies(projected -> {
            assertThat(projected.createdByName()).isEqualTo("Ada Lovelace");
            assertThat(projected.createdByMemberId()).isNull();
        });
    }

    @Test
    @DisplayName("without an ACTIVE Outline connection authors degrade to name-only and the resolver is never called")
    void noActiveConnection_skipsResolution() {
        when(connectionService.findActive(WORKSPACE_ID, IntegrationKind.OUTLINE))
                .thenReturn(Optional.empty());
        OutlineDocument doc = authoredDocument("doc-uuid-1", "0aa1bb2c-user", "Ada Lovelace");
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(doc));

        List<ProjectedDocument> result = projector.documentsForWorkspace(WORKSPACE_ID);

        assertThat(result).singleElement().satisfies(projected -> {
            assertThat(projected.createdByName()).isEqualTo("Ada Lovelace");
            assertThat(projected.createdByMemberId()).isNull();
        });
        verify(identityResolver, never()).resolveMemberId(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("collaborator subjects project with memoised member resolution; names only for creator/last editor")
    void collaborators_projectWithResolvedMemberIds() {
        OutlineDocument doc = authoredDocument("doc-uuid-1", "0aa1bb2c-user", "Ada Lovelace");
        doc.setCollaboratorSubjects(List.of("0aa1bb2c-user", "9dd8ee7f-user", "0aa1bb2c-user"));
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(doc));
        when(identityResolver.resolveMemberId(WORKSPACE_ID, SERVER_URL, TEAM_ID, "0aa1bb2c-user"))
                .thenReturn(Optional.of(555L));
        when(identityResolver.resolveMemberId(WORKSPACE_ID, SERVER_URL, TEAM_ID, "9dd8ee7f-user"))
                .thenReturn(Optional.empty());

        List<ProjectedDocument> result = projector.documentsForWorkspace(WORKSPACE_ID);

        assertThat(result).singleElement().satisfies(projected -> {
            // Duplicate subjects collapse; insertion order holds.
            assertThat(projected.collaborators()).hasSize(2);
            assertThat(projected.collaborators().get(0).subject()).isEqualTo("0aa1bb2c-user");
            // Creator/last-editor collaborator carries the known display name + resolved member.
            assertThat(projected.collaborators().get(0).name()).isEqualTo("Ada Lovelace");
            assertThat(projected.collaborators().get(0).memberId()).isEqualTo(555L);
            // A middle editor has no name in the list payload — subject (+ member id if linked) only.
            assertThat(projected.collaborators().get(1).subject()).isEqualTo("9dd8ee7f-user");
            assertThat(projected.collaborators().get(1).name()).isNull();
            assertThat(projected.collaborators().get(1).memberId()).isNull();
        });
        // The memo covers collaborators too: the shared subject resolves once for author + collaborator slots.
        verify(identityResolver, times(1)).resolveMemberId(WORKSPACE_ID, SERVER_URL, TEAM_ID, "0aa1bb2c-user");
    }

    @Test
    @DisplayName("a row without collaborator subjects projects an empty collaborator list")
    void noCollaborators_projectsEmptyList() {
        when(documentRepository.findForProjection(eq(WORKSPACE_ID), any(Pageable.class)))
                .thenReturn(List.of(liveDocument()));

        assertThat(projector.documentsForWorkspace(WORKSPACE_ID))
                .singleElement()
                .satisfies(projected -> assertThat(projected.collaborators()).isEmpty());
    }

    @Test
    @DisplayName("documentsByReference expands a URL reference to its trailing path segment before querying")
    void documentsByReference_expandsUrlToTrailingSegment() {
        when(documentRepository.findByWorkspaceIdAndReferenceIn(eq(WORKSPACE_ID), refsCaptor.capture()))
                .thenReturn(List.of(liveDocument()));

        List<ProjectedDocument> result = projector.documentsByReference(
                WORKSPACE_ID, List.of("https://outline.example.com/doc/design-doc", "doc-uuid-1"));

        assertThat(result).hasSize(1);
        assertThat(refsCaptor.getValue())
                .contains("https://outline.example.com/doc/design-doc", "design-doc", "doc-uuid-1");
    }

    @Test
    @DisplayName("documentsByReference short-circuits on an empty reference set")
    void documentsByReference_emptyInputReturnsEmpty() {
        assertThat(projector.documentsByReference(WORKSPACE_ID, List.of())).isEmpty();
    }

    @Test
    @DisplayName("documentsByReference resolves a full-URL reference even when a webhook-synced row stored "
            + "only the short Outline urlId as its slug, not the full title-slug")
    void documentsByReference_legacyShortSlugRow_stillResolvesAgainstFullUrlReference() {
        OutlineDocument legacyRow = liveDocument();
        // A webhook targeted-refresh row's slug may be the bare urlId instead of the full
        // "<title>-<urlId>" segment full reconcile derives from the document tree; reference
        // matching must handle both forms.
        legacyRow.setSlug("psUl8qCles");
        when(documentRepository.findByWorkspaceIdAndReferenceIn(eq(WORKSPACE_ID), refsCaptor.capture()))
                .thenReturn(List.of(legacyRow));

        List<ProjectedDocument> result = projector.documentsByReference(
                WORKSPACE_ID, List.of("https://wiki.example.com/doc/setup-guide-psUl8qCles"));

        assertThat(result).hasSize(1);
        // The trailing "-<urlId>" segment is derived and included as a candidate token.
        assertThat(refsCaptor.getValue()).contains("setup-guide-psUl8qCles", "psUl8qCles");
    }

    @Test
    @DisplayName("documentsByReference does not derive a urlId candidate from a short trailing segment")
    void documentsByReference_shortTrailingSegment_noUrlIdCandidateDerived() {
        when(documentRepository.findByWorkspaceIdAndReferenceIn(eq(WORKSPACE_ID), refsCaptor.capture()))
                .thenReturn(List.of());

        projector.documentsByReference(WORKSPACE_ID, List.of("https://wiki.example.com/doc/design-doc"));

        // "design-doc" splits to "design" / "doc" — "doc" is far too short to be mistaken for a urlId.
        assertThat(refsCaptor.getValue()).doesNotContain("doc");
    }

    @Test
    @DisplayName("searchDocuments projects the selector's ranked hits in order")
    void searchDocuments_projectsSelectorHitsInOrder() {
        OutlineDocument second = liveDocument();
        second.setDocumentId("doc-uuid-9");
        second.setSlug("runbook");
        when(documentSelector.select(WORKSPACE_ID, "deploy OR rollback", 5))
                .thenReturn(List.of(liveDocument(), second));

        List<ProjectedDocument> result = projector.searchDocuments(WORKSPACE_ID, "deploy OR rollback", 5);

        assertThat(result).extracting(ProjectedDocument::slug).containsExactly("design-doc", "runbook");
    }

    @Test
    @DisplayName("searchDocuments with no hits skips the author/collection lookups entirely")
    void searchDocuments_noHits_returnsEmptyWithoutContextLookups() {
        when(documentSelector.select(WORKSPACE_ID, "nothing", 5)).thenReturn(List.of());

        assertThat(projector.searchDocuments(WORKSPACE_ID, "nothing", 5)).isEmpty();
        verify(connectionService, never()).findActive(anyLong(), any());
    }

    @Test
    void documentsForWorkspace_withholdsMirrorAfterOriginApprovalIsRemoved() {
        OutlineDocumentProjector denied = new OutlineDocumentProjector(
                documentRepository,
                collectionRepository,
                connectionService,
                identityResolver,
                documentSelector,
                new OutlineOriginPolicy(Set.of()));

        assertThat(denied.documentsForWorkspace(WORKSPACE_ID)).isEmpty();
        verify(documentRepository, never()).findForProjection(anyLong(), any());
    }
}
