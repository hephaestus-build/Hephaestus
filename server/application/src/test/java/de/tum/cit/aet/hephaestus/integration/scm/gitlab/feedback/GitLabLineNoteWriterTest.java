package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.PostedText;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

class GitLabLineNoteWriterTest extends BaseUnitTest {

    private static final String MARKER = "<!-- hephaestus-review-package:job-1 -->";
    private static final String REVIEWED = "3f2b9c1d0e8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c";
    private static final String SERVER = "https://gitlab.example.com";
    private static final String BOT = "gid://gitlab/User/1";
    private static final String PERSON = "gid://gitlab/User/2";
    private static final String NOTE_URL = "https://gitlab.example.com/group/project/-/merge_requests/42#note_987654";
    private static final String NOTES = "/api/v4/projects/group%2Fproject/merge_requests/42/notes/";

    @Mock
    private GitLabGraphQlClientProvider gitLabProvider;

    @Mock
    private GitLabTokenService tokenService;

    @Mock
    private OutboundEgressGuard egressGuard;

    @Mock
    private GitLabDiffVersionReader diffVersions;

    private final ScriptedGitLabRest rest = new ScriptedGitLabRest();
    private GitLabLineNoteWriter channel;
    private HttpGraphQlClient client;

    @BeforeEach
    void setUp() {
        channel = new GitLabLineNoteWriter(
                gitLabProvider,
                rest.builder(),
                tokenService,
                egressGuard,
                ScmFeedbackFixtures.resolver(),
                diffVersions,
                ScmFeedbackFixtures.formatter());
        client = mock(HttpGraphQlClient.class);
        lenient().when(tokenService.resolveServerUrl(1L)).thenReturn(SERVER);
        lenient().when(tokenService.getAccessToken(1L)).thenReturn("glpat-test");
    }

    @Test
    void shouldVerifyEachCopyUsingItsOwnMarker() {
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        var a = ScmFeedbackFixtures.line(
                ScmFeedbackFixtures.anchor("src/Foo.java", 10, null),
                "Quoted <!-- hephaestus-diff-note-ck=wrong -->",
                "<!-- copy-a -->",
                "copy-a");
        var b = ScmFeedbackFixtures.line(
                ScmFeedbackFixtures.anchor("src/Foo.java", 10, null), "Second note", "<!-- copy-b -->", "copy-b");
        stubDiscussions(
                List.of(
                        discussion("gid://Disc/A", List.of(note("gid://gitlab/Note/A", located(a), BOT))),
                        discussion("gid://Disc/B", List.of(note("gid://gitlab/Note/B", located(b), BOT)))),
                BOT);
        assertThat(channel.findPosted(target(), new OnWorkPackage(null, List.of(a, b))))
                .extracting(CopyReceipt::externalRef)
                .containsExactlyInAnyOrder("gid://gitlab/Note/A", "gid://gitlab/Note/B");
        assertThat(rest.calls()).isEmpty();
    }

    @Test
    void shouldChooseTheAuthoredOriginalOverAHumanReplyCopyingItsMarker() {
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        LineNote wanted = item("fix", "result:a:0");
        stubDiscussions(
                List.of(discussion(
                        "gid://Disc/A",
                        List.of(
                                note("gid://Note/A", located(wanted), BOT),
                                note("gid://Note/COPY", located(wanted), PERSON)))),
                BOT);

        assertThat(channel.findPosted(target(), new OnWorkPackage(null, List.of(wanted))))
                .singleElement()
                .satisfies(receipt -> assertThat(receipt.externalRef()).isEqualTo("gid://Note/A"));
    }

    @Test
    void shouldReadOnlyTheBotAuthoredCopyWithItsExpectedMarkerAndKey() {
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        LineNote expectedCopy = ScmFeedbackFixtures.line(
                ScmFeedbackFixtures.anchor("src/Foo.java", 10, null),
                "fix",
                "<!-- hephaestus-diff-note -->",
                "result:a:0");
        stubDiscussions(
                List.of(discussion(
                        "gid://Disc/1",
                        List.of(
                                diffNote("gid://Note/HUMAN", posted(expectedCopy), PERSON, 10, REVIEWED),
                                diffNote("gid://Note/7", posted(expectedCopy), BOT, 10, REVIEWED)))),
                BOT);

        assertThat(channel.findPosted(target(), new OnWorkPackage(null, List.of(expectedCopy))))
                .singleElement()
                .satisfies(receipt -> assertThat(receipt.externalRef()).isEqualTo("gid://Note/7"));
    }

