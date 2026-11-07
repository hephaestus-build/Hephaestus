package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndexBuilder;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewThreadContentSourceTest extends BaseUnitTest {

    private static final String FILE_KEY = "context/review_threads.json";
    private static final Long PR_ID = 456L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private PullRequestReviewThreadRepository threadRepository;

    @Mock
    private PullRequestReviewRepository reviewRepository;

    @Mock
    private DeliveredPullRequestCommentLookup deliveredComments;

    private ReviewThreadContentSource provider;

    @BeforeEach
    void setUp() {
        lenient().when(pullRequestRepository.isDiscussionReadWhole(PR_ID)).thenReturn(true);
        provider = new ReviewThreadContentSource(
                objectMapper, pullRequestRepository, threadRepository, reviewRepository, deliveredComments);
        lenient()
                .when(deliveredComments.findForPullRequest(anyLong(), anyLong(), any()))
                .thenReturn(new DeliveredPullRequestCommentLookup.CommentIds(Set.of(), Set.of(), Set.of()));
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

    private ScmCaptureContext request(ObjectNode metadata) {
        return ScmCaptureFixtures.context(metadata, 1L, false);
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
        List<Long> boundedIds = ids;
        when(threadRepository.findRecentIdsByPullRequestId(any(), any())).thenReturn(boundedIds);
        when(threadRepository.findAllByIdWithResolvedBy(any())).thenAnswer(invocation -> {
            List<Long> requested = invocation.getArgument(0);
            return threads.stream()
                    .filter(thread -> requested.contains(thread.getId()))
                    .toList();
        });
    }

    @Test
    void capturedReviewerIdentityRequiresBothOriginalReviewAndSubject() throws Exception {
        var target = review(PullRequestReview.State.COMMENTED, "reviewer", Instant.parse("2025-06-01T12:00:00Z"));
        ReflectionTestUtils.setField(target, "id", 31L);
        var author = Objects.requireNonNull(target.getAuthor());
        ReflectionTestUtils.setField(author, "id", 32L);
        author.setNativeId(33L);
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(target));
        var metadata = metadataWithPr();
        metadata.put("subject_role", "REVIEWER");
        metadata.put("review_id", 31L);
        metadata.put("about_user_id", 32L);
        JsonNode matched = objectMapper.readTree(provider.capture(request(metadata), provider.sourceKinds())
                .files()
                .get(FILE_KEY));
        assertThat(matched.path("reviewRecipient").path("authorId").asLong()).isEqualTo(33L);
        assertThat(matched.path("reviewRecipient").path("author").asString()).isEqualTo("reviewer");
        assertThat(matched.path("reviewDecisions")).isEmpty();
        metadata.put("about_user_id", 99L);
        JsonNode wrongSubject = objectMapper.readTree(provider.capture(request(metadata), provider.sourceKinds())
                .files()
                .get(FILE_KEY));
        assertThat(wrongSubject.has("reviewRecipient")).isFalse();
        metadata.put("about_user_id", 32L);
        metadata.put("review_id", 99L);
        JsonNode wrongReview = objectMapper.readTree(provider.capture(request(metadata), provider.sourceKinds())
                .files()
                .get(FILE_KEY));
        assertThat(wrongReview.has("reviewRecipient")).isFalse();
        metadata.put("review_id", 31L);
        metadata.remove("subject_role");
        JsonNode authorReview = objectMapper.readTree(provider.capture(request(metadata), provider.sourceKinds())
                .files()
                .get(FILE_KEY));
        assertThat(authorReview.has("reviewRecipient")).isFalse();
    }

    @Test
    void shouldReportAPartialCaptureWhenTheDiscussionWasNotReadWhole() {
        when(pullRequestRepository.isDiscussionReadWhole(PR_ID)).thenReturn(false);

        var contribution = provider.capture(request(metadataWithPr()), Set.of(ReviewThreadContentSource.KIND));

        assertThat(contribution.completeness())
                .containsEntry(ReviewThreadContentSource.KIND, SourceCompleteness.PARTIAL);
        assertThat(contribution.captureLimitations().get(ReviewThreadContentSource.KIND))
                .containsExactly(ScmCapturedWork.DISCUSSION_NOT_READ_WHOLE);
    }

    @Test
    void shouldReportAWholeCaptureAfterAWholeProviderRead() {
        when(pullRequestRepository.isDiscussionReadWhole(PR_ID)).thenReturn(true);

        var contribution = provider.capture(request(metadataWithPr()), Set.of(ReviewThreadContentSource.KIND));

        assertThat(contribution.completeness())
                .containsEntry(ReviewThreadContentSource.KIND, SourceCompleteness.COMPLETE);
    }

    @Test
    void shouldNameTheReviewerAndTheirDecisionForEachBundledReviewerPractice() {
        String words = "Please cover the empty list before merging.";
        var target =
                review(PullRequestReview.State.CHANGES_REQUESTED, "reviewer", Instant.parse("2025-06-01T12:00:00Z"));
        target.setBody(words);
        ReflectionTestUtils.setField(target, "id", 31L);
        var author = Objects.requireNonNull(target.getAuthor());
        ReflectionTestUtils.setField(author, "id", 32L);
        author.setNativeId(33L);
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(target));
        var metadata = metadataWithPr();
        metadata.put("subject_role", "REVIEWER");
        metadata.put("review_id", 31L);
        metadata.put("about_user_id", 32L);
        var reviewItem = request(metadata);

        var reviewerPractices = ScmCaptureFixtures.bundledPractices().values().stream()
                .filter(practice -> practice.definition().about() == AboutRole.REVIEWER)
                .toList();
        var mapper = JsonMapper.builder().build();
        var exporter = new JobFolderIndexBuilder(
                new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC()), mapper, Clock.systemUTC());
        assertThat(provider.sourceKinds()).containsExactly(ReviewThreadContentSource.KIND);

        assertThat(reviewerPractices).hasSize(3).allSatisfy(practice -> {
            assertThat(practice.kind()).isEqualTo(reviewItem.pinned().item().kind());
            var reads = practice.definition().reads().sources();
            assertThat(reads).anySatisfy(read -> {
                assertThat(read.source()).isEqualTo(ReviewThreadContentSource.KIND);
                assertThat(read.need()).isEqualTo(ReadNeed.COMPLETE);
            });
            var selected = reads.stream().map(read -> read.source()).collect(Collectors.toSet());
            JsonNode threads = objectMapper.readTree(
                    provider.capture(reviewItem, selected).files().get(FILE_KEY));
            assertThat(threads.path("reviewRecipient").path("author").asString())
                    .isEqualTo("reviewer");
            assertThat(threads.path("reviewRecipient").path("authorId").asLong())
                    .isEqualTo(33L);
            assertThat(threads.path("reviewDecisions")).singleElement().satisfies(decision -> {
                assertThat(decision.path("author").asString()).isEqualTo("reviewer");
                assertThat(decision.path("state").asString()).isEqualTo("CHANGES_REQUESTED");
                assertThat(decision.path("body").asString()).isEqualTo(target.getBody());
            });

            try (var prepared = exporter.build(
                    reviewItem.pinned().item().kind(),
                    Map.of(
                            ReviewThreadContentSource.KIND,
                            provider.capture(reviewItem, Set.of(ReviewThreadContentSource.KIND))))) {
                JsonNode exported =
                        mapper.readTree(Objects.requireNonNull(prepared.files().get(FILE_KEY)));
                assertThat(exported.path("reviewRecipient").path("author").asString())
                        .isEqualTo("reviewer");
                assertThat(exported.path("reviewRecipient").path("authorId").asLong())
                        .isEqualTo(33L);
                assertThat(exported.path("reviewDecisions")).singleElement().satisfies(decision -> {
                    assertThat(decision.path("author").asString()).isEqualTo("reviewer");
                    assertThat(decision.path("state").asString()).isEqualTo("CHANGES_REQUESTED");
                });
                assertExported(
                        mapper,
                        prepared,
                        reviewItem.pinned().item().kind(),
                        ReviewThreadContentSource.KIND,
                        FILE_KEY,
                        FILE_KEY + "#/reviewDecisions/0/body",
                        words);
            }
        });
    }

    /** Reads the exported manifest back and binds one source's file and quoted words to their staged bytes. */
    private static void assertExported(
            JsonMapper mapper,
            PreparedEvidence prepared,
            ItemKind item,
            SourceKind source,
            String file,
            String citation,
            String words) {
        JsonNode manifest =
                mapper.readTree(Objects.requireNonNull(prepared.files().get(SandboxLayout.MANIFEST_PATH)));
        assertThat(manifest.path("itemKind").asString()).isEqualTo(item.value());
        assertThat(manifest.path("sources")).singleElement().satisfies(captured -> {
            assertThat(captured.path("kind").asString()).isEqualTo(source.value());
            assertThat(captured.path("state").path("availability").asString()).isEqualTo("AVAILABLE");
            assertThat(captured.path("state").path("completeness").asString()).isEqualTo("COMPLETE");
            assertThat(captured.path("state").path("content").asString()).isEqualTo("NON_EMPTY");
        });
        byte[] exported = Objects.requireNonNull(prepared.files().get(file));
        assertThat(manifest.path("files")).anySatisfy(entry -> {
            assertThat(entry.path("kind").asString()).isEqualTo(source.value());
            assertThat(entry.path("file").path("path").asString()).isEqualTo(file);
            assertThat(entry.path("file").path("sha256").asString()).isEqualTo(ProvenanceDigest.sha256Hex(exported));
        });
        assertThat(manifest.path("citableTexts")).anySatisfy(text -> {
            assertThat(text.path("source").asString()).isEqualTo(source.value());
            assertThat(text.path("path").asString()).isEqualTo(citation);
            String staged = text.path("stagedPath").asString();
            assertThat(prepared.files()).containsKey(staged);
            byte[] quoted = Objects.requireNonNull(prepared.files().get(staged));
            assertThat(new String(quoted, StandardCharsets.UTF_8)).isEqualTo(words);
            assertThat(text.path("sha256").asString()).isEqualTo(ProvenanceDigest.sha256Hex(quoted));
        });
    }

    @Test
    void contribute_noThreadsNoReviews_writesCanonicalEmptyState() throws Exception {
        var captured = provider.capture(request(metadataWithPr()), provider.sourceKinds());

        JsonNode out = objectMapper.readTree(captured.files().get(FILE_KEY));
        assertThat(out.propertyNames())
                .containsExactlyInAnyOrder("threads", "reviewDecisions", "decisionHistoryComplete", "truncated");
        assertThat(out.get("decisionHistoryComplete").asBoolean())
                .as("current decisions are no history, however whole the list is")
                .isFalse();
        assertThat(out.get("threads")).isEmpty();
        assertThat(out.get("reviewDecisions")).isEmpty();
        assertThat(out.get("truncated").asBoolean()).isFalse();
        assertThat(captured.contentStates()).containsValue(SourceContentState.EMPTY);
        assertThat(captured.completeness()).containsValue(SourceCompleteness.COMPLETE);
    }

    @Test
    void shouldLeaveHephaestusOwnPostedReviewOutOfTheDecisions() throws Exception {
        PullRequestReview posted =
                review(PullRequestReview.State.COMMENTED, "hephaestus", Instant.parse("2025-06-01T10:00:00Z"));
        posted.setNativeId(87L);
        posted.setBody("Edited feedback without a marker.");
        when(deliveredComments.findForPullRequest(1L, PR_ID, ScmItemEvents.PULL_REQUEST))
                .thenReturn(new DeliveredPullRequestCommentLookup.CommentIds(Set.of(), Set.of(), Set.of(87L)));
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(posted));

        var files = provider.capture(request(metadataWithPr()), provider.sourceKinds())
                .files();

        assertThat(objectMapper.readTree(files.get(FILE_KEY)).path("reviewDecisions"))
                .isEmpty();
    }

    @Test
    void shouldKeepAMarkerQuotedInAHumanReviewWhenNoDeliveryReceiptNamesIt() throws Exception {
        var human = review(PullRequestReview.State.COMMENTED, "reviewer", Instant.parse("2025-06-01T10:00:00Z"));
        human.setNativeId(89L);
        human.setBody("The comment contains <!-- hephaestus-diff-note --> as an example.");
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(human));
        var captured = provider.capture(request(metadataWithPr()), provider.sourceKinds());
        var decisions = objectMapper.readTree(captured.files().get(FILE_KEY)).path("reviewDecisions");
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).path("body").asString()).isEqualTo(human.getBody());
        assertThat(decisions.get(0).path("quotesHephaestusMarker").asBoolean()).isTrue();
    }

    @Test
    void contribute_changesRequestedReview_emittedAsRawDecisionRow() throws Exception {
        PullRequestReview recorded =
                review(PullRequestReview.State.CHANGES_REQUESTED, "reviewer-a", Instant.parse("2025-06-01T10:00:00Z"));
        recorded.setNativeId(71L);
        Objects.requireNonNull(recorded.getAuthor()).setNativeId(72L);
        recorded.setCommitId("original-reviewed-head");
        recorded.setUpdatedAt(Instant.parse("2025-06-01T10:30:00Z"));
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(recorded));

        Map<String, byte[]> files = new HashMap<>();
        files.putAll(provider.capture(request(metadataWithPr()), provider.sourceKinds())
                .files());

        assertThat(files).containsKey(FILE_KEY);
        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        JsonNode decision = out.get("reviewDecisions").get(0);
        assertThat(decision.propertyNames())
                .containsExactlyInAnyOrder(
                        "state", "author", "submittedAt", "nativeId", "authorId", "commitId", "updatedAt");
        assertThat(decision.path("nativeId").asLong()).isEqualTo(71L);
        assertThat(decision.path("authorId").asLong()).isEqualTo(72L);
        assertThat(decision.path("commitId").asString()).isEqualTo("original-reviewed-head");
        assertThat(decision.path("updatedAt").asString()).isEqualTo("2025-06-01T10:30:00Z");
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
        files.putAll(provider.capture(request(metadataWithPr()), provider.sourceKinds())
                .files());

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));
        JsonNode decisions = out.get("reviewDecisions");
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).get("submittedAt").asString()).isEqualTo("2025-06-01T10:00:00Z");
        assertThat(decisions.get(1).get("state").asString()).isEqualTo("APPROVED");
        assertThat(decisions.get(1).get("submittedAt").asString()).isEqualTo("2025-06-01T12:00:00Z");
        // ELT contract: this connector must NOT pre-compute the supersession result — no derived
        // aggregate,
        // no per-row "effective"/"superseded" flag. Raw rows only; the agent judges.
        assertThat(out.has("changesRequestedUnaddressed")).isFalse();
        assertThat(decisions.get(0).has("superseded")).isFalse();
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
        files.putAll(provider.capture(request(metadataWithPr()), provider.sourceKinds())
                .files());

        JsonNode decisions = objectMapper.readTree(files.get(FILE_KEY)).get("reviewDecisions");
        assertThat(decisions.get(0).get("bot").asBoolean()).isTrue();
        assertThat(decisions.get(1).has("bot")).isFalse();
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
        files.putAll(provider.capture(request(metadataWithPr()), provider.sourceKinds())
                .files());

        JsonNode decisions = objectMapper.readTree(files.get(FILE_KEY)).get("reviewDecisions");
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).get("author").asString()).isEqualTo("reviewer-c");
        assertThat(decisions.get(0).get("body").asString()).isEqualTo("Looks right overall; two questions inline.");
        assertThat(decisions.get(1).get("state").asString()).isEqualTo("APPROVED");
        assertThat(decisions.get(1).get("body").asString()).isEqualTo("LGTM, thanks for the tests.");
    }

    @Test
    void contribute_hephaestusOwnThread_excludedFromThreads() throws Exception {
        // The provider mirror can lack the root relation; the receipt still identifies the delivered note.
        PullRequestReviewThread botThread = thread(PullRequestReviewThread.State.UNRESOLVED, "src/Foo.swift", 10, null);
        var posted = comment(null, "Edited note without a marker.");
        posted.setNativeId(88L);
        botThread.setComments(Set.of(posted));
        when(deliveredComments.findForPullRequest(1L, PR_ID, ScmItemEvents.PULL_REQUEST))
                .thenReturn(new DeliveredPullRequestCommentLookup.CommentIds(Set.of(), Set.of(88L), Set.of()));

        PullRequestReviewThread humanThread =
                thread(PullRequestReviewThread.State.UNRESOLVED, "src/Bar.swift", 5, null);
        humanThread.setComments(Set.of(comment("reviewer-a", "Quoted <!-- hephaestus-diff-note --> in my review.")));

        stubThreads(List.of(botThread, humanThread));

        Map<String, byte[]> files = new HashMap<>();
        files.putAll(provider.capture(request(metadataWithPr()), provider.sourceKinds())
                .files());

        JsonNode out = objectMapper.readTree(files.get(FILE_KEY));

        assertThat(out.get("threads")).hasSize(1);
        List<String> paths = new ArrayList<>();
        out.get("threads").forEach(node -> paths.add(node.get("path").asString()));
        assertThat(paths).containsExactly("src/Bar.swift").doesNotContain("src/Foo.swift");
    }

    @Test
    void shouldDateAThreadWhenTheProviderRecordedWhenItWasOpenedAndResolved() throws Exception {
        PullRequestReviewThread dated =
                thread(PullRequestReviewThread.State.RESOLVED, "src/Foo.swift", 12, user("reviewer-b"));
        dated.setCreatedAt(Instant.parse("2025-06-01T10:00:00Z"));
        dated.setResolvedAt(Instant.parse("2025-06-02T08:00:00Z"));
        stubThreads(List.of(dated, thread(PullRequestReviewThread.State.UNRESOLVED, "src/Bar.swift", 5, null)));

        Map<String, byte[]> files = new HashMap<>();
        files.putAll(provider.capture(request(metadataWithPr()), provider.sourceKinds())
                .files());

        JsonNode threads = objectMapper.readTree(files.get(FILE_KEY)).get("threads");
        assertThat(threads.get(0).get("createdAt").asString()).isEqualTo("2025-06-01T10:00:00Z");
        assertThat(threads.get(0).get("resolvedAt").asString()).isEqualTo("2025-06-02T08:00:00Z");
        assertThat(threads.get(1).has("createdAt")).isFalse();
        assertThat(threads.get(1).has("resolvedAt")).isFalse();
    }

    @Test
    void contribute_pendingReview_excludedFromDecisions() throws Exception {
        // A PENDING review is an unsubmitted draft ("only visible to the author") with no
        // submittedAt — it
        // must never reach the agent as a real decision, else it fabricates an outstanding-CHANGES
        // signal.
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenReturn(List.of(
                        review(
                                PullRequestReview.State.PENDING,
                                "drafting-reviewer",
                                Instant.parse("2025-05-01T12:00:00Z")),
                        review(PullRequestReview.State.APPROVED, "reviewer-a", Instant.parse("2025-06-01T12:00:00Z"))));

        Map<String, byte[]> files = new HashMap<>();
        files.putAll(provider.capture(request(metadataWithPr()), provider.sourceKinds())
                .files());

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
    void shouldPropagateProgrammingFailuresWithoutRecordingCaptureRefusals() {
        var failure = new NullPointerException("Invalid review mapping");
        when(reviewRepository.findRecentByPullRequestIdWithAuthor(any(), any(), any()))
                .thenThrow(failure);

        Map<String, byte[]> files = new HashMap<>();
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> files.putAll(provider.capture(request(metadataWithPr()), provider.sourceKinds())
                        .files()))
                .isSameAs(failure);
        assertThat(files).doesNotContainKey(FILE_KEY);
    }
}
