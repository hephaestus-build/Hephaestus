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

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor.DiffAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.InlineFeedback;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.InlineResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Readback;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.WriteFence;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitHubInlineFeedbackChannelTest extends BaseUnitTest {

    private static final String MARKER = "<!-- hephaestus-review-package:job-1 -->";
    private static final String COMMIT = "commit-sha-abc";
    private static final String THREAD_URL = "https://github.com/owner/repo/pull/42#discussion_r456";

    @Mock
    private GitHubGraphQlClientProvider gitHubProvider;

    @Mock
    private GitHubPrNodeIdResolver prNodeIdResolver;

    @Mock
    private OutboundEgressGuard egressGuard;

    @Mock
    private HttpGraphQlClient client;

    private GitHubInlineFeedbackChannel channel;
    private final RecordingFence fence = new RecordingFence();

    @BeforeEach
    void setUp() {
        channel = new GitHubInlineFeedbackChannel(gitHubProvider, prNodeIdResolver, egressGuard);
    }

    @Test
    void missingReviewedRevisionCannotCreateOrAcknowledgeInlineFeedback() {
        FeedbackTarget unpinned =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        assertThatThrownBy(() -> channel.postImmutablePackage(
                        unpinned, List.of(item("fix", "observation:a:0")), Readback.AUTHORED, fence))
                .isInstanceOf(FeedbackDeliveryException.class);
        assertThat(channel.findPosted(unpinned, List.of(item("fix", "observation:a:0")), Readback.AUTHORED))
                .isNull();
        assertThat(fence.attempts).isEmpty();
        verify(gitHubProvider, never()).forScope(1L);
    }

    @Test
    void shouldRequestNothingForAnEmptyPackage() {
        assertThat(channel.postImmutablePackage(githubTarget(), List.of(), Readback.AUTHORED, fence)
                        .signals())
                .isEmpty();
        assertThat(fence.attempts).isEmpty();
    }

    @Test
    void shouldRequestNothingWhenTheExistingThreadsCannotBeRead() {
        when(gitHubProvider.forScope(1L)).thenThrow(new RuntimeException("provider unavailable"));

        assertThatThrownBy(() -> channel.postImmutablePackage(
                        githubTarget(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED, fence))
                .isInstanceOf(FeedbackDeliveryException.class);
        assertThat(fence.attempts).isEmpty();
    }

    @Test
    void shouldRequestNothingWhenTheScanEndsOnItsPageBudgetWithMorePages() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        stubReviewThreadPages(20, true);

        assertThatThrownBy(() -> channel.postImmutablePackage(
                        githubTarget(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED, fence))
                .isInstanceOf(FeedbackDeliveryException.class);
        assertThat(fence.attempts).isEmpty();
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
    }

    @Test
    void findPostedIsInconclusiveWhenThePageBudgetEndsWithMorePages() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        stubReviewThreadPages(20, true);

        assertThat(channel.findPosted(githubTarget(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED))
                .isNull();
    }

    @Test
    void findPostedAnswersWhenTheLastBudgetedPageIsTheLastPage() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        stubReviewThreadPages(20, false);

        assertThat(channel.findPosted(githubTarget(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED))
                .isEmpty();
    }

    @Test
    void shouldReportEveryThreadUnsentWithoutRequestingWhenTheRateLimitIsCritical() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(true);

        InlineResult result = channel.postImmutablePackage(
                githubTarget(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED, fence);

        assertThat(result.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.acknowledged()).isFalse();
            assertThat(signal.writeMayHaveStarted()).isFalse();
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
                        returned("RC_a", "observation:a:0", "https://github.com/owner/repo/pull/42#discussion_r111")));
        ThreadsCaptor threads = captureThreads(addSpec);

        InlineResult result = channel.postImmutablePackage(
                githubTarget(),
                List.of(item("fix-a", "observation:a:0"), item("fix-b", "observation:b:0")),
                Readback.AUTHORED,
                fence);

        assertThat(fence.attempts).containsExactly(List.of("observation:a:0", "observation:b:0"));
        assertThat(signalForKey(result, "observation:a:0")).satisfies(signal -> {
            assertThat(signal.disposition()).isEqualTo(Disposition.POSTED);
            assertThat(signal.externalRef()).isEqualTo("RC_a");
            assertThat(signal.threadExternalRef()).isEqualTo("REVIEW_1");
            assertThat(signal.externalUrl()).isEqualTo("https://github.com/owner/repo/pull/42#discussion_r111");
        });
        assertThat(signalForKey(result, "observation:b:0").externalRef()).isEqualTo("RC_b");
        assertThat(threads.bodies())
                .containsExactly(posted(item("fix-a", "observation:a:0")), posted(item("fix-b", "observation:b:0")));
    }

    @Test
    void shouldNotDeliverAThreadTheReviewReturnedWithoutItsTagOrItsId() {
        stubPostable();
        stubReviewThreads(List.of());
        Map<String, Object> withoutId = returned("RC_ignored", "observation:b:0", THREAD_URL);
        withoutId.remove("id");
        stubAddReview("REVIEW_1", List.of(Map.of("id", "RC_untagged", "url", THREAD_URL, "body", "fix-a"), withoutId));

        InlineResult result = channel.postImmutablePackage(
                githubTarget(),
                List.of(item("fix-a", "observation:a:0"), item("fix-b", "observation:b:0")),
                Readback.AUTHORED,
                fence);

        assertThat(result.posted()).isZero();
        assertThat(result.signals()).allSatisfy(signal -> {
            assertThat(signal.acknowledged()).isFalse();
            assertThat(signal.unconfirmed()).isTrue();
        });
    }

    @Test
    void shouldLeaveEveryFencedThreadUnconfirmedWhenTheBatchAnswersWithErrorsOrIsLost() {
        stubPostable();
        InlineFeedback kept = item("fix-a", "observation:a:0");
        stubReviewThreads(List.of(thread("THREAD_a", "RC_a", authored(kept))));
        stubAddReviewWithErrors();

        InlineResult rejected = channel.postImmutablePackage(
                githubTarget(), List.of(kept, item("fix-b", "observation:b:0")), Readback.AUTHORED, fence);

        assertThat(signalForKey(rejected, "observation:a:0").disposition()).isEqualTo(Disposition.PRESERVED_EXISTING);
        assertThat(signalForKey(rejected, "observation:b:0").unconfirmed()).isTrue();
        assertThat(rejected.posted()).isEqualTo(1);

        GraphQlClient.RequestSpec lost = mock(GraphQlClient.RequestSpec.class);
        when(client.documentName("AddPullRequestReviewWithThreads")).thenReturn(lost);
        when(lost.variable(any(), any())).thenReturn(lost);
        when(lost.execute()).thenReturn(Mono.error(new RuntimeException("connection reset")));

        InlineResult unknown = channel.postImmutablePackage(
                githubTarget(),
                List.of(item("fix-b", "observation:b:0"), item("fix-c", "observation:c:0")),
                Readback.AUTHORED,
                fence);

        assertThat(unknown.signals())
                .allSatisfy(signal -> assertThat(signal.writeMayHaveStarted()).isTrue());
    }

    @Test
    void shouldPreserveAnAuthoredCopyThatIsOutdatedResolvedOrRepliedToWithoutAnyWrite() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        InlineFeedback kept = item("fix", "observation:a:0");
        Map<String, Object> thread = thread("THREAD_a", "RC_a", authored(kept));
        thread.put("isOutdated", true);
        thread.put("isResolved", true);
        addReply(thread, "Done, thanks!");
        stubReviewThreads(List.of(thread));

        InlineResult result = channel.postImmutablePackage(githubTarget(), List.of(kept), Readback.AUTHORED, fence);

        assertThat(result.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.disposition()).isEqualTo(Disposition.PRESERVED_EXISTING);
            assertThat(signal.externalRef()).isEqualTo("RC_a");
            assertThat(signal.threadExternalRef()).isEqualTo("THREAD_a");
            assertThat(signal.externalUrl()).isEqualTo(THREAD_URL);
        });
        assertThat(fence.attempts).isEmpty();
        verify(prNodeIdResolver, never()).resolve(any(Long.class), any(), any(), any(Integer.class));
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
        verify(client, never()).documentName("MinimizeComment");
    }

    @Test
    void shouldNeitherAcknowledgeNorCreateNextToACopyThatIsNotExactlyItsOwn() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        InlineFeedback a = item("fix-a", "observation:a:0");
        InlineFeedback b = item("fix-b", "observation:b:0");
        InlineFeedback c = item("fix-c", "observation:c:0");
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

        InlineResult result = channel.postImmutablePackage(githubTarget(), List.of(a, b, c), Readback.AUTHORED, fence);

        assertThat(result.signals()).allSatisfy(signal -> {
            assertThat(signal.acknowledged()).isFalse();
            assertThat(signal.writeMayHaveStarted()).isFalse();
        });
        assertThat(fence.attempts).isEmpty();
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
        assertThat(channel.findPosted(githubTarget(), List.of(a, b, c), Readback.AUTHORED))
                .isEmpty();
    }

    @Test
    void shouldReadAHistoricalThreadBackOnlyByTheViewersCopyRenderedWithoutTheSharedMarker() {
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        InlineFeedback legacy = new InlineFeedback(
                new DiffAnchor("src/Foo.java", 10, null), "fix", "<!-- hephaestus-diff-note -->", "observation:a:0");
        Map<String, Object> human = authored(legacy);
        human.put("body", "fix\n" + ckTag("observation:a:0"));
        human.put("viewerDidAuthor", false);
        Map<String, Object> original = authored(legacy);
        original.put("body", "fix\n" + ckTag("observation:a:0"));
        stubReviewThreads(List.of(thread("THREAD_copy", "RC_copy", human), thread("THREAD_old", "RC_old", original)));

        assertThat(channel.findPosted(githubTarget(), List.of(legacy), Readback.SHARED))
                .singleElement()
                .satisfies(signal -> assertThat(signal.externalRef()).isEqualTo("RC_old"));
    }

    @Test
    void shouldRequestNothingWhenAPageLeavesItsEndOrAThreadsCommentsUnanswered() {
        stubPostableScanOnly();
        Map<String, Object> unread = new HashMap<>(Map.of("id", "THREAD_x", "isOutdated", false, "isResolved", false));
        stubReviewThreadsPage(threadsPage(List.of(), null, null));

        assertThatThrownBy(() -> channel.postImmutablePackage(
                        githubTarget(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED, fence))
                .isInstanceOf(FeedbackDeliveryException.class);

        stubReviewThreadsPage(threadsPage(List.of(unread), false, null));
        assertThatThrownBy(() -> channel.postImmutablePackage(
                        githubTarget(), List.of(item("fix", "observation:a:0")), Readback.SHARED, fence))
                .isInstanceOf(FeedbackDeliveryException.class);
        assertThat(fence.attempts).isEmpty();
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
    }

    @Test
    void shouldRequestNothingWhenTheFenceRefusesOrEgressIsSilenced() {
        stubPostable();
        stubReviewThreads(List.of());
        fence.accept = false;

        InlineResult refused = channel.postImmutablePackage(
                githubTarget(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED, fence);

        assertThat(refused.signals())
                .singleElement()
                .satisfies(signal -> assertThat(signal.writeMayHaveStarted()).isFalse());
        doThrow(new OutboundEgressSuppressedException("github.post-inline-feedback"))
                .when(egressGuard)
                .requireDeliveryAllowed(anyString());
        InlineResult silenced = channel.postImmutablePackage(
                githubTarget(), List.of(item("fix", "observation:b:0")), Readback.AUTHORED, fence);

        assertThat(silenced.suppressed()).isTrue();
        assertThat(silenced.suppressedDeliveryKeys()).containsExactly("observation:b:0");
        assertThat(fence.attempts).hasSize(1);
        verify(client, never()).documentName("AddPullRequestReviewWithThreads");
    }

    private static InlineFeedback item(String body, String key) {
        return new InlineFeedback(new DiffAnchor("src/Foo.java", 10, null), body, MARKER, key);
    }

    /** The exact body the channel posts for {@code item}. */
    private static String posted(InlineFeedback item) {
        return item.body() + "\n\n" + item.marker() + "\n" + ckTag(String.valueOf(item.deliveryKey()));
    }

    /** The first comment the viewer's own review left for {@code item} at the reviewed commit. */
    private static Map<String, Object> authored(InlineFeedback item) {
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", "RC_" + item.deliveryKey());
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

    /** Stubs {@code pages} empty pages of review threads; the last one reports more after it or not. */
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

    /** Stubs GetPullRequestReviewThreads to return a single complete page of the given thread nodes. */
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

    /** Stubs AddPullRequestReviewWithThreads to return the given review id + returned comment nodes. */
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

    /** Captures the {@code threads} variable of a stubbed review request. */
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

    /** Stubs AddPullRequestReviewWithThreads to return a non-empty {@code errors} list. */
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

    /** Builds a reviewThreads node whose first comment is {@code firstComment}. */
    private static Map<String, Object> thread(
            String threadId, String firstCommentId, Map<String, Object> firstComment) {
        firstComment.put("id", firstCommentId);
        Map<String, Object> t = new HashMap<>();
        t.put("id", threadId);
        t.put("isOutdated", false);
        t.put("isResolved", false);
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

    private static DeliveredSignal signalForKey(InlineResult result, String key) {
        return result.signals().stream()
                .filter(s -> key.equals(s.deliveryKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no signal for key " + key));
    }

    private static FeedbackTarget githubTarget() {
        return new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", COMMIT);
    }

    /** Mutable holder for capturing the {@code threads} mutation variable. */
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

    /** Records which delivery keys each create request carried. */
    private static final class RecordingFence implements WriteFence {

        final List<List<String>> attempts = new ArrayList<>();
        boolean accept = true;

        @Override
        public boolean beforeCreate(List<InlineFeedback> attempting, List<DeliveredSignal> completed) {
            attempts.add(attempting.stream()
                    .map(item -> String.valueOf(item.deliveryKey()))
                    .toList());
            return accept;
        }
    }
}