    // The address and credential are resolved before the copy is fenced, so failing to resolve them requests nothing.
    @Test
    void findPostedIsInconclusiveWhenADiscussionHasMoreNotesThanOneLookupReads() {
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        Map<String, Object> crowded = discussion("gid://gitlab/Discussion/1", List.of());
        crowded.put("notes", Map.of("nodes", List.of(), "pageInfo", Map.of("hasNextPage", true)));
        stubDiscussions(List.of(crowded), BOT);

        assertThat(channel.findPosted(target(), new OnWorkPackage(null, List.of(item("fix", "result:a:0")))))
                .isNull();
    }

    // GitLab has no node(id:) root field, so every correction goes through the merge request's notes API.
    @Test
    void shouldWithdrawANativeDiffNoteInPlaceKeepingItsThreadMarkersAndTypedReceipt() {
        String original = posted(item("fix", "result:a:0"));
        Map<String, Object> stored = restNote(5303062, "DiffNote", original, 1);
        var guarded = new AtomicInteger(-1);
        doAnswer(call -> {
                    guarded.set(rest.calls().size());
                    return null;
                })
                .when(egressGuard)
                .requireDeliveryAllowed("gitlab.correct-feedback-copy");
        rest.answer(200, Map.of("id", 1, "username", "bot"));
        rest.answer(200, stored);
        rest.answer(call -> ScriptedGitLabRest.respond(200, storedAs(stored, call)));
        var copy = copy("gid://gitlab/DiffNote/5303062");

        assertThat(channel.retract(target(), List.of(copy))).singleElement().satisfies(update -> {
            assertThat(update.kind()).isEqualTo(CopyUpdateKind.EDITED);
            assertThat(update.copyId()).isEqualTo(copy.copyId());
            assertThat(update.externalRef()).isEqualTo("gid://gitlab/DiffNote/5303062");
            assertThat(update.externalUrl()).isEqualTo(copy.externalUrl());
        });
        assertThat(rest.calls())
                .extracting(ScriptedGitLabRest.Call::method, ScriptedGitLabRest.Call::path)
                .containsExactly(
                        tuple(HttpMethod.GET, "/api/v4/user"),
                        tuple(HttpMethod.GET, NOTES + "5303062"),
                        tuple(HttpMethod.PUT, NOTES + "5303062"));
        assertThat(rest.calls())
                .allSatisfy(call -> assertThat(call.authorization()).isEqualTo("Bearer glpat-test"));
        // Only the body changes: the position, replies, and resolution of the native thread stay GitLab's.
        assertThat(rest.calls().get(2).json())
                .containsExactly(Map.entry(
                        "body",
                        "This feedback was withdrawn.\n" + MARKER + "\n<!-- hephaestus-diff-note-ck=result:a:0 -->"));
        assertThat(guarded.get()).isEqualTo(2);
        verify(gitLabProvider, times(3)).updateRateLimit(eq(1L), any());
    }

    @ParameterizedTest
    @CsvSource({"DiscussionNote,DiscussionNote", "Note,", "Note,DiscussionNote"})
    void shouldWithdrawAnEarlierOverviewCopyThroughTheSameNotesEndpoint(String recorded, @Nullable String answered) {
        Map<String, Object> stored = restNote(987654, answered, posted(item("fix", "result:a:0")), 1);
        rest.answer(200, Map.of("id", 1));
        rest.answer(200, stored);
        rest.answer(call -> ScriptedGitLabRest.respond(200, storedAs(stored, call)));

        assertThat(channel.retract(target(), List.of(copy("gid://gitlab/" + recorded + "/987654"))))
                .singleElement()
                .satisfies(update -> {
                    assertThat(update.kind()).isEqualTo(CopyUpdateKind.EDITED);
                    assertThat(update.externalRef()).isEqualTo("gid://gitlab/" + recorded + "/987654");
                });
        assertThat(rest.calls())
                .extracting(ScriptedGitLabRest.Call::method, ScriptedGitLabRest.Call::path)
                .containsExactly(
                        tuple(HttpMethod.GET, "/api/v4/user"),
                        tuple(HttpMethod.GET, NOTES + "987654"),
                        tuple(HttpMethod.PUT, NOTES + "987654"));
    }

