package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ThreadChange;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpMethod;

/** The GitLab side of the thread resolution contract: read the discussion, change it only when needed, confirm it. */
class GitLabThreadResolutionTest extends BaseUnitTest {
    private static final FeedbackTarget TARGET = ScmFeedbackFixtures.target(
            new IntegrationRef(IntegrationKind.GITLAB, 1L, null),
            "group/project!42",
            "3f2b9c1d0e8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c");
    private static final String DISCUSSION = "6a9c1750b37d513a43987b574953fceb50b03ce7";
    private static final String PATH = "/api/v4/projects/group%2Fproject/merge_requests/42/discussions/" + DISCUSSION;
    private static final long BOT = 1;

    @Mock
    private GitLabGraphQlClientProvider gitLabProvider;

    @Mock
    private GitLabTokenService tokenService;

    @Mock
    private OutboundEgressGuard egressGuard;

    @Mock
    private GitLabDiffVersionReader diffVersions;

    private final ScriptedGitLabRest rest = new ScriptedGitLabRest();
    private GitLabLineNoteWriter writer;

    @BeforeEach
    void setUp() {
        writer = new GitLabLineNoteWriter(
                gitLabProvider,
                () -> rest.builder(),
                tokenService,
                egressGuard,
                ScmFeedbackFixtures.resolver(),
                diffVersions,
                ScmFeedbackFixtures.formatter());
        lenient().when(tokenService.resolveServerUrl(1L)).thenReturn("https://gitlab.example.com");
        lenient().when(tokenService.getAccessToken(1L)).thenReturn("glpat-test");
        lenient().when(egressGuard.deliveryAllowed(anyString())).thenReturn(true);
    }

    @Test
    void shouldResolveAnOwnedDiscussionAndConfirmItsState() {
        rest.answer(200, discussion(false, BOT));
        rest.answer(200, discussion(true, BOT));

        assertThat(writer.resolveThread(TARGET, copy(), true, "1").outcome()).isEqualTo(ThreadChange.Outcome.DONE);
        var put = rest.calls().getLast();
        assertThat(put.method()).isEqualTo(HttpMethod.PUT);
        assertThat(put.path()).isEqualTo(PATH);
        assertThat(put.json()).containsEntry("resolved", true);
        verify(egressGuard).deliveryAllowed("gitlab.resolve-feedback-thread");
    }

    @Test
    void shouldReopenADiscussionItResolved() {
        rest.answer(200, discussion(true, BOT));
        rest.answer(200, discussion(false, BOT));

        assertThat(writer.resolveThread(TARGET, copy(), false, "1").outcome()).isEqualTo(ThreadChange.Outcome.DONE);
        assertThat(rest.calls().getLast().json()).containsEntry("resolved", false);
        verify(egressGuard).deliveryAllowed("gitlab.reopen-feedback-thread");
    }

    @Test
    void shouldCountADiscussionAlreadyInTheAskedStateAsDoneWithoutWriting() {
        rest.answer(200, discussion(true, BOT));

        assertThat(writer.resolveThread(TARGET, copy(), true, "1").outcome()).isEqualTo(ThreadChange.Outcome.DONE);
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
        verify(egressGuard, never()).deliveryAllowed(anyString());
    }

    @Test
    void shouldNotChangeADiscussionThatAnotherAuthorStarted() {
        rest.answer(200, discussion(false, 2));

        assertThat(writer.resolveThread(TARGET, copy(), true, "1").outcome()).isEqualTo(ThreadChange.Outcome.NOT_OWNED);
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
    }

    @Test
    void shouldNotResolveADiscussionWhereAPersonRepliedAfterTheNote() {
        rest.answer(200, replied(false));

        assertThat(writer.resolveThread(TARGET, copy(), true, "1").outcome()).isEqualTo(ThreadChange.Outcome.REPLIED);
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
    }

    @Test
    void shouldWriteNothingWhileSilentModeHoldsWrites() {
        when(egressGuard.deliveryAllowed(anyString())).thenReturn(false);
        rest.answer(200, discussion(false, BOT));

        assertThat(writer.resolveThread(TARGET, copy(), true, "1").outcome()).isEqualTo(ThreadChange.Outcome.DEFERRED);
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
    }

    @Test
    void shouldNeitherReopenNorClaimADiscussionThatAPersonResolved() {
        rest.answer(200, discussion(true, BOT, 2));
        rest.answer(200, discussion(true, BOT, 2));

        assertThat(writer.resolveThread(TARGET, copy(), false, "1").outcome()).isEqualTo(ThreadChange.Outcome.TAKEN);
        assertThat(writer.resolveThread(TARGET, copy(), true, "1").outcome()).isEqualTo(ThreadChange.Outcome.TAKEN);
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
    }

