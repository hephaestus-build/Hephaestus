package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Two line notes that cite the same problem are a repeat, found by the problem, and the draft still stands. */
@Tag("unit")
class ReviewOnWorkRepeatsTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final JsonNode LINE = MAPPER.createObjectNode().put("anchorable", true);
    private static final Map<String, Evidence.Result> RESULTS = Map.of(
            "r1",
            new Evidence.Result(
                    "handles-errors",
                    false,
                    "NOT_MET",
                    List.of(
                            new Evidence.Problem("p1", List.of(LINE, LINE)),
                            new Evidence.Problem("p2", List.of(LINE)))));
    private static final String FIRST =
            "The new catch returns null and records nothing. Record or rethrow the failure so it stays visible.";

    @Test
    void shouldListALineNoteThatCitesAProblemThatAnEarlierNoteCitesWithoutRefusingTheDraft() {
        var draft = draft();
        note(draft, "p1", 0, FIRST);
        note(draft, "p2", 0, "This branch drops the error code. Pass it on so the caller can tell the failures apart.");
        note(draft, "p1", 1, "The retry swallows the same failure. Record it here too so the cause is not lost.");

        var read = ReviewOnWork.read(draft, RESULTS, true, Map.of());

        assertThat(read.errors()).isEmpty();
        assertThat(ReviewOnWork.repeats(Objects.requireNonNull(read.review())))
                .containsExactly(new ReviewOnWork.Repeat("inline #3", List.of("p1"), "inline #1", FIRST));
    }

    @Test
    void shouldListNoRepeatWhenEachNoteCitesItsOwnProblemAndTheSummaryCitesThemAll() {
        var draft = draft();
        draft.putObject("summary")
                .put(
                        "body",
                        "Two failures are dropped without a trace. Record both so a failing fetch can be diagnosed.")
                .putArray("basedOn")
                .add("p1")
                .add("p2");
        note(draft, "p1", 0, FIRST);
        note(draft, "p2", 0, "This branch drops the error code. Pass it on so the caller can tell the failures apart.");

        var read = ReviewOnWork.read(draft, RESULTS, true, Map.of());

        assertThat(read.errors()).isEmpty();
        assertThat(ReviewOnWork.repeats(Objects.requireNonNull(read.review()))).isEmpty();
    }

    @Test
    void shouldQuoteTheOpeningWordsOfALongNoteOnOneLine() {
        String quote = ReviewOnWork.quote("Line one\nline two " + "x".repeat(200));

        assertThat(quote).startsWith("Line one line two x").endsWith("…").hasSizeLessThanOrEqualTo(120);
    }

    private static ObjectNode draft() {
        var draft = MAPPER.createObjectNode();
        draft.putArray("decisions").addObject().put("resultId", "r1").put("disposition", "RAISE");
        draft.putNull("summary");
        draft.putArray("inline");
        return draft;
    }

    private static void note(ObjectNode draft, String problem, int citation, String body) {
        var note = ((ArrayNode) draft.path("inline")).addObject();
        note.putArray("basedOn").add(problem);
        note.put("body", body);
        note.putObject("anchor").put("problemId", problem).put("citationIndex", citation);
    }
}