    @Test
    void shouldNeverWriteToANoteItCannotProveIsTheRecordedOwnCopy() {
        String original = posted(item("fix", "result:a:0"));
        Map<String, Object> other = restNote(5303062, "DiffNote", original, 1);
        other.put("id", 5303061);
        Map<String, Object> system = restNote(5303062, "DiffNote", original, 1);
        system.put("system", true);
        Map<String, Object> bodiless = restNote(5303062, "DiffNote", original, 1);
        bodiless.remove("body");
        List<Map<String, Object>> answers = List.of(
                restNote(5303062, "DiffNote", original, 2),
                restNote(5303062, "DiffNote", "Looks good to me", 1),
                restNote(5303062, null, original, 1),
                restNote(5303062, "DiscussionNote", original, 1),
                other,
                system,
                bodiless);
        for (var answer : answers) {
            rest.answer(200, Map.of("id", 1));
            rest.answer(200, answer);
            assertThat(channel.retract(target(), List.of(copy("gid://gitlab/DiffNote/5303062"))))
                    .singleElement()
                    .satisfies(update -> {
                        assertThat(update.kind()).isEqualTo(CopyUpdateKind.UNSUPPORTED);
                        assertThat(update.externalRef()).isEqualTo("gid://gitlab/DiffNote/5303062");
                    });
        }
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
        verifyNoInteractions(egressGuard);
    }

    @Test
    void shouldRequestNothingForAReceiptThatNamesNoGitLabNote() {
        for (String ref : List.of(
                "gid://gitlab/Issue/5303062",
                "gid://gitlab/DiffNote/0",
                "gid://gitlab/DiffNote/5303062/1",
                "gid://gitlab/Discussion/6a9c1750b37d513a43987b574953fceb50b03ce7",
                "5303062")) {
            assertThat(channel.retract(target(), List.of(copy(ref))))
                    .singleElement()
                    .satisfies(update -> assertThat(update.kind()).isEqualTo(CopyUpdateKind.UNSUPPORTED));
        }
        assertThat(rest.calls()).isEmpty();
    }

    @Test
    void shouldSkipAnOversizedNoteIdentityAndContinueWithTheNextCopy() {
        String original = posted(item("fix", "result:a:0"));
        Map<String, Object> stored = restNote(5303062, "DiffNote", original, 1);
        rest.answer(200, Map.of("id", 1));
        rest.answer(200, stored);
        rest.answer(call -> ScriptedGitLabRest.respond(200, storedAs(stored, call)));

        assertThat(channel.retract(
                        target(),
                        List.of(
                                copy("gid://gitlab/DiffNote/999999999999999999999999999999"),
                                copy("gid://gitlab/DiffNote/5303062"))))
                .extracting(CopyUpdateReceipt::kind)
                .containsExactly(CopyUpdateKind.UNSUPPORTED, CopyUpdateKind.EDITED);
        assertThat(rest.calls())
                .extracting(ScriptedGitLabRest.Call::method, ScriptedGitLabRest.Call::path)
                .containsExactly(
                        tuple(HttpMethod.GET, "/api/v4/user"),
                        tuple(HttpMethod.GET, NOTES + "5303062"),
                        tuple(HttpMethod.PUT, NOTES + "5303062"));
    }

    @Test
    void shouldReadOnlyAnAbsentRecordOnTheReadableMergeRequestAsGone() {
        rest.answer(200, Map.of("id", 1));
        rest.answer(404, "{\"message\":\"404 Not found\"}");
        rest.answer(200, Map.of("iid", 42));
        assertThat(channel.retract(target(), List.of(copy("gid://gitlab/DiffNote/5303062"))))
                .singleElement()
                .satisfies(update -> assertThat(update.kind()).isEqualTo(CopyUpdateKind.GONE));
        assertThat(rest.calls())
                .extracting(ScriptedGitLabRest.Call::path)
                .containsExactly(
                        "/api/v4/user", NOTES + "5303062", "/api/v4/projects/group%2Fproject/merge_requests/42");
        for (String unreadable : List.of(
                "{\"message\":\"404 Note Not Found\"}",
                "{\"message\":\"404 Merge Request Not Found\"}",
                "{\"message\":\"404 Project Not Found\"}")) {
            rest.answer(200, Map.of("id", 1));
            rest.answer(404, unreadable);
            assertThat(channel.retract(target(), List.of(copy("gid://gitlab/DiffNote/5303062"))))
                    .singleElement()
                    .satisfies(update -> assertThat(update.kind()).isEqualTo(CopyUpdateKind.TRANSIENT));
        }
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
    }