    @Test
    void shouldReadAgainWhenTheDiscussionReadFails() {
        rest.answer(503, Map.of("message", "Service unavailable"));

        assertThat(writer.resolveThread(TARGET, copy(), true, "1").outcome()).isEqualTo(ThreadChange.Outcome.TRANSIENT);
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
    }

    @Test
    void shouldResolveADiscussionWhoseFirstNoteAloneIsResolved() {
        rest.answer(200, withNote(discussion(true, BOT), 5303064, BOT, false));
        rest.answer(200, withNote(discussion(true, BOT), 5303064, BOT, true));

        assertThat(writer.resolveThread(TARGET, copy(), true, "1").outcome()).isEqualTo(ThreadChange.Outcome.DONE);
        assertThat(rest.calls().getLast().method()).isEqualTo(HttpMethod.PUT);
    }

    @Test
    void shouldNotResolveADiscussionWhereAPersonRepliedBeforeALaterNoteOfTheAccount() {
        rest.answer(200, withNote(replied(false), 5303065, BOT, false));

        assertThat(writer.resolveThread(TARGET, copy(), true, "1").outcome()).isEqualTo(ThreadChange.Outcome.REPLIED);
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
    }

    /** The discussion with one more resolvable note by {@code author}, resolved or not. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withNote(
            Map<String, Object> discussion, long id, long author, boolean resolved) {
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", id);
        note.put("type", "DiffNote");
        note.put("body", "A later note.");
        note.put("system", false);
        note.put("author", Map.of("id", author, "username", "bot"));
        note.put("resolvable", true);
        note.put("resolved", resolved);
        if (resolved) note.put("resolved_by", Map.of("id", author, "username", "bot"));
        var notes = new ArrayList<>((List<Map<String, Object>>) discussion.get("notes"));
        notes.add(note);
        return Map.of("id", DISCUSSION, "individual_note", false, "notes", notes);
    }

    @Test
    void shouldReportARemovedDiscussionGoneOnlyWhenItsMergeRequestIsReadable() {
        rest.answer(404, "{\"message\":\"404 Not found\"}");
        rest.answer(200, Map.of("iid", 42));

        assertThat(writer.resolveThread(TARGET, copy(), true, "1").outcome()).isEqualTo(ThreadChange.Outcome.GONE);
    }

    @Test
    void shouldLeaveAnUnconfirmedChangeTransient() {
        rest.answer(200, discussion(false, BOT));
        rest.answer(200, discussion(false, BOT));

        assertThat(writer.resolveThread(TARGET, copy(), true, "1").outcome()).isEqualTo(ThreadChange.Outcome.TRANSIENT);
    }

    @Test
    void shouldActAsTheAccountOfItsToken() {
        rest.answer(200, Map.of("id", 77));

        assertThat(writer.actor(TARGET)).isEqualTo("77");
    }

    @Test
    void shouldDeclareWhetherItReopensThreads() {
        // GitLab does not name the account that reopened a discussion.
        assertThat(writer.reopensThreads()).isFalse();
    }

    private static CopyReceipt copy() {
        return new CopyReceipt(
                UUID.randomUUID(),
                CopyDisposition.POSTED,
                "gid://gitlab/DiffNote/5303062",
                null,
                true,
                null,
                "gid://gitlab/Discussion/" + DISCUSSION);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> replied(boolean resolved) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("id", 5303063);
        reply.put("type", "DiffNote");
        reply.put("body", "I think this is fine.");
        reply.put("system", false);
        reply.put("author", Map.of("id", 2, "username", "person"));
        reply.put("resolvable", true);
        reply.put("resolved", resolved);
        var notes = new ArrayList<>(
                (List<Map<String, Object>>) discussion(resolved, BOT).get("notes"));
        notes.add(reply);
        return Map.of("id", DISCUSSION, "individual_note", false, "notes", notes);
    }

    private static Map<String, Object> discussion(boolean resolved, long author) {
        return discussion(resolved, author, author);
    }

    private static Map<String, Object> discussion(boolean resolved, long author, long resolver) {
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", 5303062);
        note.put("type", "DiffNote");
        note.put("body", "Keep the failure cause.");
        note.put("system", false);
        note.put("author", Map.of("id", author, "username", "bot"));
        note.put("resolvable", true);
        note.put("resolved", resolved);
        if (resolved) note.put("resolved_by", Map.of("id", resolver, "username", "bot"));
        return Map.of("id", DISCUSSION, "individual_note", false, "notes", List.of(note));
    }
}
