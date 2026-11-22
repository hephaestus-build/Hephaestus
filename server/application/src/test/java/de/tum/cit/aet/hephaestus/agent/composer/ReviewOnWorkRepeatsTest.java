package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A repeat is found by the problem, and the draft still stands: two line notes of the draft that cite the same problem,
 * or an entry that cites a problem that an open note of an earlier review on the work still raises.
 */
@Tag("unit")
class ReviewOnWorkRepeatsTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final JsonNode LINE = MAPPER.createObjectNode().put("anchorable", true);
    private static final String WORK_URL = "https://github.com/acme/app/pull/7";
    private static final String DROPPED = "key:dropped-failure";
    private static final Map<String, Evidence.Result> RESULTS = Map.of(
            "r1",
            new Evidence.Result(
                    "handles-errors",
                    false,
                    "NOT_MET",
                    List.of(
                            new Evidence.Problem("p1", List.of(LINE, LINE), DROPPED),
                            new Evidence.Problem("p2", List.of(LINE), "scheme-1:" + "a".repeat(64)),
                            new Evidence.Problem("p3", List.of(LINE), null))));
    private static final String FIRST =
            "The new catch returns null and records nothing. Record or rethrow the failure so it stays visible.";
    private static final String EARLIER = "This catch hides the failure. Record it so the cause is not lost.";

    @Test
    void shouldListALineNoteThatCitesAProblemThatAnEarlierNoteCitesWithoutRefusingTheDraft() {
        var draft = draft();
        note(draft, "p1", 0, FIRST);
        note(draft, "p2", 0, "This branch drops the error code. Pass it on so the caller can tell the failures apart.");
        note(draft, "p1", 1, "The retry swallows the same failure. Record it here too so the cause is not lost.");

        var read = ReviewOnWork.read(draft, RESULTS, true, Map.of());

        assertThat(read.errors()).isEmpty();
        assertThat(ReviewOnWork.repeats(Objects.requireNonNull(read.review()), List.of(), RESULTS))
                .containsExactly(new ReviewOnWork.Repeat("inline #3", List.of("p1"), "inline #1", null, FIRST));
    }

    @Test
    void shouldListNoRepeatWhenEachNoteCitesItsOwnProblemAndTheSummaryCitesThemAll() {
        var draft = draft();
        summary(draft, "p1", "p2");
        note(draft, "p1", 0, FIRST);
        note(draft, "p2", 0, "This branch drops the error code. Pass it on so the caller can tell the failures apart.");

        var read = ReviewOnWork.read(draft, RESULTS, true, Map.of());

        assertThat(read.errors()).isEmpty();
        assertThat(ReviewOnWork.repeats(Objects.requireNonNull(read.review()), List.of(), RESULTS))
                .isEmpty();
    }

    @Test
    void shouldListTheSummaryAndALineNoteWhenAStandingEarlierNoteCitesTheirProblem() {
        var history = history();
        var open = entry(history, false);
        copy(open, "POSTED", false, cite("handles-errors", DROPPED));
        var draft = draft();
        summary(draft, "p1");
        note(draft, "p1", 0, FIRST);

        var earlier = earlierNotes(history);
        var read = ReviewOnWork.read(draft, RESULTS, true, Map.of());

        var note = new ReviewOnWork.EarlierNote(
                "src/App.java",
                15,
                15,
                WORK_URL + "#discussion_r1",
                EARLIER,
                Set.of(new ReviewOnWork.Cite("handles-errors", DROPPED)));
        assertThat(earlier).containsExactly(note);
        assertThat(read.errors()).isEmpty();
        assertThat(ReviewOnWork.repeats(Objects.requireNonNull(read.review()), earlier, RESULTS))
                .containsExactly(
                        new ReviewOnWork.Repeat("summary", List.of("p1"), null, note, EARLIER),
                        new ReviewOnWork.Repeat("inline #1", List.of("p1"), null, note, EARLIER));
    }

    /**
     * The capture leaves Hephaestus's own notes and threads out, so whether a person resolved an earlier note's thread
     * is unknown: the receipt says the note raised the problem, never that it still raises it.
     */
    @Test
    void shouldSayTheEarlierNoteRaisedTheProblemWhenItsThreadStateIsUnknown() {
        var history = history();
        copy(entry(history, false), "POSTED", false, cite("handles-errors", DROPPED));
        var draft = draft();
        note(draft, "p1", 0, FIRST);
        var read = ReviewOnWork.read(draft, RESULTS, true, Map.of());

        var receipt = MAPPER.readTree(CompositionSessions.checkedReceipt(
                "draft-1",
                ReviewOnWork.repeats(Objects.requireNonNull(read.review()), earlierNotes(history), RESULTS)));

        assertThat(receipt.path("repeats")
                        .get(0)
                        .path("earlierNote")
                        .path("link")
                        .asString())
                .isEqualTo(WORK_URL + "#discussion_r1");
        assertThat(receipt.path("next").asString())
                .contains("an earlier note on this work raised")
                .contains("whether a person resolved its thread is not known")
                .doesNotContain("still raises");
    }

    @Test
    void shouldShowTheModelEarlierNotesWithoutTheirLinkOrCitedProblems() {
        var history = history();
        var entry = entry(history, false);
        entry.put("id", "70000000-0000-4000-8000-000000000001");
        copy(entry, "POSTED", false, cite("handles-errors", DROPPED));

        var shown = ReviewOnWork.priorPublicFeedback(ReviewOnWork.publicHistory(history), work(), null, null);

        var note = shown.feedback().getFirst().path("notes").get(0);
        assertThat(note.path("body").asString()).isEqualTo(EARLIER);
        assertThat(note.has("externalUrl")).isFalse();
        assertThat(note.has("cites")).isFalse();
    }

    @Test
    void shouldNotCountAnEarlierNoteWhenItIsClosedAsFixedRetractedOrWithdrawn() {
        var history = history();
        copy(entry(history, false), "POSTED", true, cite("handles-errors", DROPPED));
        copy(entry(history, false), "RETRACTED", false, cite("handles-errors", DROPPED));
        copy(entry(history, true), "POSTED", false, cite("handles-errors", DROPPED));
        var draft = draft();
        note(draft, "p1", 0, FIRST);

        var earlier = earlierNotes(history);
        var read = ReviewOnWork.read(draft, RESULTS, true, Map.of());

        assertThat(earlier).isEmpty();
        assertThat(ReviewOnWork.repeats(Objects.requireNonNull(read.review()), earlier, RESULTS))
                .isEmpty();
    }

    @Test
    void shouldNotMatchWhenTheEarlierNoteCitesTheSameKeyForAnotherPractice() {
        var history = history();
        copy(entry(history, false), "POSTED", false, cite("names-things", DROPPED));
        var draft = draft();
        note(draft, "p1", 0, FIRST);

        var read = ReviewOnWork.read(draft, RESULTS, true, Map.of());

        assertThat(ReviewOnWork.repeats(Objects.requireNonNull(read.review()), earlierNotes(history), RESULTS))
                .isEmpty();
    }

    @Test
    void shouldNeverMatchWhenAProblemHasNoIdentity() {
        var history = history();
        copy(entry(history, false), "POSTED", false, cite("handles-errors", DROPPED));
        copy(entry(history, false), "POSTED", false, MAPPER.createObjectNode().put("practiceSlug", "handles-errors"));
        var draft = draft();
        note(draft, "p3", 0, "This catch also drops the failure. Record it so a failing fetch can be diagnosed.");

        var earlier = earlierNotes(history);
        var read = ReviewOnWork.read(draft, RESULTS, true, Map.of());

        assertThat(earlier).hasSize(1);
        assertThat(read.errors()).isEmpty();
        assertThat(ReviewOnWork.repeats(Objects.requireNonNull(read.review()), earlier, RESULTS))
                .isEmpty();
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

    private static void summary(ObjectNode draft, String... problems) {
        var basedOn = draft.putObject("summary")
                .put("body", "Failures are dropped without a trace. Record them so a failing fetch can be diagnosed.")
                .putArray("basedOn");
        for (String problem : problems) basedOn.add(problem);
    }

    private static void note(ObjectNode draft, String problem, int citation, String body) {
        var note = ((ArrayNode) draft.path("inline")).addObject();
        note.putArray("basedOn").add(problem);
        note.put("body", body);
        note.putObject("anchor").put("problemId", problem).put("citationIndex", citation);
    }

    /** A public history read of this work, as the server answers it. */
    private static ObjectNode history() {
        var read = MAPPER.createObjectNode();
        var history = read.putObject("history");
        history.putObject("item").put("kind", "scm.pull_request").put("url", WORK_URL);
        history.putArray("feedback");
        return read;
    }

    private static ObjectNode entry(ObjectNode read, boolean withdrawn) {
        var entry = ((ArrayNode) read.path("history").path("feedback")).addObject();
        entry.put("destination", "ON_WORK");
        entry.put("withdrawn", withdrawn);
        entry.putArray("copies");
        return entry;
    }

    private static void copy(ObjectNode entry, String state, boolean closedAsFixed, ObjectNode cite) {
        var copy = ((ArrayNode) entry.path("copies")).addObject();
        copy.put("kind", "LINE_NOTE");
        copy.put("body", EARLIER);
        copy.put("path", "src/App.java");
        copy.put("startLine", 15);
        copy.put("endLine", 15);
        copy.put("externalUrl", WORK_URL + "#discussion_r1");
        copy.put("state", state);
        copy.put("closedAsFixed", closedAsFixed);
        copy.putArray("cites").add(cite);
    }

    private static ObjectNode cite(String practiceSlug, String identity) {
        return MAPPER.createObjectNode().put("practiceSlug", practiceSlug).put("identity", identity);
    }

    private static String work() {
        return Objects.requireNonNull(ReviewOnWork.workIdentity(
                MAPPER.getNodeFactory().stringNode("scm.pull_request"),
                MAPPER.getNodeFactory().stringNode(WORK_URL)));
    }

    private static List<ReviewOnWork.EarlierNote> earlierNotes(ObjectNode read) {
        return ReviewOnWork.earlierNotes(
                ReviewOnWork.publicHistory(read),
                ReviewOnWork.workIdentity(
                        MAPPER.getNodeFactory().stringNode("scm.pull_request"),
                        MAPPER.getNodeFactory().stringNode(WORK_URL)));
    }
}
