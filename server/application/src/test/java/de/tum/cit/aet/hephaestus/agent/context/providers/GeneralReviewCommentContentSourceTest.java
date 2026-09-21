package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class GeneralReviewCommentContentSourceTest extends BaseUnitTest {

    private static final String FILE_KEY = "inputs/context/general_comments.json";
    private static final Long PR_ID = 456L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private IssueCommentRepository issueCommentRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

    private GeneralReviewCommentContentSource provider;

    @BeforeEach
    void setUp() {
        lenient()
                .when(pullRequestRepository.existsByIdAndDeletedAtIsNull(PR_ID))
                .thenReturn(true);
        provider = new GeneralReviewCommentContentSource(objectMapper, issueCommentRepository, pullRequestRepository);
        lenient()
                .when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of());
    }

    private ObjectNode metadataWithPr() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("repository_id", 123L);
        metadata.put("pull_request_id", PR_ID);
        return metadata;
    }

    private ContextRequest.PracticeReviewRequest request(ObjectNode metadata) {
        AgentJob job = new AgentJob();
        job.setMetadata(metadata);
        Workspace workspace = new Workspace();
        workspace.setId(99L);
        job.setWorkspace(workspace);
        return new ContextRequest.PracticeReviewRequest(job);
    }

    private IssueComment comment(@Nullable String login, String body, @Nullable Instant createdAt) {
        IssueComment c = new IssueComment();
        c.setBody(body);
        if (login != null) {
            User u = new User();
            u.setLogin(login);
            c.setAuthor(u);
        }
        c.setCreatedAt(createdAt);
        return c;
    }

    @Test
    void contribute_noPrId_reportsCollectionError() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("repository_id", 123L);

        // Writing nothing would be misread downstream as "no review comments", a claim about the
        // work rather than the collection — so a missing pull_request_id must throw instead.
        assertThatThrownBy(() -> provider.contribute(request(metadata), new HashMap<>()))
                .isInstanceOf(EvidenceCollectionException.class)
                .hasMessage("Review-comment collection has no pull_request_id");
    }

    /**
     * A pull request nobody commented on still stages the file, holding an empty list — omitting it would leave
     * the model unable to tell "no comments" from "comments never staged".
     */
    @Test
    void contribute_noComments_stagesAnEmptyCommentList() throws Exception {
        var captured = provider.capture(request(metadataWithPr()), provider.sourceKinds());

        assertThat(captured.files()).containsKey(FILE_KEY);
        var out = objectMapper.readTree(captured.files().get(FILE_KEY));
        assertThat(out.propertyNames()).containsExactlyInAnyOrder("comments", "truncated");
        assertThat(out.get("comments")).isEmpty();
        // Present, and still EMPTY: the staged placeholder must not be read back as content.
        assertThat(captured.contentStates()).containsValue(SourceContentState.EMPTY);
    }

    @Test
    void shouldMarkACommentBotWhenTheAdapterStoredItsAuthorAsOne() throws Exception {
        IssueComment automated =
                comment("group_12_bot_9f3a", "Pipeline passed.", Instant.parse("2025-06-01T10:00:00Z"));
        automated.getAuthor().setType(User.Type.BOT);
        when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(
                        automated, comment("reviewer-a", "Looks right.", Instant.parse("2025-06-01T11:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode comments = objectMapper.readTree(files.get(FILE_KEY)).get("comments");
        // The adapter's classification, not a guess from the login; absent means a person.
        assertThat(comments.get(0).get("bot").asBoolean()).isTrue();
        assertThat(comments.get(1).has("bot")).isFalse();
    }

    @Test
    void contribute_generalDiscussion_emittedWithAuthorAndBody() throws Exception {
        when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(
                        comment(
                                "reviewer-a",
                                "I think this is always going to pass since confidence is always >= 0.3",
                                Instant.parse("2025-06-01T10:00:00Z")),
                        comment(
                                "author-x",
                                "added confidence scoring to address this",
                                Instant.parse("2025-06-01T11:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        assertThat(files).containsKey(FILE_KEY);
        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        assertThat(out.get("comments")).hasSize(2);
        JsonNode first = out.get("comments").get(0);
        assertThat(first.get("author").asString()).isEqualTo("reviewer-a");
        assertThat(first.get("body").asString()).contains("confidence");
        assertThat(first.get("createdAt").asString()).isEqualTo("2025-06-01T10:00:00Z");
    }

    @Test
    void contribute_excludesHephaestusOwnComments() throws Exception {
        when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(
                        comment(
                                "bot",
                                "<!-- hephaestus:practice-review:abc --> 2 issues to fix before merging",
                                Instant.parse("2025-06-01T09:00:00Z")),
                        comment(
                                "reviewer-b",
                                "Nit: split persistence out so each unit is testable",
                                Instant.parse("2025-06-01T10:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        assertThat(out.get("comments")).hasSize(1);
        assertThat(out.get("comments").get(0).get("author").asString()).isEqualTo("reviewer-b");
    }

    @Test
    void contribute_onlyHephaestusComments_stagesAnEmptyCommentList() {
        when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(comment(
                        "bot",
                        "<!-- hephaestus:practice-review:abc --> summary",
                        Instant.parse("2025-06-01T09:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        // Still staged holding nothing: reviewer-craft's empty-context abstention must come from the empty
        // list, not from the file being absent.
        assertThat(files).containsKey(FILE_KEY);
    }

    @Test
    void contribute_reportsRepositoryFailure() {
        when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        Map<String, byte[]> files = new HashMap<>();
        assertThatThrownBy(() -> provider.contribute(request(metadataWithPr()), files))
                .isInstanceOf(EvidenceCollectionException.class)
                .hasMessageContaining("General-review-comment collection failed");
        assertThat(files).doesNotContainKey(FILE_KEY);
    }

    @Test
    void contribute_overCap_keepsNewestAndFlagsTruncated() throws Exception {
        int total = GeneralReviewCommentContentSource.MAX_COMMENTS + 5;
        List<IssueComment> comments = new ArrayList<>();
        Instant base = Instant.parse("2025-06-01T00:00:00Z");
        for (int i = 0; i < total; i++) {
            comments.add(comment("reviewer-" + i, "comment-" + i, base.plusSeconds(i)));
        }
        java.util.Collections.reverse(comments);
        when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenReturn(comments);

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        assertThat(out.get("truncated").asBoolean()).isTrue();
        JsonNode bodies = out.get("comments");
        assertThat(bodies).hasSize(GeneralReviewCommentContentSource.MAX_COMMENTS);
        assertThat(bodies.get(0).get("body").asString()).isEqualTo("comment-5");
        assertThat(bodies.get(bodies.size() - 1).get("body").asString()).isEqualTo("comment-" + (total - 1));
        for (JsonNode c : bodies) {
            assertThat(c.get("body").asString()).isNotEqualTo("comment-0");
        }
    }

    @Test
    void contribute_underCap_flagsNotTruncated() throws Exception {
        when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(comment("reviewer-a", "looks good", Instant.parse("2025-06-01T10:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        assertThat(out.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void contribute_nullAuthor_omitsAuthorKey() throws Exception {
        when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(comment(null, "anonymous note", Instant.parse("2025-06-01T10:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode first =
                objectMapper.readTree(files.get(FILE_KEY)).get("comments").get(0);
        // login()==null → the author key is omitted entirely, never serialised as JSON null.
        assertThat(first.has("author")).isFalse();
        assertThat(first.get("body").asString()).isEqualTo("anonymous note");
    }

    @Test
    void contribute_nullCreatedAt_omitsCreatedAtKey() throws Exception {
        when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(comment("reviewer-a", "no timestamp", null)));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode first =
                objectMapper.readTree(files.get(FILE_KEY)).get("comments").get(0);
        assertThat(first.has("createdAt")).isFalse();
        assertThat(first.get("author").asString()).isEqualTo("reviewer-a");
    }

    @Test
    void contribute_blankBody_isSkipped() throws Exception {
        when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(
                        comment("reviewer-a", "   ", Instant.parse("2025-06-01T09:00:00Z")),
                        comment("reviewer-b", "real feedback", Instant.parse("2025-06-01T10:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        assertThat(out.get("comments")).hasSize(1);
        assertThat(out.get("comments").get(0).get("author").asString()).isEqualTo("reviewer-b");
    }

    @Test
    void contribute_hyphenFormDiffNoteMarker_isNotExcluded() throws Exception {
        // The hyphen-form diff-note marker is NOT matched by HEPHAESTUS_MARKER (colon form only) — correctly
        // so, since diff notes are stored as PullRequestReviewComment, which this IssueComment-only provider
        // never sees.
        when(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(comment(
                        "reviewer-a",
                        "<!-- hephaestus-diff-note --> human follow-up",
                        Instant.parse("2025-06-01T10:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        assertThat(out.get("comments")).hasSize(1);
    }

    @Test
    void required_isFalse_bestEffort() {
        assertThat(provider.required()).isFalse();
    }

    @Test
    void shouldRefuseUnavailableDiscussionAndAllowItWhenTheParentReturns() {
        when(pullRequestRepository.existsByIdAndDeletedAtIsNull(PR_ID)).thenReturn(false);
        var captured = provider.capture(request(metadataWithPr()), provider.sourceKinds());
        assertThat(captured.files()).isEmpty();
        assertThat(captured.stateOverrides())
                .containsValue(new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
        verifyNoInteractions(issueCommentRepository);
        when(pullRequestRepository.existsByIdAndDeletedAtIsNull(PR_ID)).thenReturn(true);
        var restored = provider.capture(request(metadataWithPr()), provider.sourceKinds());
        assertThat(restored.stateOverrides()).isEmpty();
        assertThat(restored.files()).isNotEmpty();
    }
}
