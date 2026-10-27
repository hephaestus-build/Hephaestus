package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITHUB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyForm;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyWriteResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryLookup;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.PostedText;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitHubLineNoteWriterTest extends BaseUnitTest {

    private static final String MARKER = "<!-- hephaestus-review-package:job-1 -->";
    private static final String COMMIT = "b".repeat(40);
    private static final String THREAD_URL = "https://github.com/owner/repo/pull/42#discussion_r456";

    @Mock
    private GitHubGraphQlClientProvider gitHubProvider;

    @Mock
    private GitHubPrNodeIdResolver prNodeIdResolver;

    @Mock
    private OutboundEgressGuard egressGuard;

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private GitHubSummaryWriter summaries;

    private GitHubLineNoteWriter channel;
    private final RecordingFence fence = new RecordingFence();

    @BeforeEach
    void setUp() {
        channel = new GitHubLineNoteWriter(
                gitHubProvider, prNodeIdResolver, egressGuard, ScmFeedbackFixtures.resolver(), summaries);
    }

    @Test
    void shouldVerifyEachCopyMarkerAndIgnoreKeysQuotedEarlierInFeedback() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        var a = ScmFeedbackFixtures.line(
                ScmFeedbackFixtures.anchor("src/Foo.java", 10, null),
                "Quoted <!-- hephaestus-diff-note-ck=wrong -->",
                "<!-- copy-a -->",
                "copy-a");
        var b = ScmFeedbackFixtures.line(
                ScmFeedbackFixtures.anchor("src/Foo.java", 10, null), "Second note", "<!-- copy-b -->", "copy-b");
        stubReviewThreads(List.of(thread("THREAD_a", "RC_a", authored(a)), thread("THREAD_b", "RC_b", authored(b))));
        assertThat(channel.findPosted(githubTarget(), List.of(a, b)))
                .extracting(CopyReceipt::externalRef)
                .containsExactlyInAnyOrder("RC_a", "RC_b");
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
    }

    @Test
    void shouldPostAFileAnchorAsAPinnedLinkAndReportItsActualForm() {
        var note = ScmFeedbackFixtures.line(
                new FeedbackAnchor("src/a file.ts", null, null, null), "Explain this file.", MARKER, "file-copy");
        when(summaries.findExistingSummary(any(), any())).thenReturn(SummaryLookup.absent());
        when(summaries.postSummary(any(), any())).thenReturn(new SummaryHandle("IC_1", THREAD_URL));
        var result = channel.postImmutablePackage(githubTarget(), List.of(note), fence);
        assertThat(result.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.acknowledged()).isTrue();
            assertThat(receipt.actualKind()).isEqualTo(CopyForm.FILE_NOTE);
        });
        var posted = ArgumentCaptor.forClass(SummaryContent.class);
        verify(summaries).postSummary(any(), posted.capture());
        assertThat(posted.getValue().externalBody())
                .contains("/blob/" + COMMIT + "/src/a%20file.ts", MARKER, "hephaestus-diff-note-ck=file-copy")
                .doesNotContain("#L", ":null");
        assertThat(fence.attempts).containsExactly(List.of(note.copyId().toString()));
    }

    @Test
    void shouldNotCreateAFileCopyAfterUnknownReadback() {
        var note = ScmFeedbackFixtures.line(new FeedbackAnchor("a.ts", null, null, null), "Words", MARKER, "file-copy");
        when(summaries.findExistingSummary(any(), any())).thenReturn(SummaryLookup.unknown());
        assertThat(channel.postImmutablePackage(githubTarget(), List.of(note), fence)
                        .receipts())
                .singleElement()
                .satisfies(receipt -> assertThat(receipt.writeMayHaveStarted()).isFalse());
        verify(summaries, never()).postSummary(any(), any());
        assertThat(fence.attempts).isEmpty();
    }

    @Test
    void missingReviewedRevisionCannotCreateOrAcknowledgeInlineFeedback() {
        FeedbackTarget unpinned =
                ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        assertThat(channel.postImmutablePackage(unpinned, List.of(item("fix", "result:a:0")), fence)
                        .receipts())
                .singleElement()
                .satisfies(receipt -> assertThat(receipt.disposition()).isEqualTo(CopyDisposition.FAILED));
        assertThat(channel.findPosted(unpinned, List.of(item("fix", "result:a:0"))))
                .isNull();
        assertThat(fence.attempts).isEmpty();
        verify(gitHubProvider, never()).forScope(1L);
    }

    @Test
    void shouldRequestNothingForAnEmptyPackage() {
        assertThat(channel.postImmutablePackage(githubTarget(), List.of(), fence)
                        .receipts())
                .isEmpty();
        assertThat(fence.attempts).isEmpty();
    }

    @Test
    void shouldRequestNothingWhenTheExistingThreadsCannotBeRead() {
        when(gitHubProvider.forScope(1L)).thenThrow(new RuntimeException("provider unavailable"));

        assertThatThrownBy(
                        () -> channel.postImmutablePackage(githubTarget(), List.of(item("fix", "result:a:0")), fence))
                .isInstanceOf(FeedbackDeliveryException.class);
        assertThat(fence.attempts).isEmpty();
    }

    @Test
    void shouldRequestNothingWhenTheScanEndsOnItsPageBudgetWithMorePages() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        stubReviewThreadPages(20, true);

        assertThatThrownBy(
                        () -> channel.postImmutablePackage(githubTarget(), List.of(item("fix", "result:a:0")), fence))
                .isInstanceOf(FeedbackDeliveryException.class);
        assertThat(fence.attempts).isEmpty();
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
    }

    @Test
    void findPostedIsInconclusiveWhenThePageBudgetEndsWithMorePages() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        stubReviewThreadPages(20, true);

        assertThat(channel.findPosted(githubTarget(), List.of(item("fix", "result:a:0"))))
                .isNull();
    }

    @Test
    void findPostedAnswersWhenTheLastBudgetedPageIsTheLastPage() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        stubReviewThreadPages(20, false);

        assertThat(channel.findPosted(githubTarget(), List.of(item("fix", "result:a:0"))))
                .isEmpty();
    }

    @Test
    void shouldReportEveryThreadUnsentWithoutRequestingWhenTheRateLimitIsCritical() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(true);

        CopyWriteResult result =
                channel.postImmutablePackage(githubTarget(), List.of(item("fix", "result:a:0")), fence);

        assertThat(result.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.acknowledged()).isFalse();
            assertThat(receipt.writeMayHaveStarted()).isFalse();
        });
        assertThat(fence.attempts).isEmpty();
    }

    @Test
    void shouldFenceTheWholeBatchBeforeTheOneReviewRequestAndMatchReturnedCommentsByTag() {
        stubPostable();
        stubReviewThreads(List.of());
        // Both comments anchor at one line; only the correlation tag tells them apart.
        GraphQlClient.RequestSpec addSpec = stubAddReview(
                "REVIEW_1",
                List.of(
                        returned("RC_b", "observation:b:0", "https://github.com/owner/repo/pull/42#discussion_r222"),
                        returned("RC_a", "result:a:0", "https://github.com/owner/repo/pull/42#discussion_r111")));
        ThreadsCaptor threads = captureThreads(addSpec);

        CopyWriteResult result = channel.postImmutablePackage(
                githubTarget(), List.of(item("fix-a", "result:a:0"), item("fix-b", "observation:b:0")), fence);

        assertThat(fence.attempts)
                .containsExactly(List.of(
                        ScmFeedbackFixtures.copyId("result:a:0").toString(),
                        ScmFeedbackFixtures.copyId("observation:b:0").toString()));
        assertThat(signalForKey(result, "result:a:0")).satisfies(receipt -> {
            assertThat(receipt.disposition()).isEqualTo(CopyDisposition.POSTED);
            assertThat(receipt.externalRef()).isEqualTo("RC_a");

            assertThat(receipt.externalUrl()).isEqualTo("https://github.com/owner/repo/pull/42#discussion_r111");
        });
        assertThat(signalForKey(result, "observation:b:0").externalRef()).isEqualTo("RC_b");
        assertThat(threads.bodies())
                .containsExactly(posted(item("fix-a", "result:a:0")), posted(item("fix-b", "observation:b:0")));
    }

    @Test
    void shouldPostALineNoteThatNamesSomeoneWithTheNameAsCode() {
        stubPostable();
        stubReviewThreads(List.of());
        GraphQlClient.RequestSpec addSpec = stubAddReview(
                "REVIEW_1",
                List.of(returned("RC_a", "result:a:0", "https://github.com/owner/repo/pull/42#discussion_r111")));
        ThreadsCaptor threads = captureThreads(addSpec);

        channel.postImmutablePackage(githubTarget(), List.of(item("Ask @alice", "result:a:0")), fence);

        assertThat(threads.bodies()).singleElement().asString().startsWith("Ask <code>@alice</code>\n\n");
    }

    @Test
    void shouldRecognizeAPostedLineNoteThatNamedSomeone() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        var note = item("Ask @alice", "result:a:0");
        stubReviewThreads(List.of(thread("THREAD_a", "RC_a", authored(note))));

        assertThat(channel.findPosted(githubTarget(), List.of(note)))
                .extracting(CopyReceipt::externalRef)
                .containsExactly("RC_a");
    }

    @Test
    void shouldNotDeliverAThreadTheReviewReturnedWithoutItsTagOrItsId() {
        stubPostable();
        stubReviewThreads(List.of());
        Map<String, Object> withoutId = returned("RC_ignored", "observation:b:0", THREAD_URL);
        withoutId.remove("id");
        stubAddReview("REVIEW_1", List.of(Map.of("id", "RC_untagged", "url", THREAD_URL, "body", "fix-a"), withoutId));

        CopyWriteResult result = channel.postImmutablePackage(
                githubTarget(), List.of(item("fix-a", "result:a:0"), item("fix-b", "observation:b:0")), fence);

        assertThat(result.receipts().stream().filter(CopyReceipt::acknowledged).count())
                .isZero();
        assertThat(result.receipts()).allSatisfy(receipt -> {
            assertThat(receipt.acknowledged()).isFalse();
            assertThat(receipt.unconfirmed()).isTrue();
        });
    }

    @Test
    void shouldLeaveEveryFencedThreadUnconfirmedWhenTheBatchAnswersWithErrorsOrIsLost() {
        stubPostable();
        LineNote kept = item("fix-a", "result:a:0");
        stubReviewThreads(List.of(thread("THREAD_a", "RC_a", authored(kept))));
        stubAddReviewWithErrors();

        CopyWriteResult rejected =
                channel.postImmutablePackage(githubTarget(), List.of(kept, item("fix-b", "observation:b:0")), fence);

        assertThat(signalForKey(rejected, "result:a:0").disposition()).isEqualTo(CopyDisposition.PRESERVED_EXISTING);
        assertThat(signalForKey(rejected, "observation:b:0").unconfirmed()).isTrue();
        assertThat(rejected.receipts().stream()
                        .filter(CopyReceipt::acknowledged)
                        .count())
                .isEqualTo(1);

        GraphQlClient.RequestSpec lost = mock(GraphQlClient.RequestSpec.class);
        when(client.documentName("AddPullRequestReviewWithThreads")).thenReturn(lost);
        when(lost.variable(any(), any())).thenReturn(lost);
        when(lost.execute()).thenReturn(Mono.error(new RuntimeException("connection reset")));

        CopyWriteResult unknown = channel.postImmutablePackage(
                githubTarget(), List.of(item("fix-b", "observation:b:0"), item("fix-c", "observation:c:0")), fence);

        assertThat(unknown.receipts())
                .allSatisfy(receipt -> assertThat(receipt.writeMayHaveStarted()).isTrue());
    }

    @Test
    void shouldPreserveAnAuthoredCopyThatIsOutdatedResolvedOrRepliedToWithoutAnyWrite() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        LineNote kept = item("fix", "result:a:0");
        Map<String, Object> thread = thread("THREAD_a", "RC_a", authored(kept));
        thread.put("isOutdated", true);
        thread.put("isResolved", true);
        addReply(thread, "Done, thanks!");
        stubReviewThreads(List.of(thread));

        CopyWriteResult result = channel.postImmutablePackage(githubTarget(), List.of(kept), fence);

        assertThat(result.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.disposition()).isEqualTo(CopyDisposition.PRESERVED_EXISTING);
            assertThat(receipt.externalRef()).isEqualTo("RC_a");

            assertThat(receipt.externalUrl()).isEqualTo(THREAD_URL);
        });
        assertThat(fence.attempts).isEmpty();
        verify(prNodeIdResolver, never()).resolve(any(Long.class), any(), any(), any(Integer.class));
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
        verify(client, never()).documentName("MinimizeComment");
    }

    @ParameterizedTest
    @EnumSource(CitationSide.class)
    void shouldVerifyBothRangeSidesBeforeAcknowledgingAnExistingCopy(CitationSide side) {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        var kept =
                ScmFeedbackFixtures.line(new FeedbackAnchor("src/Foo.java", 9, 10, side), "fix", MARKER, "range-copy");
        var comment = authored(kept);
        comment.put("originalStartLine", 9);
        var thread = thread("THREAD_range", "RC_range", comment);
        String expectedSide = side == CitationSide.OLD ? "LEFT" : "RIGHT";
        String otherSide = side == CitationSide.OLD ? "RIGHT" : "LEFT";
        thread.put("diffSide", expectedSide);
        thread.put("startDiffSide", expectedSide);
        stubReviewThreads(List.of(thread));

        assertThat(channel.findPosted(githubTarget(), List.of(kept)))
                .singleElement()
                .satisfies(copy -> assertThat(copy.externalRef()).isEqualTo("RC_range"));

        thread.put("startDiffSide", otherSide);
        assertThat(channel.findPosted(githubTarget(), List.of(kept))).isEmpty();
        thread.remove("startDiffSide");
        assertThat(channel.findPosted(githubTarget(), List.of(kept))).isEmpty();
        thread.put("startDiffSide", expectedSide);
        thread.put("diffSide", otherSide);
        assertThat(channel.findPosted(githubTarget(), List.of(kept))).isEmpty();
        thread.remove("diffSide");
        assertThat(channel.findPosted(githubTarget(), List.of(kept))).isEmpty();
        assertThat(fence.attempts).isEmpty();
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
    }

    @Test
    void shouldNeitherAcknowledgeNorCreateNextToACopyThatIsNotExactlyItsOwn() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        LineNote a = item("fix-a", "result:a:0");
        LineNote b = item("fix-b", "observation:b:0");
        LineNote c = item("fix-c", "observation:c:0");
        Map<String, Object> otherAuthor = authored(a);
        otherAuthor.put("viewerDidAuthor", false);
        Map<String, Object> editedBody = authored(b);
        editedBody.put("body", posted(b) + " edited");
        Map<String, Object> otherCommit = authored(c);
        otherCommit.put("originalCommit", Map.of("oid", "another-commit"));
        stubReviewThreads(List.of(
                thread("THREAD_a", "RC_a", otherAuthor),
                thread("THREAD_b", "RC_b", editedBody),
                thread("THREAD_c", "RC_c", otherCommit)));

        CopyWriteResult result = channel.postImmutablePackage(githubTarget(), List.of(a, b, c), fence);

        assertThat(result.receipts()).allSatisfy(receipt -> {
            assertThat(receipt.acknowledged()).isFalse();
            assertThat(receipt.writeMayHaveStarted()).isFalse();
        });
        assertThat(fence.attempts).isEmpty();
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
        assertThat(channel.findPosted(githubTarget(), List.of(a, b, c))).isEmpty();
    }

    @Test
    void shouldRequestNothingWhenAPageLeavesItsEndOrAThreadsCommentsUnanswered() {
        stubPostableScanOnly();
        Map<String, Object> unread = new HashMap<>(Map.of("id", "THREAD_x", "isOutdated", false, "isResolved", false));
        stubReviewThreadsPage(threadsPage(List.of(), null, null));

        assertThatThrownBy(
                        () -> channel.postImmutablePackage(githubTarget(), List.of(item("fix", "result:a:0")), fence))
                .isInstanceOf(FeedbackDeliveryException.class);

        stubReviewThreadsPage(threadsPage(List.of(unread), false, null));
        assertThatThrownBy(
                        () -> channel.postImmutablePackage(githubTarget(), List.of(item("fix", "result:a:0")), fence))
                .isInstanceOf(FeedbackDeliveryException.class);
        assertThat(fence.attempts).isEmpty();
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
    }

    @Test
    void shouldRequestNothingWhenTheFenceRefusesOrEgressIsSilenced() {
        stubPostable();
        stubReviewThreads(List.of());
        fence.accept = false;

        CopyWriteResult refused =
                channel.postImmutablePackage(githubTarget(), List.of(item("fix", "result:a:0")), fence);

        assertThat(refused.receipts())
                .singleElement()
                .satisfies(receipt -> assertThat(receipt.writeMayHaveStarted()).isFalse());
        doThrow(new OutboundEgressSuppressedException("github.post-inline-feedback"))
                .when(egressGuard)
                .requireDeliveryAllowed(anyString());
        CopyWriteResult silenced =
                channel.postImmutablePackage(githubTarget(), List.of(item("fix", "observation:b:0")), fence);

        assertThat(silenced.withheldCopyIds()).isNotEmpty();
        assertThat(silenced.withheldCopyIds()).containsExactly(ScmFeedbackFixtures.copyId("observation:b:0"));
        assertThat(fence.attempts).hasSize(1);
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
    }

    private static LineNote item(String body, String key) {
        return ScmFeedbackFixtures.line(ScmFeedbackFixtures.anchor("src/Foo.java", 10, null), body, MARKER, key);
    }

    private static String posted(LineNote item) {
        return PostedText.of(item.body()) + "\n\n" + item.writeMarker() + "\n" + ckTag(String.valueOf(item.writeKey()));
    }

    private static Map<String, Object> authored(LineNote item) {
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", "RC_" + item.writeKey());
        comment.put("url", THREAD_URL);
        comment.put("body", posted(item));
        comment.put("viewerDidAuthor", true);
        comment.put("path", "src/Foo.java");
        comment.put("originalLine", 10);
        comment.put("originalStartLine", null);
        comment.put("originalCommit", Map.of("oid", COMMIT));
        return comment;
    }

    private static Map<String, Object> returned(String id, String key, String url) {
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", id);
        comment.put("url", url);
        comment.put("body", "posted body\n" + ckTag(key));
        return comment;
    }

    private void stubPostableScanOnly() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
    }

    private void stubPostable() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(prNodeIdResolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_node123");
    }

    private void stubReviewThreadPages(int pages, boolean moreAfterLast) {
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(client.documentName("GetPullRequestReviewThreads")).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        List<Mono<ClientGraphQlResponse>> responses = new ArrayList<>();
        for (int page = 1; page <= pages; page++) {
            responses.add(Mono.just(threadsPage(List.of(), page < pages || moreAfterLast, "cursor-" + page)));
        }
        var next = responses.iterator();
        when(spec.execute()).thenAnswer(invocation -> next.next());
    }

    private void stubReviewThreads(List<Map<String, Object>> nodes) {
        stubReviewThreadsPage(threadsPage(nodes, false, null));
    }

    private void stubReviewThreadsPage(ClientGraphQlResponse page) {
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(client.documentName("GetPullRequestReviewThreads")).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        when(spec.execute()).thenReturn(Mono.just(page));
    }

    private static ClientGraphQlResponse threadsPage(
            List<Map<String, Object>> nodes, @Nullable Boolean hasNextPage, @Nullable String endCursor) {
        Map<String, Object> pageInfo = new HashMap<>();
        pageInfo.put("hasNextPage", hasNextPage);
        pageInfo.put("endCursor", endCursor);
        Map<String, Object> connection = Map.of("nodes", nodes, "pageInfo", pageInfo);
        assertVendorCouldReturn(
                GITHUB, "GetPullRequestReviewThreads", "repository.pullRequest.reviewThreads", connection);
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        lenient().when(response.getErrors()).thenReturn(List.of());
        stubField(response, "repository.pullRequest.reviewThreads", connection);
        return response;
    }

    private GraphQlClient.RequestSpec stubAddReview(String reviewId, List<Map<String, Object>> commentNodes) {
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(client.documentName("AddPullRequestReviewWithThreads")).thenReturn(spec);
        lenient().when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        lenient().when(response.getErrors()).thenReturn(List.of());
        stubField(response, "addPullRequestReview.pullRequestReview.id", reviewId);
        stubField(response, "addPullRequestReview.pullRequestReview.comments.nodes", commentNodes);
        when(spec.execute()).thenReturn(Mono.just(response));
        return spec;
    }

    private static ThreadsCaptor captureThreads(GraphQlClient.RequestSpec spec) {
        ThreadsCaptor captor = new ThreadsCaptor();
        when(spec.variable(any(), any())).thenAnswer(inv -> {
            if ("threads".equals(inv.getArgument(0))) {
                captor.value = inv.getArgument(1);
            }
            return spec;
        });
        return captor;
    }

    private void stubAddReviewWithErrors() {
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(client.documentName("AddPullRequestReviewWithThreads")).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ResponseError error = mock(ResponseError.class);
        lenient().when(error.getMessage()).thenReturn("commitOID is not a valid GitObjectID");
        when(response.getErrors()).thenReturn(List.of(error));
        when(spec.execute()).thenReturn(Mono.just(response));
    }

    private static void stubField(ClientGraphQlResponse response, String path, @Nullable Object value) {
        ClientResponseField field = mock(ClientResponseField.class);
        lenient().when(response.field(path)).thenReturn(field);
        lenient().when(field.getValue()).thenReturn(value);
    }

    private static Map<String, Object> thread(
            String threadId, String firstCommentId, Map<String, Object> firstComment) {
        firstComment.put("id", firstCommentId);
        Map<String, Object> t = new HashMap<>();
        t.put("id", threadId);
        t.put("isOutdated", false);
        t.put("isResolved", false);
        t.put("diffSide", "RIGHT");
        t.put("comments", Map.of("nodes", new ArrayList<>(List.of(firstComment))));
        return t;
    }

    @SuppressWarnings("unchecked")
    private static void addReply(Map<String, Object> thread, String body) {
        Map<String, Object> comments = (Map<String, Object>) Objects.requireNonNull(thread.get("comments"));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) Objects.requireNonNull(comments.get("nodes"));
        nodes.add(Map.of("id", "RC_reply", "body", body, "viewerDidAuthor", false));
    }

    private static String ckTag(String key) {
        return "<!-- hephaestus-diff-note-ck=" + key + " -->";
    }

    private static CopyReceipt signalForKey(CopyWriteResult result, String key) {
        return result.receipts().stream()
                .filter(s -> ScmFeedbackFixtures.copyId(key).equals(s.copyId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no signal for key " + key));
    }

    private static FeedbackTarget githubTarget() {
        return ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", COMMIT);
    }

    private static final class ThreadsCaptor {

        private @Nullable Object value;

        List<String> bodies() {
            if (!(value instanceof List<?> threads)) {
                throw new AssertionError("no review threads were requested");
            }
            return threads.stream()
                    .map(thread -> String.valueOf(((Map<?, ?>) thread).get("body")))
                    .toList();
        }
    }

    private static final class RecordingFence implements CopyWriteFence {

        final List<List<String>> attempts = new ArrayList<>();
        boolean accept = true;

        @Override
        public boolean beforeCreate(List<UUID> attempting, List<CopyReceipt> completed) {
            attempts.add(attempting.stream().map(String::valueOf).toList());
            return accept;
        }
    }
}
