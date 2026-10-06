package de.tum.cit.aet.hephaestus.agent.handler.composition;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FeedbackCompositionResultParserTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final FeedbackCompositionResultParser parser = new FeedbackCompositionResultParser();

    private static final String OBSERVATIONS = """
        [
          { "id": "obs-0", "practiceSlug": "ships-tests-with-the-change", "outcome": "NOT_MET",
            "severity": "MAJOR", "anchorable": true,
            "citations": [
              { "index": 0, "sourceKind": "scm.pull-request.diff", "path": "src/billing/InvoiceTotals.java",
                "side": "NEW", "startLine": 47, "endLine": 47, "anchorable": true }
            ] },
          { "id": "obs-1", "practiceSlug": "keeps-the-thread-moving", "outcome": "NOT_MET",
            "severity": "MINOR", "anchorable": false,
            "citations": [
              { "index": 0, "sourceKind": "scm.review-threads", "path": "thread/9",
                "side": null, "startLine": 1, "endLine": null, "anchorable": false }
            ] }
        ]
        """;

    @Test
    void shouldIgnoreAnEarlierRunnersFragmentUnitForTheWorkAndKeepItsNextStepAsHistory() {
        JsonNode legacy = output("""
                { "channel": "IN_CONTEXT", "practiceSlug": "ships-tests-with-the-change",
                  "basedOn": ["obs-0"], "action": "NEW",
                  "title": "This branch is untested",
                  "nextStep": "Add a case that calls total with a tax-exempt customer.",
                  "placement": { "kind": "DIFF", "observationId": "obs-0", "citationIndex": 0 } }
                """, "[]");

        assertThat(parser.parse(legacy)).isEmpty();
        assertThat(parser.writtenWhole(legacy)).isFalse();
        assertThat(parser.historicalNextSteps(legacy))
                .containsExactly(Map.entry("obs-0", "Add a case that calls total with a tax-exempt customer."));
    }

    @Test
    void shouldReadUnitWhenGroundedInPrimaryAndRelatedPracticeEvidence() {
        List<ComposedFeedbackUnit> units = parser.parse(output("""
                { "channel": "IN_APP", "practiceSlug": "ships-tests-with-the-change",
                  "basedOn": ["obs-0", "obs-1"], "action": "NEW",
                  "title": "Review readiness breaks across the workflow",
                  "body": "The change enters review without tests and leaves follow-up work unresolved.",
                  "nextStep": "Add the test and resolve the linked review thread before requesting review." }
                """, "[]"));

        assertThat(units)
                .singleElement()
                .satisfies(unit -> assertThat(unit.basedOn()).containsExactly("obs-0", "obs-1"));
    }

    @Test
    void shouldReadNotesWhenConversationUnitIsValid() {
        List<ComposedFeedbackUnit> units = parser.parse(output("""
                { "channel": "IN_CHAT", "practiceSlug": "ships-tests-with-the-change",
                  "basedOn": ["obs-0"], "action": "NEW",
                  "title": "Tests arrive after review",
                  "notes": {
                    "situation": "On !18, !20 and !22 the test landed a push after the review comment.",
                    "capability": "Writing the test last is what leaves the review to find the gap.",
                    "evidenceSummary": "On the last three changes the test arrived a push later.",
                    "inConversationSignal": "They name a check they could run before pushing."
                  } }
                """, "[]"));

        assertThat(units).singleElement().satisfies(unit -> {
            assertThat(unit.body()).isNull();
            assertThat(unit.notes()).isNotNull();
            assertThat(unit.notes().situation())
                    .isEqualTo("On !18, !20 and !22 the test landed a push after the review comment.");
            assertThat(unit.notes().inConversationSignal())
                    .isEqualTo("They name a check they could run before pushing.");
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                """
            "situation": "On !18 the test landed a push after the review comment.",
            "evidenceSummary": "On the last three changes the test arrived a push later.",
            "inConversationSignal": "They name a check they could run before pushing."
            """,
                """
            "situation": "On !18 the test landed a push after the review comment.",
            "capability": "   ",
            "evidenceSummary": "On the last three changes the test arrived a push later.",
            "inConversationSignal": "They name a check they could run before pushing."
            """,
            })
    void shouldRejectConversationUnitWhenRequiredNoteIsMissing(String notes) {
        assertThat(parser.parse(output("""
                    { "channel": "IN_CHAT", "practiceSlug": "ships-tests-with-the-change",
                      "basedOn": ["obs-0"], "action": "NEW",
                      "title": "Tests arrive after review",
                      "notes": { %s } }
                    """.formatted(notes), "[]"))).isEmpty();
    }

    @Test
    void refusesAnOverlongNoteInsteadOfPersistingAnArbitrarilyTruncatedPlan() {
        String oversized = "x".repeat(ComposedFeedbackUnit.MAX_AIM_LENGTH + 1);
        assertThat(parser.parse(output("""
                    { "channel": "IN_CHAT", "practiceSlug": "ships-tests-with-the-change",
                      "basedOn": ["obs-0"], "action": "NEW",
                      "title": "Tests arrive after review",
                      "notes": {
                        "situation": "Tests repeatedly arrived after review.",
                        "capability": "%s",
                        "evidenceSummary": "Three merge requests show the sequence.",
                        "inConversationSignal": "The developer can name when the test belongs."
                      } }
                    """.formatted(oversized), "[]"))).isEmpty();
    }

    @Test
    void refusesAUnitNamingAThreadKeyThatWasNeverStaged() {
        String unit = """
            { "channel": "IN_APP", "practiceSlug": "ships-tests-with-the-change",
              "basedOn": ["obs-0"], "action": "SUPERSEDE",
              "supersedesThreadKey": "invented-key",
              "title": "A way of working", "body": "A body", "nextStep": "A next step" }
            """;

        assertThat(parser.parse(output(unit, "[]"))).isEmpty();
        assertThat(parser.parse(output(unit, "[\"invented-key\"]"))).hasSize(1);
    }

    @Test
    void refusesAnAnchorOnALongitudinalLane() {
        assertThat(parser.parse(output("""
                    { "channel": "IN_APP", "practiceSlug": "ships-tests-with-the-change",
                      "basedOn": ["obs-0"], "action": "NEW",
                      "title": "t", "body": "b", "nextStep": "n",
                      "placement": { "kind": "DIFF", "observationId": "obs-0", "citationIndex": 0 } }
                    """, "[]"))).isEmpty();
    }

    @Test
    void shouldKeepOnlyWithholdWhenReasonIsPresent() {
        assertThat(parser.parse(output("""
                    { "channel": "IN_APP", "practiceSlug": "ships-tests-with-the-change",
                      "basedOn": ["obs-0"], "action": "WITHHOLD", "withholdReason": "ALREADY_SAID" }
                    """, "[]"))).singleElement().satisfies(unit -> {
            assertThat(unit.action()).isEqualTo(ComposedFeedbackUnit.Action.WITHHOLD);
            assertThat(unit.withholdReason()).isEqualTo(ComposedFeedbackUnit.WithholdReason.ALREADY_SAID);
        });

        assertThat(parser.parse(output("""
                    { "channel": "IN_APP", "practiceSlug": "ships-tests-with-the-change",
                      "basedOn": ["obs-0"], "action": "WITHHOLD" }
                    """, "[]"))).isEmpty();
    }

    @Test
    void rejectsEvidenceFromAnotherPracticeOrAnUnknownObservation() {
        for (String reference :
                List.of("obs-1", "obs-missing", "prior:ships-tests-with-the-change", "prior:keeps-the-thread-moving")) {
            assertThat(parser.parse(output("""
                        { "channel": "IN_APP", "practiceSlug": "ships-tests-with-the-change",
                          "basedOn": ["%s"], "action": "NEW",
                          "title": "t", "body": "b", "nextStep": "n" }
                        """.formatted(reference), "[]")))
                    .as("evidence reference %s", reference)
                    .isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"channel", "practiceSlug", "basedOn", "action", "title", "body", "nextStep"})
    void dropsAnInAppUnitMissingAnyLoadBearingPart(String omitted) {
        var unit = objectMapper.createObjectNode();
        unit.put("channel", "IN_APP");
        unit.put("practiceSlug", "ships-tests-with-the-change");
        unit.putArray("basedOn").add("obs-0");
        unit.put("action", "NEW");
        unit.put("title", "A title");
        unit.put("body", "A body");
        unit.put("nextStep", "A next step");
        unit.remove(omitted);

        assertThat(parser.parse(outputOf(objectMapper.createArrayNode().add(unit), "[]")))
                .isEmpty();
    }

    @Test
    void keepsOnlyTheFirstUnitForAPracticeOnOneChannel() {
        List<ComposedFeedbackUnit> units = parser.parse(outputOf(objectMapper.readTree("""
                    [
                      { "channel": "IN_APP", "practiceSlug": "ships-tests-with-the-change", "basedOn": ["obs-0"], "action": "NEW",
                        "title": "First", "body": "b", "nextStep": "n" },
                      { "channel": "IN_APP", "practiceSlug": "ships-tests-with-the-change", "basedOn": ["obs-0"], "action": "NEW",
                        "title": "Second", "body": "b", "nextStep": "n" }
                    ]
                    """), "[]"));

        assertThat(units)
                .singleElement()
                .extracting(ComposedFeedbackUnit::title)
                .isEqualTo("First");
    }

    @Test
    void filtersByChannelWithoutCollapsingOtherChannels() {
        JsonNode jobOutput = outputOf(objectMapper.readTree("""
                [
                  { "channel": "IN_CONTEXT", "practiceSlug": "ships-tests-with-the-change",
                    "basedOn": ["obs-0"], "action": "NEW", "title": "on the work", "nextStep": "n",
                    "placement": { "kind": "DIFF", "observationId": "obs-0", "citationIndex": 0 } },
                  { "channel": "IN_APP", "practiceSlug": "ships-tests-with-the-change",
                    "basedOn": ["obs-0"], "action": "NEW", "title": "on the page", "body": "b", "nextStep": "n" }
                ]
                """), "[]");

        assertThat(parser.parse(jobOutput))
                .extracting(ComposedFeedbackUnit::channel)
                .containsExactly(FeedbackChannel.IN_APP);
        assertThat(parser.parse(jobOutput, FeedbackChannel.IN_APP))
                .singleElement()
                .extracting(ComposedFeedbackUnit::title)
                .isEqualTo("on the page");
    }

    @Test
    void shouldKeepAllUnitsWhenEveryLaneUsesItsFullCapacity() {
        var observations = objectMapper.createArrayNode();
        var units = objectMapper.createArrayNode();
        for (int practice = 0; practice < 15; practice++) {
            String practiceSlug = "practice-" + practice;
            String observationId = "obs-" + practice;
            observations.addObject().put("id", observationId).put("practiceSlug", practiceSlug);
            for (FeedbackChannel channel : List.of(FeedbackChannel.IN_APP, FeedbackChannel.IN_CHAT)) {
                var unit = units.addObject().put("channel", channel.name()).put("practiceSlug", practiceSlug);
                unit.putArray("basedOn").add(observationId);
                unit.put("action", "WITHHOLD").put("withholdReason", "ALREADY_SAID");
            }
        }
        var payload = objectMapper.createObjectNode();
        payload.set("observations", observations);
        payload.set("preparedTargets", objectMapper.createArrayNode());
        payload.set("units", units);
        var output = objectMapper.createObjectNode();
        output.set("feedback", payload);

        assertThat(parser.parse(output)).hasSize(30);
    }

    @Test
    void normalisesPracticeSlug() {
        assertThat(parser.parse(output("""
                    { "channel": "IN_APP", "practiceSlug": "Ships_Tests_With_The_Change", "basedOn": ["obs-0"],
                      "action": "NEW", "title": "t", "body": "b", "nextStep": "n" }
                    """, "[]")))
                .singleElement()
                .extracting(ComposedFeedbackUnit::practiceSlug)
                .isEqualTo("ships-tests-with-the-change");
    }

    @Test
    void treatsAnyMalformedOrAbsentPayloadAsNothingComposed() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse(objectMapper.createObjectNode())).isEmpty();
        assertThat(parser.parse(raw("{}"))).isEmpty();
        assertThat(parser.parse(raw("{ \"units\": \"not an array\" }"))).isEmpty();
        assertThat(parser.parse(raw("{ \"units\": [ 3, null, \"x\" ] }"))).isEmpty();
    }

    // --- The review on the work ---------------------------------------------------------------------------

    private static final String SUMMARY = "  The new branch in `total` has no test.\\n\\n"
            + "    total(taxExempt)\\n\\nAdd a case that calls it with a tax-exempt customer.\\n";

    private JsonNode review(String reviewJson) {
        return raw("""
            { "contractVersion": 2, "observations": %s, "units": [], "review": %s }
            """.formatted(OBSERVATIONS, reviewJson));
    }

    @Test
    void shouldReadACompleteReviewWithEveryBodyExactlyAsWrittenAndTheAnchorFromTheCitation() {
        JsonNode output = review("""
            { "summary": { "body": "%s", "basedOn": ["obs-0", "obs-1"] },
              "inline": [ { "body": "No test reaches this line.", "basedOn": ["obs-0"],
                            "anchor": { "observationId": "obs-0", "citationIndex": 0 } } ],
              "withheld": [] }
            """.formatted(SUMMARY));

        assertThat(parser.writtenWhole(output)).isTrue();
        ComposedReview read = parser.review(output);
        assertThat(read).isNotNull();
        ComposedReview.Summary summary = read.summary();
        assertThat(summary).isNotNull();
        assertThat(summary.body())
                .isEqualTo("  The new branch in `total` has no test.\n\n"
                        + "    total(taxExempt)\n\nAdd a case that calls it with a tax-exempt customer.\n");
        assertThat(summary.basedOn()).containsExactly("obs-0", "obs-1");
        assertThat(read.inline()).singleElement().satisfies(note -> {
            assertThat(note.anchor().path()).isEqualTo("src/billing/InvoiceTotals.java");
            assertThat(note.anchor().startLine()).isEqualTo(47);
        });
    }

    @Test
    void shouldReadAValidEmptyReviewAsSilenceAndAMissingOneAsNoReview() {
        assertThat(parser.review(review("{}"))).isEqualTo(ComposedReview.empty());
        assertThat(parser.review(review("null"))).isNull();
        assertThat(parser.review(raw("{ \"contractVersion\": 2, \"units\": [] }")))
                .isNull();
        assertThat(parser.review(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{ \"summary\": { \"body\": \"Add the test.\", \"basedOn\": [\"obs-0\", 42] } }",
                "{ \"summary\": { \"body\": \"Add the test.\", \"basedOn\": \"obs-0\" } }",
                "{ \"summary\": { \"body\": \"Add the test.\", \"basedOn\": [] } }",
                "{ \"summary\": { \"body\": \"Add the test.\", \"basedOn\": [\"obs-missing\"] } }",
                "{ \"summary\": { \"body\": \"   \", \"basedOn\": [\"obs-0\"] } }",
                "{ \"summary\": { \"body\": \"Fine. <!-- marker -->\", \"basedOn\": [\"obs-0\"] } }",
                "{ \"summary\": { \"body\": \"Add the reason.}\\\"\", \"basedOn\": [\"obs-0\"] } }",
                "{ \"inline\": [ { \"body\": \"x\", \"basedOn\": [\"obs-1\"],"
                        + " \"anchor\": { \"observationId\": \"obs-0\", \"citationIndex\": 0 } } ] }",
                "{ \"inline\": [ { \"body\": \"x\", \"basedOn\": [\"obs-1\"],"
                        + " \"anchor\": { \"observationId\": \"obs-1\", \"citationIndex\": 0 } } ] }",
                "{ \"inline\": [ { \"body\": \"x\", \"basedOn\": [\"obs-0\"],"
                        + " \"anchor\": { \"observationId\": \"obs-0\", \"citationIndex\": 4 } } ] }",
                "{ \"inline\": { \"body\": \"x\" } }",
                "{ \"withheld\": [ { \"basedOn\": [\"obs-0\"], \"reason\": \"BORED\" } ] }",
                "{ \"summary\": { \"body\": \"Add the test.\", \"basedOn\": [\"obs-0\"] },"
                        + " \"withheld\": [ { \"basedOn\": [\"obs-0\"], \"reason\": \"BELOW_BAR\" } ] }",
                "{ \"lead\": \"A fragment from an earlier contract.\" }",
            })
    void shouldReadAReviewThatBreaksItsContractAsNoReviewRatherThanAsSilence(String reviewJson) {
        assertThat(parser.review(review(reviewJson))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MET", "UNDETERMINED", "NOT_APPLICABLE"})
    void shouldRefuseWithholdingWhenTheNamedObservationIsNotNotMet(String outcome) {
        String evidence = OBSERVATIONS.replace("NOT_MET", outcome);
        JsonNode output = raw("""
            { "contractVersion": 2, "observations": %s,
              "review": { "withheld": [{ "basedOn": ["obs-0"], "reason": "ALREADY_SAID" }] } }
            """.formatted(evidence));
        assertThat(parser.review(output)).isNull();
    }

    @Test
    void shouldRefuseAWholeReviewOverTheLineNoteBoundOrWithTwoNotesOnOneLine() {
        String note = "{ \"body\": \"x\", \"basedOn\": [\"obs-0\"],"
                + " \"anchor\": { \"observationId\": \"obs-0\", \"citationIndex\": 0 } }";

        assertThat(parser.review(review("{ \"inline\": [" + note + ", " + note + "] }")))
                .isNull();
        assertThat(parser.review(review("{ \"inline\": [" + String.join(", ", Collections.nCopies(31, note)) + "] }")))
                .isNull();
    }

    private JsonNode output(String unitJson, String preparedThreadKeysJson) {
        return outputOf(objectMapper.createArrayNode().add(objectMapper.readTree(unitJson)), preparedThreadKeysJson);
    }

    private JsonNode outputOf(JsonNode units, String preparedThreadKeysJson) {
        var payload = objectMapper.createObjectNode();
        payload.set("observations", objectMapper.readTree(OBSERVATIONS));
        var preparedTargets = objectMapper.createArrayNode();
        for (JsonNode key : objectMapper.readTree(preparedThreadKeysJson)) {
            preparedTargets
                    .addObject()
                    .put("threadKey", key.asString())
                    .put("channel", "IN_APP")
                    .put("practiceSlug", "ships-tests-with-the-change");
        }
        payload.set("preparedTargets", preparedTargets);
        payload.set("units", units);
        var jobOutput = objectMapper.createObjectNode();
        jobOutput.set("feedback", payload);
        return jobOutput;
    }

    private JsonNode raw(String feedbackJson) {
        var jobOutput = objectMapper.createObjectNode();
        jobOutput.set("feedback", objectMapper.readTree(feedbackJson));
        return jobOutput;
    }
}
