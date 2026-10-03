package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        provider = new OutlineDocumentContentSource(projection, objectMapper, chatMessageRepository);
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
                null,
                null,
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
                null);
    }

    @Test
    void supportsMentorOnly() {
        assertThat(provider.supports(mentorRequest())).isTrue();
        assertThat(provider.supports(prRequest("no links"))).isFalse();
        assertThat(provider.supports(issueRequest("no links"))).isFalse();
        assertThat(provider.supports(new ContextRequest.ConversationReviewRequest(new AgentJob())))
                .isFalse();
    }

    @Test
    void isBestEffort() {
        assertThat(provider.required()).isFalse();
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
