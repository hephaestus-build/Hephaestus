package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.FeedbackReadbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Tag("unit")
class GitLabFeedbackCopyReaderTest {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion
                    .withValueInclusion(JsonInclude.Include.NON_NULL)
                    .withContentInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private static final UUID COPY = UUID.fromString("32000000-0000-4000-8000-000000000001");
    private static final String MARKER = "<!-- hephaestus-feedback-copy:" + COPY + " -->";
    private static final String HEAD = "b".repeat(40);
    private static final String TIME = "2026-01-01T00:00:00Z";

    @Test
    void shouldReadActualRenderingAndTheRestRangeOfTheSameObservedNote() {
        var actual = read(data(note()), rest());
        assertThat(actual.status()).isEqualTo(CopyReadback.Status.FOUND);
        assertThat(actual.body()).isEqualTo("Actual provider text\n" + MARKER);
        assertThat(actual.renderedBody()).isEqualTo("<p>Actual rendered text</p>");
        assertThat(actual.anchor()).isEqualTo(new FeedbackAnchor("src/actual.ts", 12, 14, CitationSide.NEW));
    }

    @Test
    void shouldRefusePositionFactsFromAnotherReadOfTheNote() {
        var changed = rest().put("updated_at", "2026-01-01T00:00:01Z");
        assertThat(read(data(note()), changed)).isEqualTo(CopyReadback.refused(COPY, CopyReadback.Status.UNAVAILABLE));
        changed = rest().put("body", "A concurrent edit");
        assertThat(read(data(note()), changed).status()).isEqualTo(CopyReadback.Status.UNAVAILABLE);
        changed = rest();
        ((ObjectNode) changed.path("position")).put("head_sha", "c".repeat(40));
        assertThat(read(data(note()), changed).status()).isEqualTo(CopyReadback.Status.UNAVAILABLE);
    }

    @Test
    void shouldProtectHumanEditsAndRepliesAndRefuseForeignMarkers() {
        var note = note();
        note.putObject("lastEditedBy").put("id", "gid://gitlab/User/2");
        assertThat(read(data(note), rest())).isEqualTo(CopyReadback.refused(COPY, CopyReadback.Status.HUMAN_EDITED));
        note = note();
        ((ArrayNode) note.path("discussion").path("notes").path("nodes"))
                .addObject()
                .put("id", "gid://gitlab/Note/2");
        assertThat(read(data(note), rest()).status()).isEqualTo(CopyReadback.Status.HUMAN_EDITED);
        note = note().put("body", "No retained marker");
        assertThat(read(data(note), rest()).status()).isEqualTo(CopyReadback.Status.NOT_OWNED);
    }

    @Test
    void shouldProveAbsenceOnlyAfterTheWholeBoundedConnectionWasRead() {
        var data = data(note());
        ((ObjectNode) data.path("project").path("mergeRequest").path("notes")).putArray("nodes");
        assertThat(read(data, rest()).status()).isEqualTo(CopyReadback.Status.GONE);
        ((ObjectNode) data.path("project").path("mergeRequest").path("notes").path("pageInfo"))
                .put("hasPreviousPage", true)
                .put("startCursor", "same-page");
        assertThat(read(data, rest()).status()).isEqualTo(CopyReadback.Status.UNAVAILABLE);
    }

    @Test
    void shouldReadAnIssueNoteWithoutAReviewPosition() {
        var note = note();
        note.putNull("position");
        var data = data(note);
        ((ObjectNode) data.path("project")).set("issue", data.path("project").path("mergeRequest"));
        assertThat(read(data, rest(), true)).satisfies(actual -> {
            assertThat(actual.status()).isEqualTo(CopyReadback.Status.FOUND);
            assertThat(actual.renderedBody()).isEqualTo("<p>Actual rendered text</p>");
            assertThat(actual.anchor()).isNull();
        });
    }

    private static CopyReadback read(ObjectNode data, ObjectNode rest) {
        return read(data, rest, false);
    }

    private static CopyReadback read(ObjectNode data, ObjectNode rest, boolean issue) {
        var provider = mock(GitLabGraphQlClientProvider.class);
        when(provider.forScope(1L)).thenReturn(FeedbackReadbackFixtures.client("gitlab", data));
        var tokens = mock(GitLabTokenService.class);
        when(tokens.getAccessToken(1L)).thenReturn("synthetic-test-credential");
        var client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(rest.toString())
                        .build()));
        var reader =
                new GitLabFeedbackCopyReader(provider, ScmFeedbackFixtures.resolver(), tokens, () -> client, MAPPER);
        var target = ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITLAB, 1, "https://scm.example.test"),
                issue ? "group/repo#3" : "group/repo!3",
                HEAD);
        return reader.read(
                        target,
                        List.of(new CopyReadRequest(
                                COPY,
                                "gid://gitlab/Note/1",
                                "gid://gitlab/Discussion/1",
                                MARKER,
                                new FeedbackAnchor("src/expected.ts", 1, 1, CitationSide.OLD))))
                .getFirst();
    }

    private static ObjectNode data(ObjectNode note) {
        var data = MAPPER.createObjectNode();
        data.putObject("currentUser").put("id", "gid://gitlab/User/1");
        var notes = data.putObject("project").putObject("mergeRequest").putObject("notes");
        notes.putObject("pageInfo").put("hasPreviousPage", false).putNull("startCursor");
        notes.putArray("nodes").add(note);
        return data;
    }

    private static ObjectNode note() {
        var note = MAPPER.createObjectNode()
                .put("id", "gid://gitlab/Note/1")
                .put("body", "Actual provider text\n" + MARKER)
                .put("bodyHtml", "<p>Actual rendered text</p>")
                .put("updatedAt", TIME)
                .put("system", false)
                .put("internal", false)
                .put("resolved", false);
        note.putObject("author").put("id", "gid://gitlab/User/1");
        note.putNull("lastEditedBy");
        var discussion = note.putObject("discussion")
                .put("id", "gid://gitlab/Discussion/1")
                .put("resolved", false);
        var notes = discussion.putObject("notes");
        notes.putObject("pageInfo").put("hasNextPage", false);
        notes.putArray("nodes").addObject().put("id", "gid://gitlab/Note/1");
        note.putObject("position").putObject("diffRefs").put("headSha", HEAD);
        return note;
    }

    private static ObjectNode rest() {
        var note = MAPPER.createObjectNode()
                .put("body", "Actual provider text\n" + MARKER)
                .put("updated_at", TIME);
        var position = note.putObject("position")
                .put("head_sha", HEAD)
                .put("new_path", "src/actual.ts")
                .put("old_path", "src/actual.ts")
                .put("new_line", 14);
        position.putNull("old_line");
        var range = position.putObject("line_range");
        range.putObject("start").put("new_line", 12);
        range.putObject("end").put("new_line", 14);
        return note;
    }
}
