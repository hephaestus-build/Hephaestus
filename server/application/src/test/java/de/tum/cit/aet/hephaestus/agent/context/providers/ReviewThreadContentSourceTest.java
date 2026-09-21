package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceLimits;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewThreadContentSourceTest extends BaseUnitTest {

    private static final String FILE_KEY = "inputs/context/review_threads.json";
    private static final Long PR_ID = 456L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private PullRequestReviewThreadRepository threadRepository;

    @Mock
    private PullRequestReviewRepository reviewRepository;

    private ReviewThreadContentSource provider;

    @BeforeEach
    void setUp() {
        provider =
                new ReviewThreadContentSource(objectMapper, pullRequestRepository, threadRepository, reviewRepository);
        lenient().when(pullRequestRepository.findById(any())).thenReturn(Optional.of(new PullRequest()));
        lenient()
                .when(threadRepository.findRecentIdsByPullRequestId(any(), any()))
                .thenReturn(List.of());
        lenient()
                .when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
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

    private User user(String login) {
        User u = new User();
        u.setLogin(login);
        return u;
    }

    private PullRequestReview review(PullRequestReview.State state, String author, Instant submittedAt) {
        PullRequestReview r = new PullRequestReview();
        r.setState(state);
        r.setAuthor(user(author));
        r.setSubmittedAt(submittedAt);
        return r;
    }

    private PullRequestReviewThread thread(
            PullRequestReviewThread.State state, String path, Integer line, @Nullable User resolvedBy) {
        PullRequestReviewThread t = new PullRequestReviewThread();
        t.setState(state);
        t.setPath(path);
        t.setLine(line);
        t.setResolvedBy(resolvedBy);
        return t;
    }

    private PullRequestReviewComment comment(@Nullable String login, String body) {
        var c = new PullRequestReviewComment();
        c.setBody(body);
        if (login != null) {
            c.setAuthor(user(login));
        }
        return c;
    }

    private void stubThreads(List<PullRequestReviewThread> threads) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < threads.size(); i++) {
            long id = i + 1L;
            ReflectionTestUtils.setField(threads.get(i), "id", id);
            ids.add(id);
        }
        List<Long> boundedIds = ids.subList(0, Math.min(ids.size(), ReviewThreadContentSource.MAX_THREADS + 1));
        when(threadRepository.findRecentIdsByPullRequestId(any(), any())).thenReturn(boundedIds);
        when(threadRepository.findAllByIdWithResolvedBy(any())).thenAnswer(invocation -> {
            List<Long> requested = invocation.getArgument(0);
            return threads.stream()
                    .filter(thread -> requested.contains(thread.getId()))
                    .toList();
        });
    }

    @Test
    void contribute_noPrId_reportsCollectionError() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("repository_id", 123L);

        // "No unresolved threads" is a finding about the author; a missing key is a broken job.
        assertThatThrownBy(() -> provider.contribute(request(metadata), new HashMap<>()))
                .isInstanceOf(EvidenceCollectionException.class)
                .hasMessage("Review-thread collection has no pull_request_id");
    }

    @Test
    void contribute_noThreadsNoReviews_writesCanonicalEmptyState() throws Exception {
        var captured = provider.capture(request(metadataWithPr()), provider.sourceKinds());

        JsonNode out = objectMapper.readTree(captured.files().get(FILE_KEY));
        // Threads, decisions and the bound: the merge state and any tally are the review's to read from
        // the pull request itself.
        assertThat(out.propertyNames()).containsExactlyInAnyOrder("threads", "reviewDecisions", "truncated");
        assertThat(out.get("threads")).isEmpty();
        assertThat(out.get("reviewDecisions")).isEmpty();
        assertThat(out.get("truncated").asBoolean()).isFalse();
        assertThat(captured.contentStates()).containsValue(SourceContentState.EMPTY);
        assertThat(captured.completeness()).containsValue(SourceCompleteness.COMPLETE);
    }

    @Test
    void contribute_changesRequestedReview_emittedAsRawDecisionRow() throws Exception {
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(review(
                        PullRequestReview.State.CHANGES_REQUESTED,
                        "reviewer-a",
                        Instant.parse("2025-06-01T10:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        assertThat(files).containsKey(FILE_KEY);
        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        JsonNode decision = out.get("reviewDecisions").get(0);
        assertThat(decision.propertyNames()).containsExactlyInAnyOrder("state", "author", "submittedAt");
        assertThat(decision.get("state").asString()).isEqualTo("CHANGES_REQUESTED");
        assertThat(decision.get("author").asString()).isEqualTo("reviewer-a");
        // submittedAt is emitted raw so the agent (not this connector) can compute supersession.
        assertThat(decision.get("submittedAt").asString()).isEqualTo("2025-06-01T10:00:00Z");
    }

    @Test
    void contribute_changesRequestedThenApproved_emitsBothRowsWithTimestamps() throws Exception {
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(
                        review(
                                PullRequestReview.State.CHANGES_REQUESTED,
                                "reviewer-a",
                                Instant.parse("2025-06-01T10:00:00Z")),
                        review(PullRequestReview.State.APPROVED, "reviewer-a", Instant.parse("2025-06-01T12:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        // Both decisions are emitted losslessly with timestamps, oldest first like the comment files;
        // supersession is the agent's to compute.
        JsonNode decisions = out.get("reviewDecisions");
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).get("submittedAt").asString()).isEqualTo("2025-06-01T10:00:00Z");
        assertThat(decisions.get(1).get("state").asString()).isEqualTo("APPROVED");
        assertThat(decisions.get(1).get("submittedAt").asString()).isEqualTo("2025-06-01T12:00:00Z");
        // ELT contract: this connector must NOT pre-compute the supersession observation — no derived aggregate,
        // no per-row "effective"/"superseded" flag. Raw rows only; the agent judges.
        assertThat(out.has("changesRequestedUnaddressed")).isFalse();
        assertThat(decisions.get(0).has("superseded")).isFalse();
    }

    @Test
    void contribute_moreDecisionsThanCap_keepsLatestApprove() throws Exception {
        // The repository returns decisions newest-first (ORDER BY submittedAt DESC, id DESC). With more
        // than MAX_DECISIONS rows, the consumer's truncation keeps the NEWEST — so a final superseding APPROVE
        // must survive, not be dropped behind older CHANGES_REQUESTED (which would fabricate a false
        // "merged past unresolved request-changes" finding).
        List<PullRequestReview> newestFirst = new ArrayList<>();
        // The latest decision: an APPROVE at the most recent timestamp.
        newestFirst.add(review(PullRequestReview.State.APPROVED, "reviewer-a", Instant.parse("2025-06-30T23:59:00Z")));
        // Followed by MAX_DECISIONS + 5 older CHANGES_REQUESTED rows (descending timestamps).
        for (int i = 0; i < ReviewThreadContentSource.MAX_DECISIONS + 5; i++) {
            newestFirst.add(review(
                    PullRequestReview.State.CHANGES_REQUESTED,
                    "reviewer-a",
                    Instant.parse("2025-06-01T10:00:00Z").minusSeconds(i)));
        }
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(newestFirst);

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        JsonNode decisions = out.get("reviewDecisions");
        assertThat(decisions).hasSize(ReviewThreadContentSource.MAX_DECISIONS);
        // The latest APPROVE is retained, and listed last because the file runs oldest first.
        JsonNode last = decisions.get(decisions.size() - 1);
        assertThat(last.get("state").asString()).isEqualTo("APPROVED");
        assertThat(last.get("submittedAt").asString()).isEqualTo("2025-06-30T23:59:00Z");
        assertThat(out.get("truncated").asBoolean()).isTrue();
    }

    @Test
    void shouldMarkADecisionBotWhenTheAdapterStoredItsAuthorAsOne() throws Exception {
        User token = user("project_7_bot_a1b2");
        token.setType(User.Type.BOT);
        PullRequestReview automated = new PullRequestReview();
        automated.setState(PullRequestReview.State.APPROVED);
        automated.setAuthor(token);
        automated.setSubmittedAt(Instant.parse("2025-06-01T10:00:00Z"));
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(
                        review(PullRequestReview.State.APPROVED, "reviewer-a", Instant.parse("2025-06-01T11:00:00Z")),
                        automated));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode decisions = objectMapper.readTree(files.get(FILE_KEY)).get("reviewDecisions");
        assertThat(decisions.get(0).get("bot").asBoolean()).isTrue();
        assertThat(decisions.get(1).has("bot")).isFalse();
    }

    @Test
    void shouldBoundDecisionsByTheMemoryLimitSoBusyMergeRequestsKeepTheirApprovals() {
        // GitLab's sync makes one COMMENTED review per discussion and author; the old window of thirty
        // let those push an approval out of the file.
        assertThat(ReviewThreadContentSource.MAX_DECISIONS).isEqualTo(EvidenceLimits.MAX_ITEMS_PER_SOURCE);
    }

    @Test
    void shouldLeaveOutACommentedReviewWithNoBodyAndKeepOneThatSaysSomething() throws Exception {
        PullRequestReview batch =
                review(PullRequestReview.State.COMMENTED, "reviewer-a", Instant.parse("2025-06-01T10:00:00Z"));
        batch.setBody("");
        PullRequestReview synthetic =
                review(PullRequestReview.State.COMMENTED, "reviewer-b", Instant.parse("2025-06-01T11:00:00Z"));
        PullRequestReview worded =
                review(PullRequestReview.State.COMMENTED, "reviewer-c", Instant.parse("2025-06-01T12:00:00Z"));
        worded.setBody("Looks right overall; two questions inline.");
        PullRequestReview approval =
                review(PullRequestReview.State.APPROVED, "reviewer-a", Instant.parse("2025-06-01T13:00:00Z"));
        approval.setBody("LGTM, thanks for the tests.");
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(approval, worded, synthetic, batch));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode decisions = objectMapper.readTree(files.get(FILE_KEY)).get("reviewDecisions");
        // The inline batch and the synthetic per-discussion row decided nothing and their comments are in
        // comments.json; the worded comment and the approval stay, with what the reviewer wrote.
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).get("author").asString()).isEqualTo("reviewer-c");
        assertThat(decisions.get(0).get("body").asString()).isEqualTo("Looks right overall; two questions inline.");
        assertThat(decisions.get(1).get("state").asString()).isEqualTo("APPROVED");
        assertThat(decisions.get(1).get("body").asString()).isEqualTo("LGTM, thanks for the tests.");
    }

    @Test
    void contribute_hephaestusOwnThread_excludedFromThreads() throws Exception {
        // A thread whose comments are Hephaestus's own posted note (marker-bearing) must NOT count as a
        // reviewer thread — the rootComment FK is null in sync, so the comment set is the signal.
        PullRequestReviewThread botThread = thread(PullRequestReviewThread.State.UNRESOLVED, "src/Foo.swift", 10, null);
        botThread.setComments(Set.of(comment(null, "Add a unit test for encodeDepth.\n<!-- hephaestus-diff-note -->")));

        PullRequestReviewThread humanThread =
                thread(PullRequestReviewThread.State.UNRESOLVED, "src/Bar.swift", 5, null);
        humanThread.setComments(Set.of(comment("reviewer-a", "This force-unwrap will crash — can we guard it?")));

        stubThreads(List.of(botThread, humanThread));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        // Only the human reviewer thread is listed; the Hephaestus note is dropped entirely. Assert by
        // CONTENT, not position, so the test does not encode an incidental ordering over the comment Set.
        assertThat(out.get("threads")).hasSize(1);
        List<String> paths = new ArrayList<>();
        out.get("threads").forEach(node -> paths.add(node.get("path").asString()));
        assertThat(paths).containsExactly("src/Bar.swift").doesNotContain("src/Foo.swift");
    }

    @Test
    void contribute_unresolvedAndResolvedThreads_emittedAsRawRows() throws Exception {
        stubThreads(List.of(
                thread(PullRequestReviewThread.State.UNRESOLVED, "src/Foo.swift", 12, null),
                thread(PullRequestReviewThread.State.RESOLVED, "src/Bar.swift", 5, user("reviewer-b"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        assertThat(out.get("threads")).hasSize(2);
        JsonNode unresolved = out.get("threads").get(0);
        assertThat(unresolved.propertyNames()).containsExactlyInAnyOrder("id", "path", "line", "state");
        // The stored id, which a comment in comments.json names as its `thread`.
        assertThat(unresolved.get("id").asLong()).isEqualTo(1L);
        assertThat(unresolved.get("path").asString()).isEqualTo("src/Foo.swift");
        assertThat(unresolved.get("line").asInt()).isEqualTo(12);
        assertThat(unresolved.get("state").asString()).isEqualTo("UNRESOLVED");
        JsonNode resolved = out.get("threads").get(1);
        assertThat(resolved.get("state").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolvedBy").asString()).isEqualTo("reviewer-b");
        // How many are still open is the review's to count from the rows.
        assertThat(out.has("unresolvedCount")).isFalse();
    }

    @Test
    void shouldDateAThreadWhenTheProviderRecordedWhenItWasOpened() throws Exception {
        PullRequestReviewThread dated = thread(PullRequestReviewThread.State.UNRESOLVED, "src/Foo.swift", 12, null);
        dated.setCreatedAt(Instant.parse("2025-06-01T10:00:00Z"));
        stubThreads(List.of(dated, thread(PullRequestReviewThread.State.UNRESOLVED, "src/Bar.swift", 5, null)));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode threads = objectMapper.readTree(files.get(FILE_KEY)).get("threads");
        assertThat(threads.get(0).get("createdAt").asString()).isEqualTo("2025-06-01T10:00:00Z");
        assertThat(threads.get(1).has("createdAt")).isFalse();
    }

    @Test
    void contribute_pendingReview_excludedFromDecisions() throws Exception {
        // A PENDING review is an unsubmitted draft ("only visible to the author") with no submittedAt — it
        // must never reach the agent as a real decision, else it fabricates an outstanding-CHANGES signal.
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(
                        review(
                                PullRequestReview.State.PENDING,
                                "drafting-reviewer",
                                Instant.parse("2025-05-01T12:00:00Z")),
                        review(PullRequestReview.State.APPROVED, "reviewer-a", Instant.parse("2025-06-01T12:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        JsonNode decisions = out.get("reviewDecisions");
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).get("state").asString()).isEqualTo("APPROVED");
    }

    @Test
    void contribute_unknownReview_excludedFromDecisions() throws Exception {
        // UNKNOWN is the unmapped forward-compat fallback — not a genuine submitted decision.
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(
                        review(PullRequestReview.State.UNKNOWN, "reviewer-x", Instant.parse("2025-05-01T12:00:00Z"))));

        var captured = provider.capture(request(metadataWithPr()), provider.sourceKinds());

        JsonNode out = objectMapper.readTree(captured.files().get(FILE_KEY));
        assertThat(out.get("reviewDecisions")).isEmpty();
        assertThat(captured.contentStates()).containsValue(SourceContentState.EMPTY);
    }

    @Test
    void contribute_moreThreadsThanCap_marksTheBoundedCaptureTruncated() throws Exception {
        List<PullRequestReviewThread> many = new ArrayList<>();
        int total = ReviewThreadContentSource.MAX_THREADS + 7;
        for (int i = 0; i < total; i++) {
            many.add(thread(PullRequestReviewThread.State.UNRESOLVED, "src/File" + i + ".swift", i, null));
        }
        stubThreads(many);

        var captured = provider.capture(request(metadataWithPr()), provider.sourceKinds());

        JsonNode out = objectMapper.readTree(captured.files().get(FILE_KEY));
        assertThat(out.get("threads")).hasSize(ReviewThreadContentSource.MAX_THREADS);
        assertThat(out.get("truncated").asBoolean()).isTrue();
        assertThat(captured.completeness()).containsValue(SourceCompleteness.PARTIAL);
    }

    @Test
    void contribute_reportsCollectionError_onRepositoryFailure() {
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        Map<String, byte[]> files = new HashMap<>();
        assertThatExceptionOfType(EvidenceCollectionException.class)
                .isThrownBy(() -> provider.contribute(request(metadataWithPr()), files));
        assertThat(files).doesNotContainKey(FILE_KEY);
    }

    @Test
    void required_isFalse_bestEffort() {
        assertThat(provider.required()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldReportUnavailableThenAllowCaptureWhenArtifactReturns(boolean tombstoned) {
        var artifact = new PullRequest();
        artifact.setDeletedAt(Instant.parse("2026-09-05T00:00:00Z"));
        when(pullRequestRepository.findById(PR_ID)).thenReturn(tombstoned ? Optional.of(artifact) : Optional.empty());
        for (var kind : provider.sourceKinds()) {
            var captured = provider.capture(request(metadataWithPr()), Set.of(kind));
            assertThat(captured.files()).isEmpty();
            assertThat(captured.completeness()).isEmpty();
            assertThat(captured.contentStates()).isEmpty();
            assertThat(captured.stateOverrides())
                    .containsExactlyEntriesOf(
                            Map.of(kind, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND)));
        }
        verifyNoInteractions(threadRepository, reviewRepository);
        artifact.setDeletedAt(null);
        when(pullRequestRepository.findById(PR_ID)).thenReturn(Optional.of(artifact));
        var restored = provider.capture(request(metadataWithPr()), provider.sourceKinds());
        assertThat(restored.stateOverrides()).isEmpty();
        assertThat(restored.files()).isNotEmpty();
    }
}
