package de.tum.cit.aet.hephaestus.agent.handler.composition;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
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
          { "id": "obs-0", "practiceSlug": "ships-tests-with-the-change", "assessment": "BAD",
            "severity": "MAJOR", "anchorable": true,
            "citations": [
              { "index": 0, "sourceKind": "scm.pull-request.diff", "path": "src/billing/InvoiceTotals.java",
                "side": "NEW", "startLine": 47, "endLine": 47, "anchorable": true }
            ] },
          { "id": "obs-1", "practiceSlug": "keeps-the-thread-moving", "assessment": "BAD",
            "severity": "MINOR", "anchorable": false,
            "citations": [
              { "index": 0, "sourceKind": "scm.review-threads", "path": "thread/9",
                "side": null, "startLine": 1, "endLine": null, "anchorable": false }
            ] }
        ]
        """;

    @Test
    void readsAnInContextUnitAndResolvesItsAnchorFromTheCitation() {
        List<ComposedFeedbackUnit> units = parser.parse(output("""
                { "channel": "IN_CONTEXT", "practiceSlug": "ships-tests-with-the-change",
                  "basedOn": ["obs-0"], "action": "NEW",
                  "title": "This branch is untested",
                  "nextStep": "Add a case that calls total with a tax-exempt customer.",
                  "placement": { "kind": "DIFF", "observationId": "obs-0", "citationIndex": 0 } }
                """, "[]"));

        assertThat(units).singleElement().satisfies(unit -> {
            assertThat(unit.channel()).isEqualTo(FeedbackChannel.IN_CONTEXT);
            assertThat(unit.basedOn()).containsExactly("obs-0");
            var placement = unit.placement();
            assertThat(placement).isNotNull();
            var anchor = placement.diffAnchor();
            assertThat(anchor).isNotNull();
            assertThat(anchor.path()).isEqualTo("src/billing/InvoiceTotals.java");
            assertThat(anchor.side()).isEqualTo("NEW");
            assertThat(anchor.startLine()).isEqualTo(47);
        });
    }

    @Test
    void readsAnArtifactPlacedInContextUnitWithoutInventingCoordinates() {
        List<ComposedFeedbackUnit> units = parser.parse(output("""
                { "channel": "IN_CONTEXT", "practiceSlug": "ships-tests-with-the-change",
                  "basedOn": ["obs-0"], "action": "NEW",
                  "title": "The decision is not yet explained",
                  "nextStep": "Add the constraint that makes this option necessary.",
                  "placement": { "kind": "ARTIFACT" } }
                """, "[]"));

        assertThat(units).singleElement().satisfies(unit -> {
            var placement = unit.placement();
            assertThat(placement).isNotNull();
            assertThat(placement.kind()).isEqualTo(ComposedFeedbackUnit.InContextPlacement.PlacementKind.ARTIFACT);
            assertThat(placement.diffAnchor()).isNull();
        });
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
    void shouldRejectUnitWhenArtifactPlacementHasCoordinates() {
        assertThat(parser.parse(output("""
                    { "channel": "IN_CONTEXT", "practiceSlug": "ships-tests-with-the-change",
                      "basedOn": ["obs-0"], "action": "NEW",
                      "title": "The decision is not yet explained", "nextStep": "Add the constraint.",
                      "placement": { "kind": "ARTIFACT", "observationId": "obs-0", "citationIndex": 0 } }
                    """, "[]"))).isEmpty();
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
    void refusesAnAnchorPointingAtACitationTheObservationDoesNotHave() {
        assertThat(parser.parse(output("""
                    { "channel": "IN_CONTEXT", "practiceSlug": "ships-tests-with-the-change",
                      "basedOn": ["obs-0"], "action": "NEW",
                      "title": "t", "nextStep": "n",
                      "placement": { "kind": "DIFF", "observationId": "obs-0", "citationIndex": 4 } }
                    """, "[]"))).isEmpty();
    }

    @Test
    void refusesAnInContextUnitAnchoredToAnObservationThatIsNotOnTheDiff() {
        assertThat(parser.parse(output("""
                    { "channel": "IN_CONTEXT", "practiceSlug": "keeps-the-thread-moving",
                      "basedOn": ["obs-1"], "action": "NEW",
                      "title": "t", "nextStep": "n",
                      "placement": { "kind": "DIFF", "observationId": "obs-1", "citationIndex": 0 } }
                    """, "[]"))).isEmpty();
    }

    @Test
    void refusesAnInContextUnitWithNoAnchorAtAll() {
        assertThat(parser.parse(output("""
                    { "channel": "IN_CONTEXT", "practiceSlug": "ships-tests-with-the-change",
                      "basedOn": ["obs-0"], "action": "NEW",
                      "title": "t", "nextStep": "n" }
                    """, "[]"))).isEmpty();
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
                .containsExactly(FeedbackChannel.IN_CONTEXT, FeedbackChannel.IN_APP);
        assertThat(parser.parse(jobOutput, FeedbackChannel.IN_APP))
                .singleElement()
                .extracting(ComposedFeedbackUnit::title)
                .isEqualTo("on the page");
    }

    @Test
    void shouldKeepAllUnitsWhenEveryLaneUsesItsFullCapacity() {
        var observations = objectMapper.createArrayNode();
        var units = objectMapper.createArrayNode();
        for (int practice = 0; practice < 10; practice++) {
            String practiceSlug = "practice-" + practice;
            String observationId = "obs-" + practice;
            observations.addObject().put("id", observationId).put("practiceSlug", practiceSlug);
            for (FeedbackChannel channel : FeedbackChannel.values()) {
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

    @Test
    void shouldReadTrimmedLeadWhenReviewComposedOne() {
        JsonNode jobOutput = raw("""
            { "lead": "  You kept this to one concern; the description just never says why.  ",
              "units": [] }
            """);

        assertThat(parser.lead(jobOutput))
                .isEqualTo("You kept this to one concern; the description just never says why.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{ \"units\": [] }", "{ \"lead\": null }", "{ \"lead\": \"\" }", "{ \"lead\": \"   \" }"})
    void shouldReturnNoLeadWhenTextIsBlankOrAbsent(String feedbackJson) {
        assertThat(parser.lead(raw(feedbackJson))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{ \"lead\": 42 }", "{ \"lead\": true }", "{ \"lead\": [\"a\"] }", "{ \"lead\": {} }"})
    void shouldReturnNoLeadWhenValueIsNotText(String feedbackJson) {
        assertThat(parser.lead(raw(feedbackJson))).isNull();
    }

    @Test
    void shouldReturnNoLeadWhenPayloadExceedsCeiling() {
        assertThat(parser.lead(raw("{ \"lead\": \"" + "x".repeat(5_000) + "\" }")))
                .as("the payload ceiling is a bound on what the envelope may carry, not a place to trim prose")
                .isNull();
    }

    @Test
    void shouldReturnNoLeadWhenJobOutputIsMissing() {
        assertThat(parser.lead(null)).isNull();
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
