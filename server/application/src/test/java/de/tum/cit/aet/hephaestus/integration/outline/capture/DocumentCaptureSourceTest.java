package de.tum.cit.aet.hephaestus.integration.outline.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentProjection;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentProjection.ProjectedDocument;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorMessageQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DocumentCaptureSourceTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 99L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private DocumentProjection projection;

    @Mock
    private MentorMessageQuery mentorMessages;

    private DocumentCaptureSource provider;

    @BeforeEach
    void setUp() {
        provider = new DocumentCaptureSource(projection, objectMapper, mentorMessages);
    }

    private MentorContextRequest mentorRequest() {
        return new MentorContextRequest(WORKSPACE_ID, 7L, UUID.randomUUID(), null);
    }

    private static ProjectedDocument doc(String collection, String slug, String title, String body) {
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
                false,
                null,
                null,
                null,
                null);
    }

    private static final Instant CREATED = Instant.parse("2025-11-01T08:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-02-03T09:30:00Z");

    private static ProjectedDocument authoredDoc(
            String collection,
            String slug,
            String title,
            String body,
            @Nullable String authorName,
            @Nullable Long authorAccountId) {
        return authoredDoc(collection, slug, title, body, authorName, authorAccountId, List.of());
    }

    private static ProjectedDocument authoredDoc(
            String collection,
            String slug,
            String title,
            String body,
            @Nullable String authorName,
            @Nullable Long authorAccountId,
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
                authorAccountId,
                authorName,
                "0aa1bb2c-user",
                authorAccountId,
                collaborators,
                false,
                null,
                null,
                null,
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
                collectionName,
                null,
                null,
                null);
    }

    @Test
    void isBestEffort() {
        assertThat(provider.required()).isFalse();
    }

    @Test
    void shouldSayThatTheTeamHasMoreDocumentsThanTheListShows() throws Exception {
        var many = IntStream.rangeClosed(0, DocumentCaptureSource.MAX_MENTOR_DOCUMENTS)
                .mapToObj(index -> doc("Engineering", "doc-" + index, "Doc " + index, "Body."))
                .toList();
        when(projection.documentsForWorkspace(WORKSPACE_ID)).thenReturn(many);

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        JsonNode root = objectMapper.readTree(Objects.requireNonNull(files.get("inputs/context/outline_docs.json")));
        assertThat(root.path("documents")).hasSize(DocumentCaptureSource.MAX_MENTOR_DOCUMENTS);
        assertThat(root.path("hasMore").asBoolean()).isTrue();
    }

    @Test
    void mentorPathEmitsSingleJsonArray() throws Exception {
        when(projection.documentsForWorkspace(WORKSPACE_ID))
                .thenReturn(List.of(
                        doc("Engineering", "onboarding-guide", "Onboarding Guide", "Welcome."),
                        doc("Product", "roadmap", "Roadmap", "Q3 plans.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        assertThat(files.keySet()).containsExactly("inputs/context/outline_docs.json");
        JsonNode root = objectMapper
                .readTree(Objects.requireNonNull(files.get("inputs/context/outline_docs.json")))
                .path("documents");
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
                        doc("product", "roadmap", "Roadmap", "Q3 plans.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        JsonNode root = objectMapper
                .readTree(Objects.requireNonNull(files.get("inputs/context/outline_docs.json")))
                .path("documents");
        // "collection" stays the path/slug identity; "collection_name" is the additive human label.
        assertThat(root.get(0).get("collection").asString()).isEqualTo("engineering");
        assertThat(root.get(0).get("collection_name").asString()).isEqualTo("Engineering Docs");
        assertThat(root.get(1).has("collection_name")).isFalse();
    }

    @Test
    void mentorPathEmitsAuthorNameAndResolvedAccountId() throws Exception {
        when(projection.documentsForWorkspace(WORKSPACE_ID))
                .thenReturn(List.of(
                        authoredDoc(
                                "Engineering",
                                "onboarding-guide",
                                "Onboarding Guide",
                                "Welcome.",
                                "Ada Lovelace",
                                555L),
                        authoredDoc("Product", "roadmap", "Roadmap", "Q3 plans.", "Grace Hopper", null)));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        JsonNode root = objectMapper
                .readTree(Objects.requireNonNull(files.get("inputs/context/outline_docs.json")))
                .path("documents");
        JsonNode linked = root.get(0);
        assertThat(linked.get("author").asString()).isEqualTo("Ada Lovelace");
        assertThat(linked.get("author_account_id").asString()).isEqualTo("555");
        assertThat(linked.get("last_edited_by").asString()).isEqualTo("Ada Lovelace");
        assertThat(linked.get("last_edited_by_account_id").asString()).isEqualTo("555");
        // Unlinked author degrades to name-only: the account id key is simply absent, never null.
        JsonNode unlinked = root.get(1);
        assertThat(unlinked.get("author").asString()).isEqualTo("Grace Hopper");
        assertThat(unlinked.has("author_account_id")).isFalse();
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
                        doc("Product", "roadmap", "Roadmap", "Q3 plans.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        JsonNode root = objectMapper
                .readTree(Objects.requireNonNull(files.get("inputs/context/outline_docs.json")))
                .path("documents");
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
        assertThat(collaborators.get(0).get("account_id").asString()).isEqualTo("555");
        assertThat(collaborators.get(1).get("subject").asString()).isEqualTo("7cc9dd0e-user");
        assertThat(collaborators.get(1).has("name")).isFalse();
        assertThat(collaborators.get(1).has("account_id")).isFalse();

        JsonNode bare = root.get(1);
        assertThat(bare.has("created")).isFalse();
        assertThat(bare.has("last_updated")).isFalse();
        assertThat(bare.has("collaborators")).isFalse();
    }

    @Test
    void mentorPathGivesTheDocumentLinkAndSaysWhenTheBodyWasCut() throws Exception {
        String longBody = "a".repeat(DocumentCaptureSource.MENTOR_BODY_CHARS + 500);
        ProjectedDocument linked = new ProjectedDocument(
                "engineering",
                "release-guide",
                "Release guide",
                longBody,
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
                null,
                null,
                null,
                "https://docs.example/doc/release-guide-abc123");
        when(projection.documentsForWorkspace(WORKSPACE_ID))
                .thenReturn(List.of(linked, doc("Product", "roadmap", "Roadmap", "Q3 plans.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        JsonNode root = objectMapper
                .readTree(Objects.requireNonNull(files.get("inputs/context/outline_docs.json")))
                .path("documents");
        assertThat(root.path(0).path("url").asString()).isEqualTo("https://docs.example/doc/release-guide-abc123");
        assertThat(root.path(0).path("body").asString()).hasSize(DocumentCaptureSource.MENTOR_BODY_CHARS);
        assertThat(root.path(0).path("bodyTruncated").asBoolean()).isTrue();
        assertThat(root.path(0).path("bodyChars").asInt()).isEqualTo(longBody.length());
        assertThat(root.path(1).has("url")).as("no link was captured").isFalse();
        assertThat(root.path(1).has("bodyTruncated")).isFalse();
    }

    @Test
    void mentorPathWritesNothingWhenWorkspaceHasNoDocuments() {
        when(projection.documentsForWorkspace(WORKSPACE_ID)).thenReturn(List.of());

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(mentorRequest(), files);

        assertThat(files).isEmpty();
    }

    @Test
    void deriveQueryTextStripsUrlsAndOrJoinsDistinctLowercasedTerms() {
        String query = DocumentCaptureSource.deriveQueryText(
                "Fix Webhook retries",
                "See https://wiki.example.com/doc/setup-abc123 — the **webhook** retry backoff is wrong.");

        assertThat(query).isEqualTo("fix OR webhook OR retries OR see OR the OR retry OR backoff OR wrong");
    }

    @Test
    void deriveQueryTextIsEmptyWhenOnlyNoiseRemains() {
        assertThat(DocumentCaptureSource.deriveQueryText(null, "a b https://wiki.example.com/doc/x-1 !!"))
                .isEmpty();
        assertThat(DocumentCaptureSource.deriveQueryText(null, null)).isEmpty();
    }

    @Test
    void mentorPathRanksByTheCurrentUserMessageWhenOneExists() throws Exception {
        UUID messageId = UUID.randomUUID();
        MentorContextRequest request = new MentorContextRequest(WORKSPACE_ID, 7L, UUID.randomUUID(), messageId);
        when(mentorMessages.textForMessage(WORKSPACE_ID, 7L, messageId))
                .thenReturn(Optional.of("How do we deploy the webhook server?"));
        when(projection.searchDocuments(
                        eq(WORKSPACE_ID), anyString(), eq(DocumentCaptureSource.MAX_MENTOR_DOCUMENTS + 1)))
                .thenReturn(List.of(doc("Ops", "deploy-guide", "Deploy Guide", "Steps.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(request, files);

        JsonNode root = objectMapper
                .readTree(Objects.requireNonNull(files.get("inputs/context/outline_docs.json")))
                .path("documents");
        assertThat(root).hasSize(1);
        assertThat(root.get(0).get("slug").asString()).isEqualTo("deploy-guide");
        verify(projection, never()).documentsForWorkspace(anyLong());
    }

    @Test
    void mentorPathFallsBackToRecencyWhenTheQueryMatchesNothing() {
        UUID messageId = UUID.randomUUID();
        MentorContextRequest request = new MentorContextRequest(WORKSPACE_ID, 7L, UUID.randomUUID(), messageId);
        when(mentorMessages.textForMessage(WORKSPACE_ID, 7L, messageId)).thenReturn(Optional.of("something niche"));
        when(projection.searchDocuments(
                        eq(WORKSPACE_ID), anyString(), eq(DocumentCaptureSource.MAX_MENTOR_DOCUMENTS + 1)))
                .thenReturn(List.of());
        when(projection.documentsForWorkspace(WORKSPACE_ID))
                .thenReturn(List.of(doc("Engineering", "onboarding-guide", "Onboarding Guide", "Welcome.")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(request, files);

        assertThat(files.keySet()).containsExactly("inputs/context/outline_docs.json");
    }
}
