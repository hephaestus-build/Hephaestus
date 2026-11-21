package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A raise over still-current advice on its practice is asked back, unless it says what is new since that advice. */
@Tag("unit")
class ReviewOnWorkPriorAdviceTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final String SUMMARY = "The new catch returns null and records nothing, so the failure that made "
            + "the fetch fail is dropped. Record or rethrow it so the failure stays visible.";
    private static final Map<String, Evidence.Result> RESULTS =
            Map.of("r1", new Evidence.Result("handles-errors", false, "NOT_MET", List.of()));

    @Test
    void shouldAskARaiseBackWhenStillCurrentAdviceOnItsPracticeExists() {
        var read = ReviewOnWork.read(raise(null), RESULTS, false, advice(true, "handles-errors"));

        assertThat(read.review()).isNull();
        assertThat(read.errors())
                .singleElement()
                .asString()
                .contains("feedback:w1 already gave still-current advice on handles-errors")
                .contains("ALREADY_SAID or NO_MATERIAL_CHANGE")
                .contains("newSince");
    }

    @Test
    void shouldAcceptARaiseWhenItSaysWhatIsNewSinceTheAdvice() {
        var read = ReviewOnWork.read(
                raise("This is a catch that the change added after the advice, not the one it named."),
                RESULTS,
                false,
                advice(true, "handles-errors"));

        assertThat(read.errors()).isEmpty();
        assertThat(read.review()).isNotNull();
    }

    @Test
    void shouldAcceptARaiseWhenTheAdviceIsOnAnotherPracticeOrNoLongerCurrent() {
        assertThat(ReviewOnWork.read(raise(null), RESULTS, false, advice(true, "describes-changes"))
                        .errors())
                .isEmpty();
        assertThat(ReviewOnWork.read(raise(null), RESULTS, false, advice(false, "handles-errors"))
                        .errors())
                .isEmpty();
    }

    @Test
    void shouldRefuseNewSinceWhenThereIsNoAdviceToAnswerOrTheResultIsWithheld() {
        assertThat(ReviewOnWork.read(raise("Something new."), RESULTS, false, Map.of())
                        .errors())
                .singleElement()
                .asString()
                .contains("newSince goes only with a raise over still-current advice");

        var withheld = MAPPER.createObjectNode();
        withheld.putArray("decisions")
                .addObject()
                .put("resultId", "r1")
                .put("disposition", "ALREADY_SAID")
                .put("newSince", "Something new.")
                .putArray("witnessIds")
                .add("feedback:w1");
        withheld.putNull("summary");
        withheld.putArray("inline");
        assertThat(ReviewOnWork.read(withheld, RESULTS, false, advice(true, "handles-errors"))
                        .errors())
                .singleElement()
                .asString()
                .contains("newSince goes only with RAISE");
    }

    @Test
    void shouldRaiseAProblemAgainWhenHephaestusClosedTheNoteThatGaveItsAdviceAsFixed() {
        var open = ReviewOnWork.priorAdviceWitnesses(
                ReviewOnWork.priorPublicFeedback(history(false), WORK, CAPTURED, CAPTURED)
                        .feedback(),
                MAPPER.createArrayNode());
        assertThat(ReviewOnWork.read(raise(null), RESULTS, false, open).errors())
                .as("an open note still stands as advice")
                .isNotEmpty();

        // On GitLab the thread stays resolved when the problem comes back, so its note must not hold the problem back.
        var closed = ReviewOnWork.priorAdviceWitnesses(
                ReviewOnWork.priorPublicFeedback(history(true), WORK, CAPTURED, CAPTURED)
                        .feedback(),
                MAPPER.createArrayNode());
        assertThat(ReviewOnWork.read(raise(null), RESULTS, false, closed).errors())
                .isEmpty();
        var withheld = MAPPER.createObjectNode();
        withheld.putArray("decisions")
                .addObject()
                .put("resultId", "r1")
                .put("disposition", "ALREADY_SAID")
                .putArray("witnessIds")
                .add("feedback:" + PRIOR);
        withheld.putNull("summary");
        withheld.putArray("inline");
        assertThat(ReviewOnWork.read(withheld, RESULTS, false, closed).errors())
                .singleElement()
                .asString()
                .contains("feedback:" + PRIOR);
    }

    private static final String PRIOR = "0b6c8c38-55a4-4d55-9d0a-2a2f6c1f6e01";
    private static final String KIND = "scm.pull_request";
    private static final String URL = "https://gitlab.example.com/group/project/-/merge_requests/42";
    private static final String WORK = KIND + ":" + URL;
    private static final String CAPTURED = "2026-11-21T12:00:00Z";

    /** One delivered note on this work about the same practice; {@code closed} when Hephaestus resolved it as fixed. */
    private static ObjectNode history(boolean closed) {
        var history = MAPPER.createObjectNode();
        var entry = history.putArray("feedback").addObject();
        entry.put("id", PRIOR)
                .put("destination", "ON_WORK")
                .put("publicEligible", true)
                .put("body", SUMMARY)
                .put("recordedClaimCurrentness", "CURRENT")
                .put("withdrawn", false)
                .put("closedAsFixed", closed)
                .put("deliveredAt", "2026-11-20T12:00:00Z");
        entry.putObject("item").put("kind", KIND).put("url", URL);
        entry.putArray("basedOn")
                .addObject()
                .put("practiceSlug", "handles-errors")
                .put("outcome", "NOT_MET");
        return history;
    }

    private static Map<String, ReviewOnWork.Witness> advice(boolean current, String practice) {
        return Map.of("feedback:w1", new ReviewOnWork.Witness(current, true, Set.of(practice)));
    }

    private static ObjectNode raise(@Nullable String newSince) {
        var review = MAPPER.createObjectNode();
        var decision = review.putArray("decisions").addObject();
        decision.put("resultId", "r1").put("disposition", "RAISE");
        if (newSince != null) decision.put("newSince", newSince);
        var summary = review.putObject("summary");
        summary.putArray("basedOn").add("r1");
        summary.put("body", SUMMARY);
        review.putArray("inline");
        return review;
    }
}