    @Test
    void shouldNotCallAnUnreadableMergeRequestAMissingCopy() {
        for (int status : List.of(403, 404, 200)) {
            rest.answer(200, Map.of("id", 1));
            rest.answer(404, "{\"message\":\"404 Not found\"}");
            rest.answer(status, Map.of("iid", 43));
            assertThat(channel.retract(target(), List.of(copy("gid://gitlab/DiffNote/5303062"))))
                    .singleElement()
                    .satisfies(update -> assertThat(update.kind()).isEqualTo(CopyUpdateKind.TRANSIENT));
        }
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
        verifyNoInteractions(egressGuard);
    }

    // A correction whose answer was lost is settled by reading it back, never by writing it again.
    @Test
    void shouldConfirmALostCorrectionByReadingItBackWithoutASecondWrite() {
        String original = posted(item("fix", "result:a:0"));
        rest.answer(200, Map.of("id", 1));
        rest.answer(200, restNote(5303062, "DiffNote", original, 1));
        rest.fail(new IllegalStateException("Timeout on blocking read"));
        var copy = copy("gid://gitlab/DiffNote/5303062");

        assertThat(channel.retract(target(), List.of(copy)))
                .singleElement()
                .satisfies(update -> assertThat(update.kind()).isEqualTo(CopyUpdateKind.TRANSIENT));

        rest.answer(200, Map.of("id", 1));
        rest.answer(200, restNote(5303062, "DiffNote", ScmFeedbackFormatter.withdrawnCopyBody(original), 1));
        assertThat(channel.retract(target(), List.of(copy))).singleElement().satisfies(update -> {
            assertThat(update.kind()).isEqualTo(CopyUpdateKind.EDITED);
            assertThat(update.externalRef()).isEqualTo("gid://gitlab/DiffNote/5303062");
        });
        assertThat(rest.calls())
                .filteredOn(call -> HttpMethod.PUT.equals(call.method()))
                .hasSize(1);
        verify(egressGuard, times(1)).requireDeliveryAllowed("gitlab.correct-feedback-copy");
    }

