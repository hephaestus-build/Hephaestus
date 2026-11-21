package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
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
    }

    @Test
    void shouldResolveAnOwnedDiscussionAndConfirmItsState() {
        rest.answer(200, Map.of("id", BOT));
        rest.answer(200, discussion(false, BOT));
        rest.answer(200, discussion(true, BOT));

        assertThat(writer.resolveThread(TARGET, copy(), true).kind()).isEqualTo(CopyUpdateKind.EDITED);
        var put = rest.calls().getLast();
        assertThat(put.method()).isEqualTo(HttpMethod.PUT);
        assertThat(put.path()).isEqualTo(PATH);
        assertThat(put.json()).containsEntry("resolved", true);
        verify(egressGuard).requireDeliveryAllowed("gitlab.resolve-feedback-thread");
    }

    @Test
    void shouldReopenADiscussionItResolved() {
        rest.answer(200, Map.of("id", BOT));
        rest.answer(200, discussion(true, BOT));
        rest.answer(200, discussion(false, BOT));

        assertThat(writer.resolveThread(TARGET, copy(), false).kind()).isEqualTo(CopyUpdateKind.EDITED);
        assertThat(rest.calls().getLast().json()).containsEntry("resolved", false);
        verify(egressGuard).requireDeliveryAllowed("gitlab.reopen-feedback-thread");
    }

    @Test
    void shouldCountADiscussionAlreadyInTheAskedStateAsDoneWithoutWriting() {
        rest.answer(200, Map.of("id", BOT));
        rest.answer(200, discussion(true, BOT));

        assertThat(writer.resolveThread(TARGET, copy(), true).kind()).isEqualTo(CopyUpdateKind.EDITED);
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
        verify(egressGuard, never()).requireDeliveryAllowed(anyString());
    }

    @Test
    void shouldNotChangeADiscussionThatAnotherAuthorStarted() {
        rest.answer(200, Map.of("id", BOT));
        rest.answer(200, discussion(false, 2));

        assertThat(writer.resolveThread(TARGET, copy(), true).kind()).isEqualTo(CopyUpdateKind.UNSUPPORTED);
        assertThat(rest.calls()).extracting(ScriptedGitLabRest.Call::method).containsOnly(HttpMethod.GET);
    }

    @Test
    void shouldReportARemovedDiscussionGoneOnlyWhenItsMergeRequestIsReadable() {
        rest.answer(200, Map.of("id", BOT));
        rest.answer(404, "{\"message\":\"404 Not found\"}");
        rest.answer(200, Map.of("iid", 42));

        assertThat(writer.resolveThread(TARGET, copy(), true).kind()).isEqualTo(CopyUpdateKind.GONE);
    }

    @Test
    void shouldLeaveAnUnconfirmedChangeTransient() {
        rest.answer(200, Map.of("id", BOT));
        rest.answer(200, discussion(false, BOT));
        rest.answer(200, discussion(false, BOT));

        assertThat(writer.resolveThread(TARGET, copy(), true).kind()).isEqualTo(CopyUpdateKind.TRANSIENT);
    }

    @Test
    void shouldActAsTheAccountOfItsToken() {
        rest.answer(200, Map.of("id", 77));

        assertThat(writer.actor(TARGET)).isEqualTo("77");
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

    private static Map<String, Object> discussion(boolean resolved, long author) {
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", 5303062);
        note.put("type", "DiffNote");
        note.put("body", "Keep the failure cause.");
        note.put("system", false);
        note.put("author", Map.of("id", author, "username", "bot"));
        note.put("resolvable", true);
        note.put("resolved", resolved);
        return Map.of("id", DISCUSSION, "individual_note", false, "notes", List.of(note));
    }
}
