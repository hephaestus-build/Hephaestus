package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitLabInlineFeedbackChannelTest extends BaseUnitTest {

    private static final String MARKER = "<!-- hephaestus-review-package:job-1 -->";
    private static final String REVIEWED = "reviewed-sha";
    private static final String BOT = "gid://gitlab/User/1";
    private static final String PERSON = "gid://gitlab/User/2";
    private static final String NOTE_URL = "https://gitlab.example.com/group/project/-/merge_requests/42#note_987654";

    @Mock
    private GitLabGraphQlClientProvider gitLabProvider;

    @Mock
    private GitLabMrResolver mrResolver;

    @Mock
    private OutboundEgressGuard egressGuard;

    private GitLabInlineFeedbackChannel channel;
    private HttpGraphQlClient client;
    private final RecordingFence fence = new RecordingFence();

    @BeforeEach
    void setUp() {
        channel = new GitLabInlineFeedbackChannel(gitLabProvider, mrResolver, egressGuard);
        client = mock(HttpGraphQlClient.class);
    }

    @Test
    void shouldRequestNothingForAnEmptyPackage() {
        assertThat(channel.postImmutablePackage(target(), List.of(), Readback.AUTHORED, fence)
                        .signals())
                .isEmpty();
        assertThat(fence.attempts).isEmpty();
    }

    @Test
    void shouldRequestNothingWhenTheScanCannotBeRead() {
        when(mrResolver.resolve(1L, "group/project", 42))
                .thenReturn(new MrInfo("gid://gitlab/MR/42", "base", "head", "start"));
        when(gitLabProvider.forScope(1L)).thenThrow(new RuntimeException("provider unavailable"));

        assertThatThrownBy(() -> channel.postImmutablePackage(
                        target(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED, fence))
                .isInstanceOf(FeedbackDeliveryException.class);
        assertThat(fence.attempts).isEmpty();
    }

    @Test
    void shouldRequestNothingWhenTheScanEndsOnItsPageBudgetWithMorePages() {
        stubResolvedMr();
        GraphQlClient.RequestSpec spec = discussionsSpec();
        AtomicInteger pages = new AtomicInteger();
        when(spec.execute())
                .thenAnswer(invocation ->
                        Mono.just(discussionsResponse(List.of(), true, "page-" + pages.incrementAndGet(), BOT)));

        assertThatThrownBy(() -> channel.postImmutablePackage(
                        target(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED, fence))
                .isInstanceOf(FeedbackDeliveryException.class);
        verify(spec, times(50)).execute();
        verify(client, never()).documentName("CreateDiffNote");
        assertThat(fence.attempts).isEmpty();
    }

    @Test
    void shouldRequestNothingWhenAPageLeavesItsEndOrIdentityUnanswered() {
        stubResolvedMr();
        GraphQlClient.RequestSpec spec = discussionsSpec();
        ClientGraphQlResponse missingPage = discussionsResponse(List.of(), null, null, BOT);
        ClientGraphQlResponse missingIdentity = discussionsResponse(List.of(), false, null, null);
        when(spec.execute()).thenReturn(Mono.just(missingPage)).thenReturn(Mono.just(missingIdentity));

        for (Readback readback : List.of(Readback.AUTHORED, Readback.SHARED)) {
            assertThatThrownBy(() -> channel.postImmutablePackage(
                            target(), List.of(item("fix", "observation:a:0")), readback, fence))
                    .isInstanceOf(FeedbackDeliveryException.class);
        }
        assertThat(fence.attempts).isEmpty();
        verify(client, never()).documentName("CreateDiffNote");
    }

    @Test
    void shouldReportEveryNoteUnsentWithoutRequestingWhenTheRateLimitIsCritical() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(true);

        InlineResult result = channel.postImmutablePackage(
                target(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED, fence);

        assertThat(result.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.acknowledged()).isFalse();
            assertThat(signal.writeMayHaveStarted()).isFalse();
        });
        assertThat(fence.attempts).isEmpty();
    }

    @Test
    void shouldFenceEachNoteBeforeItsCreateOnTheReviewedCommitAndHandTheReturnedHandleOn() {
        stubResolvedMr();
        stubDiscussions(List.of(), BOT);
        GraphQlClient.RequestSpec spec = createSpec();
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> positions = ArgumentCaptor.forClass(Object.class);
        when(spec.variable(eq("body"), bodies.capture())).thenReturn(spec);
        when(spec.variable(eq("position"), positions.capture())).thenReturn(spec);
        ClientGraphQlResponse created = createResponse(note("gid://Note/NEW", "gid://Disc/NEW"), List.of());
        when(spec.execute()).thenReturn(Mono.just(created));

        InlineResult result = channel.postImmutablePackage(
                target(),
                List.of(item("/approve once fixed", "observation:a:0"), item("second", "observation:b:0")),
                Readback.AUTHORED,
                fence);

        assertThat(fence.attempts).containsExactly(List.of("observation:a:0"), List.of("observation:b:0"));
        assertThat(fence.completedSeen.get(1)).singleElement().satisfies(signal -> {
            assertThat(signal.deliveryKey()).isEqualTo("observation:a:0");
            assertThat(signal.externalRef()).isEqualTo("gid://Note/NEW");
        });
        assertThat(result.signals()).allSatisfy(signal -> {
            assertThat(signal.disposition()).isEqualTo(Disposition.POSTED);
            assertThat(signal.externalUrl()).isEqualTo(NOTE_URL);
            assertThat(signal.threadExternalRef()).isEqualTo("gid://Disc/NEW");
        });
        assertThat(bodies.getAllValues().getFirst())
                .contains("`/approve`", MARKER, "hephaestus-diff-note-ck=observation:a:0");
        assertThat(positions.getAllValues().getFirst())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("headSha", REVIEWED);
    }

    @Test
    void shouldRequestNothingWithoutTheReviewedCommit() {
        InlineResult result = channel.postImmutablePackage(
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITLAB, 1L, null), "group/project!42", null),
                List.of(item("fix", "observation:a:0")),
                Readback.AUTHORED,
                fence);

        assertThat(result.signals())
                .singleElement()
                .satisfies(signal -> assertThat(signal.writeMayHaveStarted()).isFalse());
        assertThat(fence.attempts).isEmpty();
    }

    @Test
    void shouldPreserveAnExistingCopyThatIsResolvedOrRepliedToWithoutEverWritingToIt() {
        stubResolvedMr();
        InlineFeedback kept = item("fix", "observation:a:0");
        Map<String, Object> disc = discussion(
                "gid://Disc/OLD",
                List.of(
                        diffNote("gid://Note/OLD", posted(kept), BOT, 10, REVIEWED),
                        note("gid://Note/H", "Done!", PERSON)));
        disc.put("resolved", true);
        stubDiscussions(List.of(disc), BOT);

        InlineResult result = channel.postImmutablePackage(target(), List.of(kept), Readback.AUTHORED, fence);

        assertThat(result.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.disposition()).isEqualTo(Disposition.PRESERVED_EXISTING);
            assertThat(signal.externalRef()).isEqualTo("gid://Note/OLD");
            assertThat(signal.externalUrl()).isEqualTo(NOTE_URL);
        });
        assertThat(fence.attempts).isEmpty();
        verify(client, never()).documentName("CreateDiffNote");
    }

    @Test
    void shouldNeitherAcknowledgeNorCreateNextToACopyThatIsNotExactlyItsOwn() {
        stubResolvedMr();
        InlineFeedback a = item("fix-a", "observation:a:0");
        InlineFeedback b = item("fix-b", "observation:b:0");
        InlineFeedback c = item("fix-c", "observation:c:0");
        InlineFeedback d = item("fix-d", "observation:d:0");
        stubDiscussions(
                List.of(
                        discussion("gid://Disc/1", List.of(diffNote("gid://Note/1", posted(a), PERSON, 10, REVIEWED))),
                        discussion(
                                "gid://Disc/2",
                                List.of(diffNote("gid://Note/2", posted(b) + " edited", BOT, 10, REVIEWED))),
                        discussion("gid://Disc/3", List.of(diffNote("gid://Note/3", posted(c), BOT, 11, REVIEWED))),
                        discussion("gid://Disc/4", List.of(diffNote("gid://Note/4", posted(d), BOT, 10, "older")))),
                BOT);

        InlineResult result = channel.postImmutablePackage(target(), List.of(a, b, c, d), Readback.AUTHORED, fence);

        assertThat(result.signals())
                .allSatisfy(signal -> assertThat(signal.acknowledged()).isFalse());
        assertThat(fence.attempts).isEmpty();
        verify(client, never()).documentName("CreateDiffNote");
        assertThat(channel.findPosted(target(), List.of(a, b, c, d), Readback.AUTHORED))
                .isEmpty();
    }

    @Test
    void shouldChooseTheAuthoredOriginalOverAHumanReplyCopyingItsMarker() {
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        InlineFeedback wanted = item("fix", "observation:a:0");
        stubDiscussions(
                List.of(discussion(
                        "gid://Disc/A",
                        List.of(
                                diffNote("gid://Note/A", posted(wanted), BOT, 10, REVIEWED),
                                note("gid://Note/COPY", posted(wanted), PERSON)))),
                BOT);

        assertThat(channel.findPosted(target(), List.of(wanted), Readback.AUTHORED))
                .singleElement()
                .satisfies(signal -> assertThat(signal.externalRef()).isEqualTo("gid://Note/A"));
    }

    @Test
    void shouldReadAHistoricalPackageBackOnlyByItsOwnAuthoredExactCopy() {
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        InlineFeedback legacy = new InlineFeedback(
                new DiffAnchor("src/Foo.java", 10, null), "fix", "<!-- hephaestus-diff-note -->", "observation:a:0");
        stubDiscussions(
                List.of(discussion(
                        "gid://Disc/1",
                        List.of(
                                diffNote("gid://Note/HUMAN", posted(legacy), PERSON, 10, REVIEWED),
                                diffNote("gid://Note/7", posted(legacy), BOT, 10, REVIEWED)))),
                BOT);

        assertThat(channel.findPosted(target(), List.of(legacy), Readback.SHARED))
                .singleElement()
                .satisfies(signal -> assertThat(signal.externalRef()).isEqualTo("gid://Note/7"));
    }

    @Test
    void shouldReportALostCreateAsUnconfirmedAndStopAtARateLimitLeavingTheRestUnsent() {
        stubResolvedMr();
        stubDiscussions(List.of(), BOT);
        GraphQlClient.RequestSpec spec = createSpec();
        when(spec.execute()).thenReturn(Mono.error(new RuntimeException("429 Too Many Requests")));

        InlineResult result = channel.postImmutablePackage(
                target(),
                List.of(item("fix-a", "observation:a:0"), item("fix-b", "observation:b:0")),
                Readback.AUTHORED,
                fence);

        assertThat(fence.attempts).containsExactly(List.of("observation:a:0"));
        assertThat(result.signals())
                .extracting(DeliveredSignal::writeMayHaveStarted)
                .containsExactly(true, false);
        verify(client, never()).documentName("CreateMergeRequestNote");
    }

    @Test
    void shouldFallBackOnlyAfterAValidLineCodeRejectionWithAnExplicitlyNullNoteAndFenceTheFallbackToo() {
        stubResolvedMr();
        stubDiscussions(List.of(), BOT);
        GraphQlClient.RequestSpec diffSpec = createSpec();
        ClientGraphQlResponse rejected = createResponse(null, List.of("line_code is invalid"));
        when(rejected.isValid()).thenReturn(true);
        when(diffSpec.execute()).thenReturn(Mono.just(rejected));
        GraphQlClient.RequestSpec noteSpec = mock(GraphQlClient.RequestSpec.class);
        when(client.documentName("CreateMergeRequestNote")).thenReturn(noteSpec);
        ArgumentCaptor<String> fallbackBody = ArgumentCaptor.forClass(String.class);
        when(noteSpec.variable(any(), any())).thenReturn(noteSpec);
        when(noteSpec.variable(eq("body"), fallbackBody.capture())).thenReturn(noteSpec);
        ClientGraphQlResponse noteResponse = mock(ClientGraphQlResponse.class);
        Map<String, Object> created = Map.of("note", Map.of("id", "gid://Note/FALLBACK", "url", NOTE_URL));
        stubField(noteResponse, "CreateMergeRequestNote", "createNote", created);
        when(noteSpec.execute()).thenReturn(Mono.just(noteResponse));

        InlineResult result = channel.postImmutablePackage(
                target(),
                List.of(new InlineFeedback(
                        new DiffAnchor("src/Foo.java", 999, null), "fix", MARKER, "observation:a:0")),
                Readback.AUTHORED,
                fence);

        assertThat(fence.attempts).hasSize(2);
        assertThat(fallbackBody.getValue()).startsWith("**`src/Foo.java:999`**\n\nfix\n" + MARKER);
        assertThat(result.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.disposition()).isEqualTo(Disposition.FELL_BACK);
            assertThat(signal.externalRef()).isEqualTo("gid://Note/FALLBACK");
            assertThat(signal.externalUrl()).isEqualTo(NOTE_URL);
        });
    }

    @Test
    void shouldReportAFallbackItsFenceRefusedAsNeverSent() {
        stubResolvedMr();
        stubDiscussions(List.of(), BOT);
        GraphQlClient.RequestSpec diffSpec = createSpec();
        ClientGraphQlResponse rejected = createResponse(null, List.of("line_code is invalid"));
        when(rejected.isValid()).thenReturn(true);
        when(diffSpec.execute()).thenReturn(Mono.just(rejected));
        fence.acceptOnly = 1;

        InlineResult result = channel.postImmutablePackage(
                target(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED, fence);

        assertThat(result.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.acknowledged()).isFalse();
            assertThat(signal.writeMayHaveStarted()).isFalse();
        });
        verify(client, never()).documentName("CreateMergeRequestNote");
    }

    @Test
    void shouldNotFallBackWhenTheRejectionDoesNotProveNoNoteWasSaved() {
        stubResolvedMr();
        stubDiscussions(List.of(), BOT);
        GraphQlClient.RequestSpec diffSpec = createSpec();
        // A generic error, a line-code error beside a top-level error, and a payload missing its note field.
        ClientGraphQlResponse generic = createResponse(null, List.of("validation failed"));
        lenient().when(generic.isValid()).thenReturn(true);
        ClientGraphQlResponse invalid = createResponse(null, List.of("line_code is invalid"));
        lenient().when(invalid.isValid()).thenReturn(true);
        lenient().when(invalid.getErrors()).thenReturn(List.of(mock(ResponseError.class)));
        ClientGraphQlResponse partial = mock(ClientGraphQlResponse.class);
        stubField(partial, "CreateDiffNote", "createDiffNote", Map.of("errors", List.of("line_code is invalid")));
        lenient().when(partial.isValid()).thenReturn(true);
        when(diffSpec.execute())
                .thenReturn(Mono.just(generic))
                .thenReturn(Mono.just(invalid))
                .thenReturn(Mono.just(partial));

        for (int attempt = 0; attempt < 3; attempt++) {
            InlineResult result = channel.postImmutablePackage(
                    target(), List.of(item("fix", "observation:a:" + attempt)), Readback.AUTHORED, fence);
            assertThat(result.signals())
                    .singleElement()
                    .satisfies(signal -> assertThat(signal.unconfirmed()).isTrue());
        }
        verify(client, never()).documentName("CreateMergeRequestNote");
    }

    @Test
    void shouldRequestNothingWhenTheFenceRefusesOrEgressIsSilenced() {
        stubResolvedMr();
        stubDiscussions(List.of(), BOT);
        fence.acceptOnly = 0;

        InlineResult refused = channel.postImmutablePackage(
                target(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED, fence);

        assertThat(refused.signals())
                .singleElement()
                .satisfies(signal -> assertThat(signal.writeMayHaveStarted()).isFalse());
        doThrow(new OutboundEgressSuppressedException("gitlab.post-inline-feedback"))
                .when(egressGuard)
                .requireDeliveryAllowed(anyString());
        InlineResult silenced = channel.postImmutablePackage(
                target(), List.of(item("fix", "observation:b:0")), Readback.AUTHORED, fence);

        assertThat(silenced.suppressed()).isTrue();
        assertThat(silenced.suppressedDeliveryKeys()).containsExactly("observation:b:0");
        verify(client, never()).documentName("CreateDiffNote");
    }

    @Test
    void findPostedIsInconclusiveWhenADiscussionHasMoreNotesThanOneLookupReads() {
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        Map<String, Object> crowded = discussion("gid://gitlab/Discussion/1", List.of());
        crowded.put("notes", Map.of("nodes", List.of(), "pageInfo", Map.of("hasNextPage", true)));
        stubDiscussions(List.of(crowded), BOT);

        assertThat(channel.findPosted(target(), List.of(item("fix", "observation:a:0")), Readback.AUTHORED))
                .isNull();
    }

    private static InlineFeedback item(String body, String key) {
        return new InlineFeedback(new DiffAnchor("src/Foo.java", 10, null), body, MARKER, key);
    }

    /** The exact body the channel posts for {@code item}, as its own copy would read back. */
    private static String posted(InlineFeedback item) {
        return item.body() + "\n" + item.marker() + "\n<!-- hephaestus-diff-note-ck=" + item.deliveryKey() + " -->";
    }

    private void stubResolvedMr() {
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(mrResolver.resolve(1L, "group/project", 42))
                .thenReturn(new MrInfo("gid://gitlab/MR/42", "base", "head", "start"));
    }

    private GraphQlClient.RequestSpec discussionsSpec() {
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(client.documentName("GetMergeRequestDiscussions")).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        return spec;
    }

    private GraphQlClient.RequestSpec createSpec() {
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(client.documentName("CreateDiffNote")).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        return spec;
    }

    /** Stubs one complete page of discussions read by the identity {@code currentUserId}. */
    private void stubDiscussions(List<Map<String, Object>> discussions, @Nullable String currentUserId) {
        GraphQlClient.RequestSpec spec = discussionsSpec();
        ClientGraphQlResponse response = discussionsResponse(discussions, false, null, currentUserId);
        when(spec.execute()).thenReturn(Mono.just(response));
    }

    private static ClientGraphQlResponse discussionsResponse(
            List<Map<String, Object>> discussions,
            @Nullable Boolean hasNextPage,
            @Nullable String endCursor,
            @Nullable String currentUserId) {
        Map<String, Object> pageInfo = new HashMap<>();
        pageInfo.put("hasNextPage", hasNextPage);
        pageInfo.put("endCursor", endCursor);
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        stubField(
                response,
                "GetMergeRequestDiscussions",
                "project.mergeRequest.discussions",
                Map.of("nodes", discussions, "pageInfo", pageInfo));
        ClientResponseField userField = mock(ClientResponseField.class);
        lenient().when(response.field("currentUser.id")).thenReturn(userField);
        lenient().when(userField.getValue()).thenReturn(currentUserId);
        return response;
    }

    /** A createDiffNote answer: the payload's {@code note}, explicitly null when {@code created} is. */
    private static ClientGraphQlResponse createResponse(@Nullable Map<String, Object> created, List<String> errors) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("note", created);
        payload.put("errors", errors);
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        stubField(response, "CreateDiffNote", "createDiffNote", payload);
        return response;
    }

    private static Map<String, Object> note(String noteId, String discussionId) {
        return Map.of("id", noteId, "url", NOTE_URL, "discussion", Map.of("id", discussionId));
    }

    private static void stubField(ClientGraphQlResponse response, String document, String path, Object value) {
        assertVendorCouldReturn(GITLAB, document, path, value);
        ClientResponseField field = mock(ClientResponseField.class);
        lenient().when(response.field(path)).thenReturn(field);
        lenient().when(field.getValue()).thenReturn(value);
    }

    private static Map<String, Object> note(String id, String body, String authorId) {
        Map<String, Object> n = new HashMap<>();
        n.put("id", id);
        n.put("url", NOTE_URL);
        n.put("body", body);
        n.put("system", false);
        n.put("author", Map.of("id", authorId));
        return n;
    }

    private static Map<String, Object> diffNote(String id, String body, String authorId, int line, String headSha) {
        Map<String, Object> n = note(id, body, authorId);
        n.put("position", Map.of("newPath", "src/Foo.java", "newLine", line, "diffRefs", Map.of("headSha", headSha)));
        return n;
    }

    private static Map<String, Object> discussion(String id, List<Map<String, Object>> notes) {
        Map<String, Object> disc = new HashMap<>();
        disc.put("id", id);
        disc.put("notes", Map.of("nodes", new ArrayList<>(notes), "pageInfo", Map.of("hasNextPage", false)));
        return disc;
    }

    private static FeedbackTarget target() {
        return new FeedbackTarget(new IntegrationRef(IntegrationKind.GITLAB, 1L, null), "group/project!42", REVIEWED);
    }

    /** Records what each create request carried; accepts the first {@code acceptOnly} records. */
    private static final class RecordingFence implements WriteFence {

        final List<List<String>> attempts = new ArrayList<>();
        final List<List<DeliveredSignal>> completedSeen = new ArrayList<>();
        int acceptOnly = Integer.MAX_VALUE;

        @Override
        public boolean beforeCreate(List<InlineFeedback> attempting, List<DeliveredSignal> completed) {
            attempts.add(attempting.stream()
                    .map(item -> String.valueOf(item.deliveryKey()))
                    .toList());
            completedSeen.add(List.copyOf(completed));
            return attempts.size() <= acceptOnly;
        }
    }
}