    @Test
    void shouldLeaveACorrectionUnconfirmedUnlessGitLabAnswersTheWithdrawnCopy() {
        String original = posted(item("fix", "result:a:0"));
        List<Function<ScriptedGitLabRest.Call, Map<String, Object>>> readbacks = List.of(
                call -> restNote(5303062, "DiffNote", original, 1),
                call -> {
                    var moved = storedAs(restNote(5303062, "DiffNote", original, 1), call);
                    moved.put("id", 5303099);
                    return moved;
                },
                call -> storedAs(restNote(5303062, "DiffNote", original, 2), call),
                call -> storedAs(restNote(5303062, "DiscussionNote", original, 1), call));
        for (var readback : readbacks) {
            rest.answer(200, Map.of("id", 1));
            rest.answer(200, restNote(5303062, "DiffNote", original, 1));
            rest.answer(call -> ScriptedGitLabRest.respond(200, readback.apply(call)));
            assertThat(channel.retract(target(), List.of(copy("gid://gitlab/DiffNote/5303062"))))
                    .singleElement()
                    .satisfies(update -> assertThat(update.kind()).isEqualTo(CopyUpdateKind.TRANSIENT));
        }
        rest.answer(200, Map.of("id", 1));
        rest.answer(200, restNote(5303062, "DiffNote", original, 1));
        rest.answer(403, "{\"message\":\"403 Forbidden\"}");
        assertThat(channel.retract(target(), List.of(copy("gid://gitlab/DiffNote/5303062"))))
                .singleElement()
                .satisfies(update -> assertThat(update.kind()).isEqualTo(CopyUpdateKind.TRANSIENT));
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).doesNotContain(HttpMethod.POST);
    }

    @Test
    void shouldStopBeforeTheWriteWhenEgressIsSilenced() {
        rest.answer(200, Map.of("id", 1));
        rest.answer(200, restNote(5303062, "DiffNote", posted(item("fix", "result:a:0")), 1));
        doThrow(new OutboundEgressSuppressedException("gitlab.correct-feedback-copy"))
                .when(egressGuard)
                .requireDeliveryAllowed("gitlab.correct-feedback-copy");

        assertThatThrownBy(() -> channel.retract(target(), List.of(copy("gid://gitlab/DiffNote/5303062"))))
                .isInstanceOf(OutboundEgressSuppressedException.class);
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
    }

    @Test
    void shouldRequestNothingMoreOnceTheRateLimitIsCritical() {
        String original = posted(item("fix", "result:a:0"));
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false, true);
        rest.answer(200, Map.of("id", 1));
        rest.answer(200, restNote(5303062, "DiffNote", original, 1));
        rest.answer(429, "{\"message\":\"429 Too Many Requests\"}");

        assertThat(channel.retract(
                        target(),
                        List.of(copy("gid://gitlab/DiffNote/5303062"), copy("gid://gitlab/DiffNote/5303063"))))
                .extracting(CopyUpdateReceipt::kind)
                .containsExactly(CopyUpdateKind.TRANSIENT, CopyUpdateKind.TRANSIENT);
        assertThat(rest.calls())
                .extracting(ScriptedGitLabRest.Call::method, ScriptedGitLabRest.Call::path)
                .containsExactly(
                        tuple(HttpMethod.GET, "/api/v4/user"),
                        tuple(HttpMethod.GET, NOTES + "5303062"),
                        tuple(HttpMethod.PUT, NOTES + "5303062"));
    }

    private static CopyReceipt copy(String ref) {
        String id = ref.matches(".*/[0-9]+$") ? ref.substring(ref.lastIndexOf('/') + 1) : "1";
        return new CopyReceipt(
                UUID.nameUUIDFromBytes(ref.getBytes(StandardCharsets.UTF_8)),
                CopyDisposition.POSTED,
                ref,
                SERVER + "/group/project/-/merge_requests/42#note_" + id,
                true,
                null);
    }

    // A note as GitLab's notes API answers it; a plain Note answers its type as null.
    private static Map<String, Object> restNote(long id, @Nullable String type, String body, long authorId) {
        Map<String, Object> note = new HashMap<>();
        note.put("id", id);
        note.put("type", type);
        note.put("body", body);
        note.put("author", Map.of("id", authorId, "username", authorId == 1 ? "bot" : "person"));
        note.put("system", false);
        note.put("noteable_type", "MergeRequest");
        note.put("noteable_iid", 42);
        if ("DiffNote".equals(type)) {
            note.put(
                    "position",
                    Map.of(
                            "position_type",
                            "text",
                            "base_sha",
                            "a".repeat(40),
                            "head_sha",
                            REVIEWED,
                            "new_path",
                            "src/Foo.java",
                            "new_line",
                            10));
            note.put("resolvable", true);
            note.put("resolved", false);
        }
        return note;
    }

    // GitLab answers an update with the stored note: the same note now holding the requested body.
    private static Map<String, Object> storedAs(Map<String, Object> note, ScriptedGitLabRest.Call call) {
        Map<String, Object> stored = new HashMap<>(note);
        stored.put("body", call.json().get("body"));
        return stored;
    }

    private static LineNote item(String body, String key) {
        return ScmFeedbackFixtures.line(ScmFeedbackFixtures.anchor("src/Foo.java", 10, null), body, MARKER, key);
    }

    private static String posted(LineNote item) {
        return posted(item, target());
    }

    private static String posted(LineNote item, FeedbackTarget target) {
        return ScmFeedbackFixtures.formatter().footer(target.item()).appendLineNotePrompt(PostedText.of(item.body()))
                + "\n" + item.writeMarker() + "\n<!-- hephaestus-diff-note-ck=" + item.writeKey() + " -->";
    }

    private static String located(LineNote item) {
        return "**[View code at line 10]("
                + SERVER
                + "/group/project/-/blob/"
                + REVIEWED
                + "/src/Foo.java#L10)**\n\n"
                + posted(item);
    }

    private GraphQlClient.RequestSpec discussionsSpec() {
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(client.documentName("GetMergeRequestDiscussions")).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        return spec;
    }

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
        // An ordinary comment, as the response answers it: the selected position is explicitly
        // null.
        n.put("position", null);
        return n;
    }

    private static Map<String, Object> diffNote(String id, String body, String authorId, int line, String headSha) {
        Map<String, Object> n = note(id, body, authorId);
        n.put(
                "position",
                Map.of(
                        "newPath",
                        "src/Foo.java",
                        "newLine",
                        line,
                        "diffRefs",
                        Map.of("baseSha", "a".repeat(40), "headSha", headSha, "startSha", "b".repeat(40))));
        return n;
    }

    private static Map<String, Object> discussion(String id, List<Map<String, Object>> notes) {
        Map<String, Object> disc = new HashMap<>();
        disc.put("id", id);
        disc.put("notes", Map.of("nodes", new ArrayList<>(notes), "pageInfo", Map.of("hasNextPage", false)));
        return disc;
    }

    private static final FeedbackTarget TARGET = ScmFeedbackFixtures.target(
            new IntegrationRef(IntegrationKind.GITLAB, 1L, null), "group/project!42", REVIEWED);

    private static FeedbackTarget target() {
        return TARGET;
    }
}
