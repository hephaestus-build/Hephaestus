package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DiscardedEntry;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ParseResult;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.InputStream;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ReviewResultParserTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private ReviewResultParser parser;

    @BeforeEach
    void setUp() {
        parser = new ReviewResultParser(objectMapper);
    }

    /** Wraps a raw JSON string in the jobOutput envelope ({rawOutput: "..."}). */
    private ObjectNode wrapRawOutput(String rawJson) {
        ObjectNode jobOutput = objectMapper.createObjectNode();
        jobOutput.put("rawOutput", rawJson);
        return jobOutput;
    }

    /** Creates a minimal valid observation JSON object. */
    private ObjectNode validFindingNode() {
        ObjectNode observation = objectMapper.createObjectNode();
        observation.put("practiceSlug", "pr-description-quality");
        observation.put("summary", "Good PR description");
        observation.put("outcome", "MET");
        observation.putNull("severity");
        observation.putObject("evidence");
        observation.put("evidenceRationale", "The cited evidence supports the observation.");
        return observation;
    }

    /** Wraps observations into a complete raw output JSON string. */
    private String wrapObservations(ObjectNode... observations) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode arr = root.putArray("observations");
        for (ObjectNode f : observations) {
            arr.add(f);
        }
        return root.toString();
    }

    @Nested
    class SubmittedObservations {

        @Test
        void shouldValidateASubmittedArrayWithoutRereadingItAsText() {
            ArrayNode submitted = objectMapper.createArrayNode();
            submitted.add(validFindingNode());
            submitted.add("not an observation");

            var result = parser.parseObservations(submitted);

            assertThat(result.validObservations())
                    .singleElement()
                    .satisfies(observation ->
                            assertThat(observation.practiceSlug()).isEqualTo("pr-description-quality"));
            assertThat(result.discarded()).singleElement().satisfies(discarded -> {
                assertThat(discarded.index()).isEqualTo(1);
                assertThat(discarded.reason()).isEqualTo("entry is not a JSON object");
            });
        }

        @Test
        void shouldDiscardEverythingWhenNothingWasSubmitted() {
            assertThat(parser.parseObservations(objectMapper.createArrayNode()).validObservations())
                    .isEmpty();
            assertThat(parser.parseObservations(null).discarded())
                    .singleElement()
                    .extracting(ReviewResultParser.DiscardedEntry::reason)
                    .isEqualTo("missing or non-array 'observations' field");
        }
    }

    @Nested
    class StructuralValidation {

        @Test
        void nullJobOutput() {
            ParseResult result = parser.parse(null);

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).reason()).contains("null");
        }

        @Test
        void missingRawOutput() {
            ObjectNode jobOutput = objectMapper.createObjectNode();
            jobOutput.put("somethingElse", "value");

            ParseResult result = parser.parse(jobOutput);

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).reason()).contains("missing rawOutput");
        }

        @Test
        void blankRawOutput() {
            ParseResult result = parser.parse(wrapRawOutput("  "));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).reason()).contains("blank");
        }

        @Test
        void oversizedRawOutputIsRejectedBeforeSanitizing() {
            // A runaway/oversized sandbox output must be rejected up front — before readTree or
            // sanitizeJsonEscapes walk the whole string — not just in the fallback extractor.
            String huge = "{\"observations\":[" + "\\".repeat(1_000_001) + "]}";

            ParseResult result = parser.parse(wrapRawOutput(huge));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).reason()).contains("too large");
        }

        @Test
        void invalidJson() {
            ParseResult result = parser.parse(wrapRawOutput("not json {{{"));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).reason()).contains("invalid JSON");
        }

        @Test
        void missingObservations() {
            ParseResult result = parser.parse(wrapRawOutput("{\"summary\":\"hello\"}"));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).reason()).contains("missing");
        }

        @Test
        void emptyObservations() {
            ParseResult result = parser.parse(wrapRawOutput("{\"observations\":[]}"));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).reason()).contains("empty");
        }

        @Test
        void keepsAllObservations() {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode arr = root.putArray("observations");
            for (int i = 0; i < 5; i++) {
                ObjectNode f = validFindingNode();
                f.put("practiceSlug", "practice-" + i);
                arr.add(f);
            }

            ParseResult result = parser.parse(wrapRawOutput(root.toString()));

            assertThat(result.validObservations()).hasSize(5);
            assertThat(result.validObservations().get(0).practiceSlug()).isEqualTo("practice-0");
            assertThat(result.validObservations().get(4).practiceSlug()).isEqualTo("practice-4");
        }

        @Test
        @DisplayName("skips non-object entries in observations array")
        void nonObjectEntry() {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode arr = root.putArray("observations");
            arr.add("not an object");
            arr.add(validFindingNode());

            ParseResult result = parser.parse(wrapRawOutput(root.toString()));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).reason()).contains("not a JSON object");
        }
    }

    @Nested
    class FieldValidation {

        @Test
        void validObservation() {
            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(validFindingNode())));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.discarded()).isEmpty();

            ValidatedObservation f = result.validObservations().get(0);
            assertThat(f.practiceSlug()).isEqualTo("pr-description-quality");
            assertThat(f.summary()).isEqualTo("Good PR description");
            assertThat(f.outcome()).isEqualTo(Outcome.MET);
            assertThat(f.severity()).isNull();
        }

        @Test
        void missingPracticeSlug() {
            ObjectNode observation = validFindingNode();
            observation.remove("practiceSlug");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).reason()).contains("practiceSlug");
        }

        @Test
        void blankTitle() {
            ObjectNode observation = validFindingNode();
            observation.put("summary", "  ");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).reason()).contains("summary is blank");
        }

        @ParameterizedTest
        @EnumSource(Outcome.class)
        void shouldPreserveEveryOutcome(Outcome outcome) {
            ObjectNode observation = validFindingNode();
            observation.put("outcome", outcome.name());
            if (outcome == Outcome.NOT_MET) observation.put("severity", "MINOR");
            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));
            assertThat(result.validObservations())
                    .singleElement()
                    .satisfies(parsed -> assertThat(parsed.outcome()).isEqualTo(outcome));
        }

        @Test
        void shouldRejectMissingOrUnknownOutcomes() {
            for (String value : new String[] {"ASSESSED", "POSITIVE", "UNKNOWN"}) {
                ObjectNode observation = validFindingNode();
                observation.put("outcome", value);
                assertThat(parser.parseObservations(
                                        objectMapper.createArrayNode().add(observation))
                                .validObservations())
                        .isEmpty();
            }
            ObjectNode observation = validFindingNode();
            observation.remove("outcome");
            assertThat(parser.parseObservations(objectMapper.createArrayNode().add(observation))
                            .validObservations())
                    .isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"absenceBoundary", "presence", "assessment", "unknown"})
        void shouldRejectUnknownEvidenceFields(String field) {
            ObjectNode observation = validFindingNode();
            ((ObjectNode) observation.path("evidence")).putNull(field);

            var result = parser.parseObservations(objectMapper.createArrayNode().add(observation));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.reason()).contains("unknown evidence fields", field));
        }

        @Test
        void shouldRejectRemovedAxesInsteadOfInferringAnOutcome() {
            for (String field : new String[] {"assessmentStatus", "presence", "assessment"}) {
                ObjectNode observation = validFindingNode();
                observation.put(field, "legacy value");
                assertThat(parser.parseObservations(
                                        objectMapper.createArrayNode().add(observation))
                                .discarded())
                        .singleElement()
                        .satisfies(entry -> assertThat(entry.reason()).contains("unknown observation fields"));
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"met", "Met", "not_met", "NOT MET", " MET", "MET "})
        void shouldRejectNoncanonicalOutcomeValues(String outcome) {
            ObjectNode observation = validFindingNode();
            observation.put("outcome", outcome);

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));
            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.reason()).contains("invalid outcome"));
        }

        @Test
        void shouldRejectLowercaseSeverity() {
            ObjectNode observation = validFindingNode();
            observation.put("outcome", "NOT_MET");
            observation.put("severity", "major");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.reason()).contains("invalid severity"));
        }

        @Test
        void invalidSeverity() {
            ObjectNode observation = validFindingNode();
            observation.put("severity", "EXTREME");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
        }

        @Test
        void missingSeverityIsRejected() {
            ObjectNode observation = validFindingNode();
            observation.remove("severity");
            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));
            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
        }

        @Test
        void shouldPreserveNullSeverityForMet() {
            ObjectNode observation = validFindingNode();
            observation.putNull("severity");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).severity()).isNull();
        }

        @Test
        void removedConfidenceFieldRejectsTheObservation() {
            ObjectNode observation = validFindingNode();
            observation.put("confidence", 0.9);

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded())
                    .singleElement()
                    .satisfies(discarded -> assertThat(discarded.reason())
                            .contains("unknown observation fields")
                            .contains("confidence"));
        }

        @Test
        void shouldDiscardOnlyTheObservationWhenItsSummaryExceedsTheRunnerBound() {
            ObjectNode atBound = validFindingNode();
            atBound.put("summary", "x".repeat(160));
            ObjectNode overBound = validFindingNode();
            overBound.put("summary", "x".repeat(161));

            ParseResult result = parser.parseObservations(
                    objectMapper.createArrayNode().add(atBound).add(overBound));

            assertThat(result.validObservations())
                    .singleElement()
                    .extracting(ValidatedObservation::summary)
                    .isEqualTo("x".repeat(160));
            assertThat(result.discarded())
                    .singleElement()
                    .isEqualTo(new DiscardedEntry(1, "summary is 161 characters, over the 160 allowed"));
        }

        @Test
        @DisplayName("normalizes practice slug with underscores")
        void slugNormalization() {
            ObjectNode observation = validFindingNode();
            observation.put("practiceSlug", "PR_Description_Quality");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).practiceSlug()).isEqualTo("pr-description-quality");
        }

        @Test
        void preservesSubmittedEvidenceAndRationale() {
            ObjectNode observation = validFindingNode();
            observation.put("evidenceRationale", "Some evidenceRationale");
            ObjectNode evidence = objectMapper.createObjectNode();
            evidence.putArray("citations").addObject().put("quote", "Quoted source text");
            observation.set("evidence", evidence);

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            ValidatedObservation f = result.validObservations().get(0);
            assertThat(f.evidenceRationale()).isEqualTo("Some evidenceRationale");
            assertThat(f.evidence()).isNotNull();
            assertThat(f.evidence().path("citations").get(0).path("quote").asString())
                    .isEqualTo("Quoted source text");
        }

        @Test
        @DisplayName("a removed measurement field rejects the observation")
        void removedFieldsAreContractErrors() {
            ObjectNode observation = validFindingNode();
            observation.put("evidenceRationale", "Some evidenceRationale");
            observation.put("guidance", "Rotate the credential and re-run the pipeline.");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded())
                    .singleElement()
                    .satisfies(discarded -> assertThat(discarded.reason())
                            .contains("unknown observation fields")
                            .contains("guidance"));
        }

        @Test
        void oversizedEvidenceIsRejected() {
            ObjectNode observation = validFindingNode();
            ObjectNode evidence = objectMapper.createObjectNode();
            evidence.putArray("citations").addObject().put("quote", "x".repeat(70_000));
            observation.set("evidence", evidence);

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded())
                    .singleElement()
                    .extracting(DiscardedEntry::reason)
                    .asString()
                    .contains("evidence");
        }

        @Test
        @DisplayName("rejects evidenceRationale exceeding 10000 chars")
        void oversizedEvidenceRationaleIsRejected() {
            ObjectNode observation = validFindingNode();
            observation.put("evidenceRationale", "r".repeat(15_000));

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded())
                    .singleElement()
                    .extracting(DiscardedEntry::reason)
                    .asString()
                    .contains("evidenceRationale");
        }
    }

    @Nested
    class MixedObservations {

        @Test
        void mixedValidAndInvalid() {
            ObjectNode valid = validFindingNode();
            ObjectNode invalid = validFindingNode();
            invalid.put("outcome", "BOGUS");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(valid, invalid)));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).index()).isEqualTo(1);
        }

        @Test
        void allInvalid() {
            ObjectNode bad1 = validFindingNode();
            bad1.remove("practiceSlug");
            ObjectNode bad2 = validFindingNode();
            bad2.remove("summary");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(bad1, bad2)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(2);
        }
    }

    @Nested
    class Deduplication {

        @Test
        void keepsAllFindingsPerPractice() {
            ObjectNode f1 = validFindingNode();
            f1.put("practiceSlug", "error-handling");
            f1.put("summary", "First violation");

            ObjectNode f2 = validFindingNode();
            f2.put("practiceSlug", "error-handling");
            f2.put("summary", "Second violation");

            ObjectNode f3 = validFindingNode();
            f3.put("practiceSlug", "code-hygiene");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(f1, f2, f3)));

            // All three observations kept — no dedup
            assertThat(result.validObservations()).hasSize(3);
            assertThat(result.validObservations().stream()
                            .filter(f -> f.practiceSlug().equals("error-handling"))
                            .count())
                    .isEqualTo(2);
            assertThat(result.validObservations().stream()
                            .anyMatch(f -> f.practiceSlug().equals("code-hygiene")))
                    .isTrue();
        }
    }

    @Nested
    class JsonExtractionFromMixedText {

        @Test
        void extractsJsonFromPhaseMarkers() {
            String mixed = """
                [PHASE0] Context loaded: 1 files changed
                [PHASE1] RELEVANT: avoids-insecure-defaults-and-over-broad-permissions
                [PHASE4] Output ready
                {"observations": [%s]}
                """.formatted(validFindingNode().toString());

            ParseResult result = parser.parse(wrapRawOutput(mixed));

            assertThat(result.validObservations()).hasSize(1);
        }

        @Test
        void returnsEmptyWhenNoJsonInText() {
            String text = "[PHASE0] no json here at all {notjson";

            ParseResult result = parser.parse(wrapRawOutput(text));

            assertThat(result.validObservations()).isEmpty();
        }
    }

    @Nested
    class JsonEscapeSanitization {

        @Test
        void fixesSwiftInterpolation() {
            // Simulate agent output with Swift \(error) in code snippets
            // Jackson would fail on \( because it's not a valid JSON escape
            String rawWithSwiftEscapes = """
                {"observations":[{"practiceSlug":"silent-failure","summary":"Empty catch","outcome": "NOT_MET","severity":"MAJOR","evidence":{},"evidenceRationale":"```swift\\nprint(\\"Error: \\(error)\\")\\n```"}]}
                """;

            ParseResult result = parser.parse(wrapRawOutput(rawWithSwiftEscapes));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).practiceSlug()).isEqualTo("silent-failure");
        }

        @Test
        void fixesInvalidParenEscape() {
            String input = "print(\\\"\\(error)\\\")";
            String result = ReviewResultParser.sanitizeJsonEscapes(input);
            assertThat(result).isEqualTo("print(\\\"\\\\(error)\\\")");
        }

        @Test
        void handlesAlreadyEscaped() {
            // \\( in the input means the text literally has \( which is valid JSON (\\)
            String input = "print(\\\\(error))";
            String result = ReviewResultParser.sanitizeJsonEscapes(input);
            assertThat(result).isEqualTo(input);
        }
    }

    @Nested
    class ContractTest {

        @Test
        void parseSampleFixture() throws Exception {
            InputStream is = getClass().getResourceAsStream("/practices/observation/sample-agent-output.json");
            assertThat(is).as("sample fixture must exist").isNotNull();

            JsonNode fixture = objectMapper.readTree(is);
            // Wrap in jobOutput envelope
            ObjectNode jobOutput = objectMapper.createObjectNode();
            jobOutput.put("rawOutput", objectMapper.writeValueAsString(fixture));

            ParseResult result = parser.parse(jobOutput);

            assertThat(result.validObservations()).hasSize(5);
            assertThat(result.discarded()).isEmpty();

            ValidatedObservation first = result.validObservations().get(0);
            assertThat(first.practiceSlug()).isEqualTo("pr-description-quality");
            assertThat(first.outcome()).isEqualTo(Outcome.MET);

            ValidatedObservation negative = result.validObservations().get(1);
            assertThat(negative.outcome()).isEqualTo(Outcome.NOT_MET);
            assertThat(negative.severity()).isEqualTo(Severity.MAJOR);

            assertThat(result.validObservations().get(3).outcome()).isEqualTo(Outcome.MET);
            assertThat(result.validObservations().get(4).outcome()).isEqualTo(Outcome.NOT_MET);
        }
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void shouldPreserveResultsWithoutChangingSeverity(Outcome outcome) {
        var observation = new ValidatedObservation(
                "custom-practice",
                "A specified practice",
                outcome,
                outcome == Outcome.NOT_MET ? Severity.MINOR : null,
                null,
                "Evidence supports this result");
        assertThat(ReviewResultParser.validateCoherence(List.of(observation))).containsExactly(observation);
    }

    @ParameterizedTest
    @CsvSource({"MET,MAJOR", "NOT_APPLICABLE,MINOR", "UNDETERMINED,INFO", "NOT_MET,"})
    void shouldRejectAnInvalidSeverityWithoutCoercion(Outcome outcome, @Nullable Severity severity) {
        var observation = new ValidatedObservation("custom-practice", "A result", outcome, severity, null, "Evidence");
        assertThatThrownBy(() -> ReviewResultParser.validateCoherence(List.of(observation)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
