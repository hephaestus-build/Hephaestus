package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiscardedEntry;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ParseResult;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class PracticeDetectionResultParserTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private PracticeDetectionResultParser parser;

    @BeforeEach
    void setUp() {
        parser = new PracticeDetectionResultParser(objectMapper);
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
        observation.put("presence", "PRESENT");
        observation.put("assessment", "GOOD");
        observation.put("severity", "INFO");
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
            assertThat(f.presence()).isEqualTo(Presence.PRESENT);
            assertThat(f.severity()).isEqualTo(Severity.INFO);
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

        @Test
        void notApplicableObservation() {
            ObjectNode observation = validFindingNode();
            observation.put("presence", "NOT_APPLICABLE");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).presence()).isEqualTo(Presence.NOT_APPLICABLE);
        }

        @Test
        void presentWithMissingAssessmentIsDiscarded() {
            // A present/absent observation MUST carry a GOOD/BAD valence; a missing assessment is malformed.
            ObjectNode observation = validFindingNode();
            observation.put("presence", "PRESENT");
            observation.remove("assessment");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
        }

        @Test
        void absentWithMissingAssessmentIsDiscarded() {
            // The valence requirement holds for ABSENT too, not only PRESENT — a gap with no GOOD/BAD is malformed.
            ObjectNode observation = validFindingNode();
            observation.put("presence", "ABSENT");
            observation.remove("assessment");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
        }

        @Test
        void absentWithAssessmentKeepsValence() {
            ObjectNode observation = validFindingNode();
            observation.put("presence", "ABSENT");
            observation.put("assessment", "BAD");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).presence()).isEqualTo(Presence.ABSENT);
            assertThat(result.validObservations().get(0).assessment()).isEqualTo(Assessment.BAD);
        }

        @Test
        void presentWithAssessmentKeepsValence() {
            ObjectNode observation = validFindingNode();
            observation.put("presence", "PRESENT");
            observation.put("assessment", "GOOD");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).presence()).isEqualTo(Presence.PRESENT);
            assertThat(result.validObservations().get(0).assessment()).isEqualTo(Assessment.GOOD);
        }

        @Test
        void notApplicableForcesNullAssessmentEvenWhenSupplied() {
            // NOT_APPLICABLE has no valence: any assessment supplied alongside it is ignored (forced null).
            ObjectNode observation = validFindingNode();
            observation.put("presence", "NOT_APPLICABLE");
            observation.put("assessment", "GOOD");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).presence()).isEqualTo(Presence.NOT_APPLICABLE);
            assertThat(result.validObservations().get(0).assessment()).isNull();
        }

        @Test
        void inconclusiveIsAcceptedAndKeepsNoDirection() {
            // "We read the evidence and it does not settle this" is a measurement the series must be able
            // to hold. Any assessment the model attaches to it is dropped rather than honoured, so a model
            // that could not decide cannot back-door a strength or a defect into a developer's history.
            ObjectNode observation = validFindingNode();
            observation.put("presence", "INCONCLUSIVE");
            observation.put("assessment", "GOOD");
            observation.put("severity", "MAJOR");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).hasSize(1);
            ValidatedObservation parsed = result.validObservations().get(0);
            assertThat(parsed.presence()).isEqualTo(Presence.INCONCLUSIVE);
            assertThat(parsed.assessment()).isNull();
            assertThat(parsed.coerceCoherence(false, false).severity())
                    .as("severity is an impact band for a defect; an undecided observation has none")
                    .isNull();
        }

        @Test
        void lowercaseObservation() {
            ObjectNode observation = validFindingNode();
            observation.put("presence", "present");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).presence()).isEqualTo(Presence.PRESENT);
        }

        @Test
        void invalidObservation() {
            ObjectNode observation = validFindingNode();
            observation.put("presence", "UNKNOWN_PRESENCE");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded()).hasSize(1);
            assertThat(result.discarded().get(0).reason()).contains("invalid presence");
        }

        @Test
        void forwardVocabularyObservations() {
            for (Presence v : new Presence[] {Presence.PRESENT, Presence.ABSENT}) {
                ObjectNode observation = validFindingNode();
                observation.put("presence", v.name());
                // Non-NA presence requires a valence; pair PRESENT->GOOD, ABSENT->BAD for a coherent observation.
                observation.put("assessment", v == Presence.PRESENT ? "GOOD" : "BAD");

                ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

                assertThat(result.validObservations()).hasSize(1);
                assertThat(result.validObservations().get(0).presence()).isEqualTo(v);
            }
        }

        @Test
        void legacyObservationVocabularyIsRejected() {
            // ADR 0022: the OBSERVED/NOT_OBSERVED vocabulary is not a valid presence — it is discarded
            // exactly like any other unknown presence.
            for (String legacy : new String[] {"OBSERVED", "NOT_OBSERVED"}) {
                ObjectNode observation = validFindingNode();
                observation.put("presence", legacy);

                ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

                assertThat(result.validObservations()).isEmpty();
                assertThat(result.discarded()).hasSize(1);
                assertThat(result.discarded().get(0).reason()).contains("invalid presence");
            }
        }

        @Test
        void lowercaseSeverity() {
            ObjectNode observation = validFindingNode();
            observation.put("severity", "major");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).severity()).isEqualTo(Severity.MAJOR);
        }

        @Test
        void invalidSeverity() {
            ObjectNode observation = validFindingNode();
            observation.put("severity", "EXTREME");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
        }

        @Test
        void missingSeverityDefaultsToInfoNotDiscarded() {
            // Regression: the model routinely omits severity on GOOD/NOT_APPLICABLE observations (severity is a
            // coaching band only for a BAD observation). Such an observation must be KEPT with severity INFO, never
            // discarded — coerceCoherence re-derives the band anyway, so dropping it silently loses coaching.
            ObjectNode observation = validFindingNode();
            observation.remove("severity");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).severity()).isEqualTo(Severity.INFO);
            assertThat(result.discarded()).isEmpty();
        }

        @Test
        void nullSeverityDefaultsToInfo() {
            ObjectNode observation = validFindingNode();
            observation.putNull("severity");

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).severity()).isEqualTo(Severity.INFO);
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
        void oversizedSummaryIsRejected() {
            ObjectNode observation = validFindingNode();
            observation.put("summary", "x".repeat(300));

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded())
                    .singleElement()
                    .extracting(DiscardedEntry::reason)
                    .asString()
                    .contains("summary");
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
        void optionalFieldsPresent() {
            ObjectNode observation = validFindingNode();
            observation.put("evidenceRationale", "Some evidenceRationale");
            ObjectNode evidence = objectMapper.createObjectNode();
            evidence.put("key", "value");
            observation.set("evidence", evidence);

            ParseResult result = parser.parse(wrapRawOutput(wrapObservations(observation)));

            ValidatedObservation f = result.validObservations().get(0);
            assertThat(f.evidenceRationale()).isEqualTo("Some evidenceRationale");
            assertThat(f.evidence()).isNotNull();
            assertThat(f.evidence().get("key").asString()).isEqualTo("value");
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
            evidence.put("data", "x".repeat(70_000));
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
            invalid.put("presence", "BOGUS");

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
                {"observations":[{"practiceSlug":"silent-failure","summary":"Empty catch","presence":"ABSENT","assessment":"BAD","severity":"MAJOR","evidence":{},"evidenceRationale":"```swift\\nprint(\\"Error: \\(error)\\")\\n```"}]}
                """;

            ParseResult result = parser.parse(wrapRawOutput(rawWithSwiftEscapes));

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.validObservations().get(0).practiceSlug()).isEqualTo("silent-failure");
        }

        @Test
        void fixesInvalidParenEscape() {
            String input = "print(\\\"\\(error)\\\")";
            String result = PracticeDetectionResultParser.sanitizeJsonEscapes(input);
            assertThat(result).isEqualTo("print(\\\"\\\\(error)\\\")");
        }

        @Test
        void handlesAlreadyEscaped() {
            // \\( in the input means the text literally has \( which is valid JSON (\\)
            String input = "print(\\\\(error))";
            String result = PracticeDetectionResultParser.sanitizeJsonEscapes(input);
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

            // Verify first observation
            ValidatedObservation first = result.validObservations().get(0);
            assertThat(first.practiceSlug()).isEqualTo("pr-description-quality");
            assertThat(first.presence()).isEqualTo(Presence.PRESENT);

            // Verify negative observation
            ValidatedObservation negative = result.validObservations().get(1);
            assertThat(negative.presence()).isEqualTo(Presence.ABSENT);
            assertThat(negative.severity()).isEqualTo(Severity.MAJOR);

            // Verify remaining presences
            assertThat(result.validObservations().get(3).presence()).isEqualTo(Presence.PRESENT);
            assertThat(result.validObservations().get(4).presence()).isEqualTo(Presence.ABSENT);
        }
    }

    @Nested
    @DisplayName("coerceCoherence — structural (observation, severity) invariants")
    class CoerceCoherence {

        private ValidatedObservation observation(Presence presence, Severity severity) {
            // Derive the valence from presence for these structural cases: PRESENT->GOOD (a strength a
            // defect-detector must not emit), ABSENT->BAD (a gap that carries a band), NA->null.
            Assessment assessment = presence == Presence.NOT_APPLICABLE
                    ? null
                    : presence == Presence.PRESENT ? Assessment.GOOD : Assessment.BAD;
            return new ValidatedObservation("p", "t", presence, assessment, severity, null, "evidenceRationale");
        }

        @Test
        @DisplayName("inconsistent PRESENT/GOOD is refused without inventing inapplicability")
        void shouldRefuseInconsistentAssessmentWithoutRewritingEvidence() {
            var original = observation(Presence.PRESENT, Severity.MAJOR);
            assertThatThrownBy(() -> original.coerceCoherence(true, false))
                    .isInstanceOfSatisfying(ObservationsRefusedException.class, e -> {
                        assertThat(e.reasonCode()).isEqualTo("incoherent_assessment");
                        assertThat(e.reason()).contains("Practice p", "PRESENT/GOOD", "original evidence");
                    });
            assertThat(original.presence()).isEqualTo(Presence.PRESENT);
            assertThat(original.assessment()).isEqualTo(Assessment.GOOD);
            assertThat(original.evidenceRationale()).isEqualTo("evidenceRationale");
        }

        @Test
        @DisplayName("non-defect-detector PRESENT/GOOD keeps presence but nulls severity (no band for a strength)")
        void nonDefectObservedSeverityInfo() {
            var out = observation(Presence.PRESENT, Severity.MAJOR).coerceCoherence(false, false);
            assertThat(out.presence()).isEqualTo(Presence.PRESENT);
            // A GOOD (strength) observation carries no severity band under ADR 0022.
            assertThat(out.severity()).isNull();
        }

        @Test
        @DisplayName("ABSENT with INFO severity is raised to MINOR (a gap must carry a band)")
        void notObservedInfoToMinor() {
            var out = observation(Presence.ABSENT, Severity.INFO).coerceCoherence(false, false);
            assertThat(out.presence()).isEqualTo(Presence.ABSENT);
            assertThat(out.severity()).isEqualTo(Severity.MINOR);
        }

        @Test
        @DisplayName("ABSENT with a real band is unchanged (identity)")
        void notObservedMajorUnchanged() {
            var in = observation(Presence.ABSENT, Severity.MAJOR);
            assertThat(in.coerceCoherence(false, false)).isSameAs(in);
        }

        @Test
        @DisplayName("NOT_APPLICABLE severity is nulled (no band for an inapplicable practice)")
        void naSeverityInfo() {
            var out = observation(Presence.NOT_APPLICABLE, Severity.MAJOR).coerceCoherence(false, false);
            assertThat(out.presence()).isEqualTo(Presence.NOT_APPLICABLE);
            assertThat(out.severity()).isNull();
        }

        @Test
        @DisplayName("defect-detector ABSENT defect is preserved with its band")
        void defectDetectorNotObservedPreserved() {
            var out = observation(Presence.ABSENT, Severity.MAJOR).coerceCoherence(true, false);
            assertThat(out.presence()).isEqualTo(Presence.ABSENT);
            assertThat(out.severity()).isEqualTo(Severity.MAJOR);
        }

        @Test
        @DisplayName("(ABSENT, GOOD) is a legitimate strength → preserved, NOT coerced to NOT_APPLICABLE")
        void absentGoodIsPreservedAsStrength() {
            // ADR 0022 §1: (ABSENT, GOOD) is "bad behaviour avoided → clean" — a real strength, distinct from a
            // practice that simply does not apply. It MUST persist as (ABSENT, GOOD); only its severity is
            // nulled (a coaching band is reserved for a BAD observation).
            var strength = new ValidatedObservation(
                    "p", "t", Presence.ABSENT, Assessment.GOOD, Severity.INFO, null, "evidenceRationale");
            var out = strength.coerceCoherence(false, false);
            assertThat(out.presence()).isEqualTo(Presence.ABSENT);
            assertThat(out.assessment()).isEqualTo(Assessment.GOOD);
            assertThat(out.severity()).isNull();
        }

        @Test
        @DisplayName("defect-detector (ABSENT, GOOD) survives — it is the shape its strength has")
        void defectDetectorAbsentGoodSurvives() {
            // A defect-detector's target signal is the undesirable behaviour, so what would be PRESENT for it
            // is the defect and a (PRESENT, GOOD) is off-contract. (ABSENT, GOOD) is the OTHER claim: the
            // harmful behaviour could have appeared in the corpus the practice bounds and did not. Coercing it
            // away is what used to tell a developer who wrote clean error handling that their work had no
            // subject for the practice — a NOT_APPLICABLE that is simply false, and indistinguishable from
            // "you touched nothing relevant". Whether the corpus really was bounded and covered is settled
            // against the practice's EXHAUSTIVE stances in PracticeDetectionDeliveryService, not guessed here.
            var strength = new ValidatedObservation(
                    "p", "t", Presence.ABSENT, Assessment.GOOD, Severity.INFO, null, "evidenceRationale");
            var out = strength.coerceCoherence(true, false);
            assertThat(out.presence()).isEqualTo(Presence.ABSENT);
            assertThat(out.assessment()).isEqualTo(Assessment.GOOD);
            // Severity is a coaching band for a problem only, so a strength carries none whatever was emitted.
            assertThat(out.severity()).isNull();
            assertThat(out.evidenceRationale()).isEqualTo("evidenceRationale");
        }

        @Test
        @DisplayName("list helper refuses inconsistent observations only for pinned defect-detector slugs")
        void shouldRefuseInconsistentAssessmentOnlyForPinnedPractice() {
            var dd = new ValidatedObservation("sec", "t", Presence.PRESENT, Assessment.GOOD, Severity.INFO, null, "r");
            var ok = new ValidatedObservation(
                    "style", "t", Presence.PRESENT, Assessment.GOOD, Severity.MAJOR, null, "r");
            assertThatThrownBy(() -> PracticeDetectionResultParser.coerceCoherence(List.of(ok, dd), Set.of("sec")))
                    .isInstanceOf(ObservationsRefusedException.class);
            var out = PracticeDetectionResultParser.coerceCoherence(List.of(dd, ok), Set.of("other"));
            assertThat(out).allSatisfy(observation -> {
                assertThat(observation.presence()).isEqualTo(Presence.PRESENT);
                assertThat(observation.assessment()).isEqualTo(Assessment.GOOD);
                assertThat(observation.severity()).isNull();
            });
        }

        // Advisory ceiling: craft/process critiques may not present as merge-blockers.

        @Test
        @DisplayName("advisory practice: ABSENT MAJOR is capped to MINOR (no merge-block)")
        void advisoryMajorCappedToMinor() {
            var out = observation(Presence.ABSENT, Severity.MAJOR).coerceCoherence(false, true);
            assertThat(out.presence()).isEqualTo(Presence.ABSENT);
            assertThat(out.severity()).isEqualTo(Severity.MINOR);
        }

        @Test
        @DisplayName("advisory practice: ABSENT CRITICAL is also capped to MINOR")
        void advisoryCriticalCappedToMinor() {
            var out = observation(Presence.ABSENT, Severity.CRITICAL).coerceCoherence(false, true);
            assertThat(out.severity()).isEqualTo(Severity.MINOR);
        }

        @Test
        @DisplayName("blocking-eligible practice: ABSENT MAJOR keeps its band")
        void blockingEligibleMajorPreserved() {
            var out = observation(Presence.ABSENT, Severity.MAJOR).coerceCoherence(false, false);
            assertThat(out.severity()).isEqualTo(Severity.MAJOR);
        }

        @Test
        @DisplayName("list helper: a craft slug's MAJOR is capped, a correctness slug's MAJOR survives")
        void listHelperAppliesAdvisoryCeilingBySlug() {
            var craft = new ValidatedObservation(
                    "describe-what-and-why", "t", Presence.ABSENT, Assessment.BAD, Severity.MAJOR, null, "r");
            var correctness = new ValidatedObservation(
                    "handles-errors-instead-of-swallowing-them",
                    "t",
                    Presence.ABSENT,
                    Assessment.BAD,
                    Severity.MAJOR,
                    null,
                    "r");
            var out = PracticeDetectionResultParser.coerceCoherence(List.of(craft, correctness), Set.of());
            assertThat(out.get(0).severity()).as("craft MAJOR -> MINOR").isEqualTo(Severity.MINOR);
            assertThat(out.get(1).severity()).as("correctness MAJOR preserved").isEqualTo(Severity.MAJOR);
        }

        @Test
        @DisplayName("a defect-detector slug that is NOT blocking-eligible still caps its BAD MAJOR to MINOR")
        void defectDetectorButAdvisoryCapsBadToMinor() {
            // A slug can be BOTH a defect-detector (in the set) AND advisory-only (not in BLOCKING_ELIGIBLE).
            // The defect-detector PRESENT/GOOD refusal does not touch a BAD observation, so the advisory ceiling must
            // apply independently: (ABSENT, BAD, MAJOR) -> MINOR.
            var ddAdvisory = new ValidatedObservation(
                    "describe-what-and-why", "t", Presence.ABSENT, Assessment.BAD, Severity.MAJOR, null, "r");
            var out =
                    PracticeDetectionResultParser.coerceCoherence(List.of(ddAdvisory), Set.of("describe-what-and-why"));
            assertThat(out.get(0).presence()).isEqualTo(Presence.ABSENT);
            assertThat(out.get(0).assessment()).isEqualTo(Assessment.BAD);
            assertThat(out.get(0).severity())
                    .as("advisory cap applies even when the slug is a defect-detector")
                    .isEqualTo(Severity.MINOR);
        }

        @Test
        @DisplayName("blocking-eligible set is the curated correctness/security/data-integrity consequence class")
        void blockingEligibleSetIsPinned() {
            assertThat(PracticeDetectionResultParser.BLOCKING_ELIGIBLE_PRACTICES)
                    .containsExactlyInAnyOrder(
                            "handles-errors-instead-of-swallowing-them",
                            "validates-inputs-and-edge-cases-at-the-boundary",
                            "avoids-unsafe-panics-and-chosen-crashes",
                            "validates-and-escapes-untrusted-input",
                            "avoids-insecure-defaults-and-over-broad-permissions",
                            "keeps-the-test-suite-honest");
        }
    }
}
