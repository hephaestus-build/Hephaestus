package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection.ProjectedDocument;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class OutlineDocumentContentSourceTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 99L;
    private static final long PR_ID = 456L;
    private static final long ISSUE_ID = 789L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private DocumentProjection projection;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private OutlineDocumentContentSource provider;

    @BeforeEach
    void setUp() {
        provider = new OutlineDocumentContentSource(
                projection, objectMapper, pullRequestRepository, issueRepository, chatMessageRepository);
    }

    private ContextRequest.PracticeReviewRequest prRequest(String body) {
        PullRequest pr = new PullRequest();
        pr.setBody(body);
        lenient().when(pullRequestRepository.findById(PR_ID)).thenReturn(Optional.of(pr));
        AgentJob job = new AgentJob();
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("pull_request_id", PR_ID);
        job.setMetadata(metadata);
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        job.setWorkspace(workspace);
        return new ContextRequest.PracticeReviewRequest(job);
    }

    private ContextRequest.IssueReviewRequest issueRequest(String body) {
        Issue issue = new Issue();
        issue.setBody(body);
        lenient().when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        AgentJob job = new AgentJob();
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("issue_id", ISSUE_ID);
        job.setMetadata(metadata);
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        job.setWorkspace(workspace);
        return new ContextRequest.IssueReviewRequest(job);
    }

    private ContextRequest.MentorChatRequest mentorRequest() {
        return new ContextRequest.MentorChatRequest(WORKSPACE_ID, 7L, UUID.randomUUID());
    }

    private static ProjectedDocument doc(String collection, String slug, String title, String body) {
        return ProjectedDocument.withoutAuthors(collection, slug, title, body, false);
    }

    private static ProjectedDocument tombstone(String collection, String slug, String title) {
        return ProjectedDocument.withoutAuthors(collection, slug, title, null, true);
    }

    private static final Instant CREATED = Instant.parse("2025-11-01T08:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-02-03T09:30:00Z");

    private static ProjectedDocument authoredDoc(
            String collection,
            String slug,
            String title,
            String body,
            @Nullable String authorName,
            @Nullable Long authorMemberId) {
        return authoredDoc(collection, slug, title, body, authorName, authorMemberId, List.of());
    }

    private static ProjectedDocument authoredDoc(
            String collection,
            String slug,
            String title,
            String body,
            @Nullable String authorName,
            @Nullable Long authorMemberId,
            List<ProjectedDocument.Collaborator> collaborators) {
        return new ProjectedDocument(
                collection,
                slug,
                title,
                body,
                false,
                CREATED,
                UPDATED,
                authorName,
                "0aa1bb2c-user",
                authorMemberId,
                authorName,
                "0aa1bb2c-user",
                authorMemberId,
                collaborators,
                false,
                null);
    }

    private static ProjectedDocument archivedDoc(String collection, String slug, String title, String body) {
        return new ProjectedDocument(
                collection,
                slug,
                title,
                body,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                true,
                null);
    }

    private static ProjectedDocument docWithCollectionName(
            String collectionSlug, String collectionName, String slug, String title, String body) {
        return new ProjectedDocument(
                collectionSlug,
                slug,
                title,
                body,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                false,
                collectionName);
    }

    private void extractsReferences(String body, String... refs) {
        when(projection.extractReferences(body)).thenReturn(new LinkedHashSet<>(List.of(refs)));
    }

    @Test
    void supportsMentorAndReviewVariantsOnly() {
        assertThat(provider.supports(mentorRequest())).isTrue();
        assertThat(provider.supports(prRequest("no links"))).isTrue();
        assertThat(provider.supports(issueRequest("no links"))).isTrue();
        assertThat(provider.supports(new ContextRequest.ConversationReviewRequest(new AgentJob())))
                .isFalse();
    }

    @Test
    void isBestEffort() {
        assertThat(provider.required()).isFalse();
    }

    @Test
    void reviewPathStagesRawBodiesAndListsEveryLinkedDocumentByteStably() throws Exception {
        String body =
                "Design in https://wiki.example.com/doc/onboarding-guide-a1b2c3 and https://wiki.example.com/doc/old-doc-z9";
        extractsReferences(
                body,
                "https://wiki.example.com/doc/onboarding-guide-a1b2c3",
                "https://wiki.example.com/doc/old-doc-z9");
        // documents.export bodies usually open with their own "# {title}" H1; the file is the body either way.
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(
                        doc("Engineering", "onboarding-guide", "Onboarding Guide", "# Onboarding Guide\n\nWelcome."),
                        tombstone("Engineering", "old-doc", "Old Doc")));

        Map<String, byte[]> first = new LinkedHashMap<>();
        provider.contribute(prRequest(body), first);
        Map<String, byte[]> second = new LinkedHashMap<>();
        provider.contribute(prRequest(body), second);

        String livePath = "inputs/context/outline/engineering/onboarding-guide.md";
        String tombstonePath = "inputs/context/outline/engineering/old-doc.md";
        // A document the mirror no longer holds stages no file; the index says the link pointed at it.
        assertThat(first.keySet()).containsExactlyInAnyOrder(livePath, OutlineDocumentContentSource.REVIEW_INDEX_KEY);
        assertThat(new String(first.get(livePath), StandardCharsets.UTF_8)).isEqualTo("# Onboarding Guide\n\nWelcome.");

        JsonNode index = objectMapper.readTree(first.get(OutlineDocumentContentSource.REVIEW_INDEX_KEY));
        assertThat(index.propertyNames()).containsExactlyInAnyOrder("documents", "unresolvedReferences");
        JsonNode documents = index.get("documents");
        assertThat(documents).hasSize(2);
        JsonNode gone = documents.get(0);
        assertThat(gone.get("path").asString()).isEqualTo(tombstonePath);
        assertThat(gone.get("available").asBoolean()).isFalse();
        assertThat(gone.get("title").asString()).isEqualTo("Old Doc");
        JsonNode live = documents.get(1);
        assertThat(live.propertyNames())
                .containsExactlyInAnyOrder(
                        "path",
                        "available",
                        "selectedBy",
                        "collection",
                        "collectionName",
                        "slug",
                        "title",
                        "createdBy",
                        "updatedBy",
                        "updatedAt",
                        "archived",
                        "contributors");
        assertThat(live.get("path").asString()).isEqualTo(livePath);
        assertThat(live.get("available").asBoolean()).isTrue();
        assertThat(live.get("selectedBy").asString()).isEqualTo("LINK");
        assertThat(live.get("collection").asString()).isEqualTo("Engineering");
        assertThat(live.get("slug").asString()).isEqualTo("onboarding-guide");
        assertThat(live.get("title").asString()).isEqualTo("Onboarding Guide");
        assertThat(live.get("archived").asBoolean()).isFalse();
        assertThat(index.get("unresolvedReferences")).isEmpty();

        assertThat(first.get(livePath)).isEqualTo(second.get(livePath));
        assertThat(first.get(OutlineDocumentContentSource.REVIEW_INDEX_KEY))
                .isEqualTo(second.get(OutlineDocumentContentSource.REVIEW_INDEX_KEY));
    }

    @Test
    void reviewPathListsAnEvictedBodyAsUnavailableWithoutStagingAFile() throws Exception {
        String body = "Design in https://wiki.example.com/doc/onboarding-guide-a1b2c3";
        extractsReferences(body, "https://wiki.example.com/doc/onboarding-guide-a1b2c3");
        // Row present, body evicted under the mirror's size cap: not deleted, still nothing to stage.
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(ProjectedDocument.withoutAuthors(
                        "Engineering", "onboarding-guide", "Onboarding Guide", null, false)));

        var captured = provider.capture(prRequest(body), provider.sourceKinds());

        assertThat(captured.files()).containsOnlyKeys(OutlineDocumentContentSource.REVIEW_INDEX_KEY);
        JsonNode entry = objectMapper
                .readTree(captured.files().get(OutlineDocumentContentSource.REVIEW_INDEX_KEY))
                .get("documents")
                .get(0);
        assertThat(entry.get("path").asString()).isEqualTo("inputs/context/outline/engineering/onboarding-guide.md");
        assertThat(entry.get("available").asBoolean()).isFalse();
        assertThat(captured.contentStates()).containsValue(SourceContentState.NON_EMPTY);
    }

    // --- (b) mentor path: single JSON array ---

    @Test
    void mentorPathEmitsSingleJsonArray() throws Exception {
        when(projection.documentsForWorkspace(WORKSPACE_ID))
                .thenReturn(List.of(
                        doc("Engineering", "onboarding-guide", "Onboarding Guide", "Welcome."),
                        doc("Product", "roadmap", "Roadmap", "Q3 plans.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        assertThat(files.keySet()).containsExactly("inputs/context/outline_docs.json");
        JsonNode root = objectMapper.readTree(files.get("inputs/context/outline_docs.json"));
        assertThat(root.isArray()).isTrue();
        assertThat(root).hasSize(2);
        JsonNode entry = root.get(0);
        assertThat(entry.get("collection").asString()).isEqualTo("Engineering");
        assertThat(entry.get("slug").asString()).isEqualTo("onboarding-guide");
        assertThat(entry.get("title").asString()).isEqualTo("Onboarding Guide");
        assertThat(entry.get("body").asString()).isEqualTo("Welcome.");

        // ELT contract: the connector emits ONLY the raw native doc fields — no verdict/severity/practice-shaped
        // field is computed into the payload (that Transform belongs downstream).
        assertThat(entry.size()).isEqualTo(4);
        assertThat(entry.has("collection")).isTrue();
        assertThat(entry.has("slug")).isTrue();
        assertThat(entry.has("title")).isTrue();
        assertThat(entry.has("body")).isTrue();
        assertThat(entry.has("verdict")).isFalse();
        assertThat(entry.has("severity")).isFalse();
    }

    @Test
    void mentorPathEmitsCollectionNameOnlyWhenCaptured() throws Exception {
        when(projection.documentsForWorkspace(WORKSPACE_ID))
                .thenReturn(List.of(
                        docWithCollectionName(
                                "engineering", "Engineering Docs", "onboarding-guide", "Onboarding Guide", "Welcome."),
                        doc("product", "roadmap", "Roadmap", "Q3 plans.") // no captured collection name
                        ));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        JsonNode root = objectMapper.readTree(files.get("inputs/context/outline_docs.json"));
        // "collection" stays the path/slug identity; "collection_name" is the additive human label.
        assertThat(root.get(0).get("collection").asString()).isEqualTo("engineering");
        assertThat(root.get(0).get("collection_name").asString()).isEqualTo("Engineering Docs");
        assertThat(root.get(1).has("collection_name")).isFalse();
    }

    // --- (b') authorship exposure ---

    @Test
    void mentorPathEmitsAuthorNameAndResolvedMemberId() throws Exception {
        when(projection.documentsForWorkspace(WORKSPACE_ID))
                .thenReturn(List.of(
                        authoredDoc(
                                "Engineering",
                                "onboarding-guide",
                                "Onboarding Guide",
                                "Welcome.",
                                "Ada Lovelace",
                                555L),
                        authoredDoc("Product", "roadmap", "Roadmap", "Q3 plans.", "Grace Hopper", null) // unlinked
                        ));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        JsonNode root = objectMapper.readTree(files.get("inputs/context/outline_docs.json"));
        JsonNode linked = root.get(0);
        assertThat(linked.get("author").asString()).isEqualTo("Ada Lovelace");
        assertThat(linked.get("author_member_id").asLong()).isEqualTo(555L);
        assertThat(linked.get("last_edited_by").asString()).isEqualTo("Ada Lovelace");
        assertThat(linked.get("last_edited_by_member_id").asLong()).isEqualTo(555L);
        // Unlinked author degrades to name-only: the member-id key is simply absent, never null.
        JsonNode unlinked = root.get(1);
        assertThat(unlinked.get("author").asString()).isEqualTo("Grace Hopper");
        assertThat(unlinked.has("author_member_id")).isFalse();
    }

    @Test
    void mentorPathEmitsTimestampsAndCollaborators() throws Exception {
        when(projection.documentsForWorkspace(WORKSPACE_ID))
                .thenReturn(List.of(
                        authoredDoc(
                                "Engineering",
                                "onboarding-guide",
                                "Onboarding Guide",
                                "Welcome.",
                                "Ada Lovelace",
                                555L,
                                List.of(
                                        new ProjectedDocument.Collaborator("0aa1bb2c-user", "Ada Lovelace", 555L),
                                        new ProjectedDocument.Collaborator("7cc9dd0e-user", null, null))),
                        doc("Product", "roadmap", "Roadmap", "Q3 plans.") // no substrate captured
                        ));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        JsonNode root = objectMapper.readTree(files.get("inputs/context/outline_docs.json"));
        JsonNode rich = root.get(0);
        // ISO-8601 upstream clocks, present only when captured.
        assertThat(rich.get("created").asString()).isEqualTo("2025-11-01T08:00:00Z");
        assertThat(rich.get("last_updated").asString()).isEqualTo("2026-02-03T09:30:00Z");
        // Collaborators are machine-facing here: subjects always, name/member id only where known.
        JsonNode collaborators = rich.get("collaborators");
        assertThat(collaborators.isArray()).isTrue();
        assertThat(collaborators).hasSize(2);
        assertThat(collaborators.get(0).get("subject").asString()).isEqualTo("0aa1bb2c-user");
        assertThat(collaborators.get(0).get("name").asString()).isEqualTo("Ada Lovelace");
        assertThat(collaborators.get(0).get("member_id").asLong()).isEqualTo(555L);
        assertThat(collaborators.get(1).get("subject").asString()).isEqualTo("7cc9dd0e-user");
        assertThat(collaborators.get(1).has("name")).isFalse();
        assertThat(collaborators.get(1).has("member_id")).isFalse();

        JsonNode bare = root.get(1);
        assertThat(bare.has("created")).isFalse();
        assertThat(bare.has("last_updated")).isFalse();
        assertThat(bare.has("collaborators")).isFalse();
    }

    @Test
    void reviewPathKeepsProvenanceInTheIndexAndOutOfTheBody() throws Exception {
        String body = "Design in https://wiki.example.com/doc/onboarding-guide-a1b2c3";
        extractsReferences(body, "https://wiki.example.com/doc/onboarding-guide-a1b2c3");
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(authoredDoc(
                        "Engineering", "onboarding-guide", "Onboarding Guide", "Welcome.", "Ada Lovelace", 555L)));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest(body), files);

        assertThat(new String(
                        files.get("inputs/context/outline/engineering/onboarding-guide.md"), StandardCharsets.UTF_8))
                .isEqualTo("Welcome.");
        JsonNode entry = objectMapper
                .readTree(files.get(OutlineDocumentContentSource.REVIEW_INDEX_KEY))
                .get("documents")
                .get(0);
        assertThat(entry.get("createdBy").asString()).isEqualTo("Ada Lovelace");
        assertThat(entry.get("updatedBy").asString()).isEqualTo("Ada Lovelace");
        assertThat(entry.get("updatedAt").asString()).isEqualTo("2026-02-03T09:30:00Z");
    }

    @Test
    void reviewPathIndexCarriesTheCollectionDisplayNameBesideItsSlug() throws Exception {
        String body = "Design in https://wiki.example.com/doc/onboarding-guide-a1b2c3";
        extractsReferences(body, "https://wiki.example.com/doc/onboarding-guide-a1b2c3");
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(docWithCollectionName(
                        "engineering", "Engineering Docs", "onboarding-guide", "Onboarding Guide", "Welcome.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest(body), files);

        // The path segment stays the slug, not the display name.
        assertThat(files.keySet())
                .containsExactlyInAnyOrder(
                        "inputs/context/outline/engineering/onboarding-guide.md",
                        OutlineDocumentContentSource.REVIEW_INDEX_KEY);
        JsonNode entry = objectMapper
                .readTree(files.get(OutlineDocumentContentSource.REVIEW_INDEX_KEY))
                .get("documents")
                .get(0);
        assertThat(entry.get("collection").asString()).isEqualTo("engineering");
        assertThat(entry.get("collectionName").asString()).isEqualTo("Engineering Docs");
    }

    @Test
    void reviewPathStagesAnArchivedDocumentWithContentIntactAndSaysSoInTheIndex() throws Exception {
        String body = "See https://wiki.example.com/doc/legacy-adr-a1b2c3";
        extractsReferences(body, "https://wiki.example.com/doc/legacy-adr-a1b2c3");
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(
                        archivedDoc("Engineering", "legacy-adr", "Legacy ADR", "This decision was superseded.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest(body), files);

        assertThat(new String(files.get("inputs/context/outline/engineering/legacy-adr.md"), StandardCharsets.UTF_8))
                .isEqualTo("This decision was superseded.");
        JsonNode entry = objectMapper
                .readTree(files.get(OutlineDocumentContentSource.REVIEW_INDEX_KEY))
                .get("documents")
                .get(0);
        assertThat(entry.get("available").asBoolean()).isTrue();
        assertThat(entry.get("archived").asBoolean()).isTrue();
    }

    @Test
    void reviewPathIndexListsContributorsByNameWithoutLeakingRawSubjects() throws Exception {
        String body = "Design in https://wiki.example.com/doc/onboarding-guide-a1b2c3";
        extractsReferences(body, "https://wiki.example.com/doc/onboarding-guide-a1b2c3");
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(authoredDoc(
                        "Engineering",
                        "onboarding-guide",
                        "Onboarding Guide",
                        "Welcome.",
                        "Ada Lovelace",
                        555L,
                        List.of(
                                new ProjectedDocument.Collaborator("0aa1bb2c-user", "Ada Lovelace", 555L),
                                new ProjectedDocument.Collaborator("7cc9dd0e-user", null, null),
                                new ProjectedDocument.Collaborator("8dd0ee1f-user", null, 777L)))));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest(body), files);

        byte[] indexBytes = files.get(OutlineDocumentContentSource.REVIEW_INDEX_KEY);
        JsonNode entry = objectMapper.readTree(indexBytes).get("documents").get(0);
        assertThat(entry.get("contributors").valueStream().map(JsonNode::asString))
                .containsExactly("Ada Lovelace");
        String index = new String(indexBytes, StandardCharsets.UTF_8);
        assertThat(index).doesNotContain("0aa1bb2c-user", "7cc9dd0e-user", "8dd0ee1f-user");
    }

    @Test
    void reviewPathIndexListsNoContributorsWhenNoneHaveAName() throws Exception {
        String body = "Design in https://wiki.example.com/doc/onboarding-guide-a1b2c3";
        extractsReferences(body, "https://wiki.example.com/doc/onboarding-guide-a1b2c3");
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(authoredDoc(
                        "Engineering",
                        "onboarding-guide",
                        "Onboarding Guide",
                        "Welcome.",
                        null,
                        null,
                        List.of(new ProjectedDocument.Collaborator("7cc9dd0e-user", null, null)))));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest(body), files);

        JsonNode entry = objectMapper
                .readTree(files.get(OutlineDocumentContentSource.REVIEW_INDEX_KEY))
                .get("documents")
                .get(0);
        assertThat(entry.get("contributors")).isEmpty();
        assertThat(entry.get("createdBy").isNull()).isTrue();
    }

    // --- (c) review path materializes only the LINKED docs ---

    @Test
    void reviewPathResolvesOnlyReferencedDocuments() {
        String body = "Follow https://wiki.example.com/doc/onboarding-guide-a1b2c3 before you start.";
        extractsReferences(body, "https://wiki.example.com/doc/onboarding-guide-a1b2c3");
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(doc("Engineering", "onboarding-guide", "Onboarding Guide", "Welcome.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest(body), files);

        // Exactly the one linked document is materialised — never the whole corpus.
        assertThat(files.keySet())
                .containsExactlyInAnyOrder(
                        "inputs/context/outline/engineering/onboarding-guide.md",
                        OutlineDocumentContentSource.REVIEW_INDEX_KEY);
        verify(projection, never()).documentsForWorkspace(anyLong());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> refs = ArgumentCaptor.forClass(Collection.class);
        verify(projection).documentsByReference(eq(WORKSPACE_ID), refs.capture());
        assertThat(refs.getValue()).containsExactly("https://wiki.example.com/doc/onboarding-guide-a1b2c3");
    }

    @Test
    void issueReviewPathMaterializesLinkedDocs() {
        String body = "See https://wiki.example.com/doc/spec-x for the spec.";
        extractsReferences(body, "https://wiki.example.com/doc/spec-x");
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(doc("Product", "spec", "Spec", "Details.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(issueRequest(body), files);

        assertThat(files.keySet())
                .containsExactlyInAnyOrder(
                        "inputs/context/outline/product/spec.md", OutlineDocumentContentSource.REVIEW_INDEX_KEY);
    }

    // --- (e) unresolved documentation link visibility ---

    @Test
    void reviewPathListsEveryUnresolvedReferenceInTheIndexWhenNothingResolves() throws Exception {
        String body = "Design in https://wiki.example.com/doc/vanished-doc-a1b2c3.";
        extractsReferences(body, "https://wiki.example.com/doc/vanished-doc-a1b2c3");
        when(projection.documentsByReference(eq(WORKSPACE_ID), any())).thenReturn(List.of());

        var captured = provider.capture(prRequest(body), provider.sourceKinds());

        assertThat(captured.files()).containsOnlyKeys(OutlineDocumentContentSource.REVIEW_INDEX_KEY);
        JsonNode index = objectMapper.readTree(captured.files().get(OutlineDocumentContentSource.REVIEW_INDEX_KEY));
        assertThat(index.get("documents")).isEmpty();
        assertThat(index.get("unresolvedReferences").valueStream().map(JsonNode::asString))
                .containsExactly("https://wiki.example.com/doc/vanished-doc-a1b2c3");
        assertThat(captured.contentStates()).containsValue(SourceContentState.EMPTY);
    }

    @Test
    void reviewPathListsOnlyTheReferencesThatResolvedToNoDocument() throws Exception {
        String body =
                "Design in https://wiki.example.com/doc/onboarding-guide-a1b2c3 and https://wiki.example.com/doc/vanished-doc-z9";
        extractsReferences(
                body,
                "https://wiki.example.com/doc/onboarding-guide-a1b2c3",
                "https://wiki.example.com/doc/vanished-doc-z9");
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(doc("Engineering", "onboarding-guide", "Onboarding Guide", "Welcome.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest(body), files);

        JsonNode index = objectMapper.readTree(files.get(OutlineDocumentContentSource.REVIEW_INDEX_KEY));
        assertThat(index.get("documents")).hasSize(1);
        assertThat(index.get("unresolvedReferences").valueStream().map(JsonNode::asString))
                .containsExactly("https://wiki.example.com/doc/vanished-doc-z9");
    }

    @Test
    void reviewPathListsNoUnresolvedReferenceWhenNoneWereExtracted() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest("A PR body with no wiki links at all."), files);

        JsonNode index = objectMapper.readTree(files.get(OutlineDocumentContentSource.REVIEW_INDEX_KEY));
        assertThat(index.get("unresolvedReferences")).isEmpty();
        verify(projection, never()).documentsByReference(anyLong(), any());
    }

    // --- (d) no-op when there is nothing to materialise ---

    @Test
    void mentorPathWritesNothingWhenWorkspaceHasNoDocuments() {
        when(projection.documentsForWorkspace(WORKSPACE_ID)).thenReturn(List.of());

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        assertThat(files).isEmpty();
    }

    /**
     * No linked or retrieved documentation still stages the index, holding an empty list: "no
     * documentation directory" and "searched, nothing bore on this change" license opposite conclusions,
     * and only the index makes them distinguishable.
     */
    @Test
    void reviewPathStagesAnEmptyIndexWhenArtifactHasNoOutlineLinks() throws Exception {
        var captured = provider.capture(prRequest("A PR body with no wiki links at all."), provider.sourceKinds());

        assertThat(captured.files()).containsOnlyKeys(OutlineDocumentContentSource.REVIEW_INDEX_KEY);
        var index = objectMapper.readTree(captured.files().get(OutlineDocumentContentSource.REVIEW_INDEX_KEY));
        assertThat(index.propertyNames()).containsExactlyInAnyOrder("documents", "unresolvedReferences");
        assertThat(index.get("documents")).isEmpty();
        assertThat(captured.contentStates()).containsValue(SourceContentState.EMPTY);
        verify(projection, never()).documentsByReference(anyLong(), any());
    }

    // --- relevance retrieval ---

    @Test
    void deriveQueryTextStripsUrlsAndOrJoinsDistinctLowercasedTerms() {
        String query = OutlineDocumentContentSource.deriveQueryText(
                "Fix Webhook retries",
                "See https://wiki.example.com/doc/setup-abc123 — the **webhook** retry backoff is wrong.");

        assertThat(query).isEqualTo("fix OR webhook OR retries OR see OR the OR retry OR backoff OR wrong");
    }

    @Test
    void deriveQueryTextIsEmptyWhenOnlyNoiseRemains() {
        assertThat(OutlineDocumentContentSource.deriveQueryText(null, "a b https://wiki.example.com/doc/x-1 !!"))
                .isEmpty();
        assertThat(OutlineDocumentContentSource.deriveQueryText(null, null)).isEmpty();
    }

    @Test
    void reviewPathFillsWithRelevanceHitsWhenLinksUndershootTheTarget() throws Exception {
        String body = "Rework retry backoff. See https://wiki.example.com/doc/setup-guide-abc123.";
        extractsReferences(body, "https://wiki.example.com/doc/setup-guide-abc123");
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(doc("Ops", "setup-guide", "Setup Guide", "Steps.")));
        when(projection.searchDocuments(eq(WORKSPACE_ID), anyString(), eq(4)))
                .thenReturn(List.of(
                        doc("Ops", "setup-guide", "Setup Guide", "Steps."), // already linked — deduped by path
                        doc("Ops", "retry-policy", "Retry Policy", "Backoff rules."),
                        doc("Dev", "error-budget", "Error Budget", "Limits."),
                        doc("Dev", "overflow", "Overflow", "Beyond the fill target.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest(body), files);

        // Linked doc first, then retrieval hits in rank order up to the target; the dupe and overflow drop.
        assertThat(files.keySet())
                .containsExactlyInAnyOrder(
                        "inputs/context/outline/ops/setup-guide.md",
                        "inputs/context/outline/ops/retry-policy.md",
                        "inputs/context/outline/dev/error-budget.md",
                        OutlineDocumentContentSource.REVIEW_INDEX_KEY);
        assertThat(new String(files.get("inputs/context/outline/ops/retry-policy.md"), StandardCharsets.UTF_8))
                .isEqualTo("Backoff rules.");
        JsonNode documents = objectMapper
                .readTree(files.get(OutlineDocumentContentSource.REVIEW_INDEX_KEY))
                .get("documents");
        assertThat(documents
                        .valueStream()
                        .map(entry -> entry.get("slug").asString() + ":"
                                + entry.get("selectedBy").asString()))
                .containsExactly("setup-guide:LINK", "retry-policy:SEARCH", "error-budget:SEARCH");
    }

    @Test
    void reviewPathRetrievesRelevantDocsWhenTheArtifactLinksNothing() {
        String body = "Change the webhook retry backoff so bursts stop dead-lettering.";
        when(projection.searchDocuments(eq(WORKSPACE_ID), anyString(), eq(3)))
                .thenReturn(List.of(doc("Ops", "retry-policy", "Retry Policy", "Backoff rules.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest(body), files);

        assertThat(files.keySet())
                .containsExactlyInAnyOrder(
                        "inputs/context/outline/ops/retry-policy.md", OutlineDocumentContentSource.REVIEW_INDEX_KEY);
        verify(projection, never()).documentsByReference(anyLong(), any());
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(projection).searchDocuments(eq(WORKSPACE_ID), query.capture(), eq(3));
        assertThat(query.getValue()).contains("webhook OR retry");
    }

    @Test
    void reviewPathSkipsRetrievalWhenLinksAlreadyMeetTheTarget() {
        String body =
                "See https://wiki.example.com/doc/alpha-1 https://wiki.example.com/doc/beta-2 https://wiki.example.com/doc/gamma-3";
        extractsReferences(
                body,
                "https://wiki.example.com/doc/alpha-1",
                "https://wiki.example.com/doc/beta-2",
                "https://wiki.example.com/doc/gamma-3");
        when(projection.documentsByReference(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(
                        doc("Ops", "alpha", "Alpha", "A."),
                        doc("Ops", "beta", "Beta", "B."),
                        doc("Ops", "gamma", "Gamma", "C.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest(body), files);

        assertThat(files).hasSize(4);
        verify(projection, never()).searchDocuments(anyLong(), anyString(), anyInt());
    }

    @Test
    void mentorPathRanksByTheCurrentUserMessageWhenOneExists() throws Exception {
        UUID messageId = UUID.randomUUID();
        ContextRequest.MentorChatRequest request =
                new ContextRequest.MentorChatRequest(WORKSPACE_ID, 7L, UUID.randomUUID(), messageId);
        when(chatMessageRepository.findById(messageId))
                .thenReturn(Optional.of(userMessage("How do we deploy the webhook server?")));
        when(projection.searchDocuments(
                        eq(WORKSPACE_ID), anyString(), eq(OutlineDocumentContentSource.MAX_MENTOR_DOCUMENTS)))
                .thenReturn(List.of(doc("Ops", "deploy-guide", "Deploy Guide", "Steps.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(request, files);

        JsonNode root = objectMapper.readTree(files.get("inputs/context/outline_docs.json"));
        assertThat(root).hasSize(1);
        assertThat(root.get(0).get("slug").asString()).isEqualTo("deploy-guide");
        verify(projection, never()).documentsForWorkspace(anyLong());
    }

    @Test
    void mentorPathFallsBackToRecencyWhenTheQueryMatchesNothing() {
        UUID messageId = UUID.randomUUID();
        ContextRequest.MentorChatRequest request =
                new ContextRequest.MentorChatRequest(WORKSPACE_ID, 7L, UUID.randomUUID(), messageId);
        when(chatMessageRepository.findById(messageId)).thenReturn(Optional.of(userMessage("something niche")));
        when(projection.searchDocuments(
                        eq(WORKSPACE_ID), anyString(), eq(OutlineDocumentContentSource.MAX_MENTOR_DOCUMENTS)))
                .thenReturn(List.of());
        when(projection.documentsForWorkspace(WORKSPACE_ID))
                .thenReturn(List.of(doc("Engineering", "onboarding-guide", "Onboarding Guide", "Welcome.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(request, files);

        assertThat(files.keySet()).containsExactly("inputs/context/outline_docs.json");
    }

    private ChatMessage userMessage(String text) {
        ChatMessage message = new ChatMessage();
        ArrayNode parts = objectMapper.createArrayNode();
        ObjectNode part = parts.addObject();
        part.put("type", "text");
        part.put("text", text);
        message.setParts(parts);
        return message;
    }
}
