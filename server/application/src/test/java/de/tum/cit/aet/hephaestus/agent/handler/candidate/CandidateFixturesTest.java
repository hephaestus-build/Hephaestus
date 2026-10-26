package de.tum.cit.aet.hephaestus.agent.handler.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class CandidateFixturesTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIXTURES = "practice-results/";

    private static final Map<String, Class<? extends Candidate>> CANDIDATES = Map.of(
            "candidate-met-with-example.json", MetCandidate.class,
            "candidate-not-applicable.json", DoesNotApplyCandidate.class,
            "candidate-not-met.json", NotMetCandidate.class,
            "candidate-undetermined.json", CouldNotDetermineCandidate.class);

    @Test
    void shouldParseEveryCandidateFixtureToItsOutcomeWhenTheFixtureIsValid() throws IOException {
        Map<String, JsonNode> fixtures = resources("candidate-*.json");
        assertThat(fixtures.keySet()).containsExactlyInAnyOrderElementsOf(CANDIDATES.keySet());

        fixtures.forEach((name, fixture) -> assertThat(CandidateParser.parse(fixture))
                .as(name)
                .isInstanceOf(Objects.requireNonNull(CANDIDATES.get(name))));
    }

    @Test
    void shouldRefuseMissingRequiredNestedTextWhenTheWireFieldIsAbsent() throws IOException {
        ObjectNode example = fixture("candidate-met-with-example.json");
        ((ObjectNode) example.path("example")).remove("note");
        assertRefused(example, CandidateRefusal.MALFORMED_CANDIDATE);
        ObjectNode problem = fixture("candidate-not-met.json");
        ((ObjectNode) problem.path("problems").get(0).path("basis")).remove("explanation");
        assertRefused(problem, CandidateRefusal.MALFORMED_CANDIDATE);
    }

    @Test
    void shouldKeepUnknownHostCoverageIncompleteWithoutInventingAReadFailure() throws IOException {
        ObjectNode candidate = fixture("host-coverage-unknown.json");
        HostCoverage coverage = (HostCoverage) CandidateParser.parse(candidate).coverage();
        assertThat(coverage.complete()).isFalse();
        assertThat(coverage.truncatedReads()).isZero();
        assertThat(coverage.failedReads()).isZero();
        assertThat(coverage.omitted()).isNotEmpty();

        candidate.put("outcome", "MET");
        evidence(candidate).remove("openQuestions");
        assertRefused(candidate, CandidateRefusal.MET_COVERAGE_INCOMPLETE);
    }

    @Test
    void shouldKeepSeverityOnEachProblem() throws IOException {
        NotMetCandidate candidate = (NotMetCandidate) CandidateParser.parse(fixture("candidate-not-met.json"));

        assertThat(candidate.problems())
                .extracting(CandidateProblem::severity)
                .containsExactly(Severity.MAJOR, Severity.MINOR);
        assertThat(candidate.example()).isNotNull();
        assertThat(candidate.openQuestions()).hasSize(1);
        assertThat(candidate.coverage()).isInstanceOf(HostCoverage.class);
        assertThat(((HostCoverage) candidate.coverage()).complete()).isFalse();
    }

    @Test
    void shouldParseEveryAdmissionCandidateWhenTheAdmissionRequestIsShared() throws IOException {
        JsonNode request = fixture("admission-request.json");
        List<JsonNode> candidates = new ArrayList<>();
        request.path("results").forEach(result -> candidates.add(result.path("candidate")));
        Map<String, JsonNode> fixtures = resources("candidate-*.json");

        assertThat(candidates)
                .allSatisfy(candidate -> assertThat(
                                CandidateParser.parse(candidate).outcome().name())
                        .isEqualTo(candidate.path("outcome").asString()))
                .as("admission-request.json carries the candidate fixtures unchanged")
                .containsExactlyInAnyOrderElementsOf(fixtures.values());
    }

    @Test
    void shouldRefuseInvalidWireValuesAndDeferQuoteOverlapToVerification() throws IOException {
        Map<String, JsonNode> invalid = resources("invalid-*.json");
        JsonNode sidecar = Objects.requireNonNull(invalid.remove("invalid-refusals.json"), "invalid-refusals.json");
        Map<String, String> codes = new LinkedHashMap<>();
        sidecar.properties()
                .forEach(entry -> codes.put(entry.getKey(), entry.getValue().asString()));

        assertThat(codes.keySet()).as("the sidecar names every invalid fixture").isEqualTo(invalid.keySet());
        invalid.forEach((name, fixture) -> {
            if (CandidateRefusal.EXAMPLE_OVERLAPS_PROBLEM.wireValue().equals(codes.get(name))) {
                assertThat(CandidateParser.parse(fixture)).as(name).isInstanceOf(NotMetCandidate.class);
            } else {
                assertThatThrownBy(() -> CandidateParser.parse(fixture))
                        .as(name)
                        .isInstanceOfSatisfying(
                                CandidateRefusedException.class,
                                refused -> assertThat(refused.reasonCode())
                                        .as(name)
                                        .isEqualTo(codes.get(name)));
            }
        });
    }

    @Test
    void shouldReportAnEmptyListAsItsRuleWhenAListMustNameSomething() throws IOException {
        ObjectNode noQuestion = fixture("candidate-undetermined.json");
        evidence(noQuestion).putArray("openQuestions");
        assertRefused(noQuestion, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode nothingConsulted = fixture("candidate-met-with-example.json");
        ((ObjectNode) evidence(nothingConsulted).get("search")).putArray("consulted");
        assertRefused(nothingConsulted, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode noProblem = fixture("candidate-not-met.json");
        noProblem.putArray("problems");
        assertRefused(noProblem, CandidateRefusal.OUTCOME_PROBLEM_MISMATCH);

        ObjectNode emptyProblemsOnMet = fixture("candidate-met-with-example.json");
        emptyProblemsOnMet.putArray("problems");
        assertRefused(emptyProblemsOnMet, CandidateRefusal.OUTCOME_PROBLEM_MISMATCH);

        ObjectNode omitsNothing = fixture("candidate-not-met.json");
        ((ObjectNode) omitsNothing.get("coverage")).putArray("omitted");
        assertRefused(omitsNothing, CandidateRefusal.COVERAGE_INCONSISTENT);
    }

    @Test
    void shouldRefuseAsMalformedWhenACitationBreaksItsShape() throws IOException {
        ObjectNode backwards = fixture("candidate-met-with-example.json");
        firstCitation(backwards).put("startLine", 9).put("endLine", 8);
        assertRefused(backwards, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode emptySource = fixture("candidate-met-with-example.json");
        firstCitation(emptySource).put("sourceKind", "");
        assertRefused(emptySource, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode emptySourcePath = fixture("candidate-met-with-example.json");
        firstCitation(emptySourcePath).put("sourcePath", "");
        assertRefused(emptySourcePath, CandidateRefusal.MALFORMED_CANDIDATE);
    }

    @Test
    void shouldAcceptBoundedCitationCoordinatesAndRefuseOversizedValues() throws IOException {
        var bounded = fixture("candidate-met-with-example.json");
        firstCitation(bounded)
                .put("sourcePath", "x".repeat(4096))
                .put("path", "y".repeat(4096))
                .put("commit", "r".repeat(1024));
        assertThat(CandidateParser.parse(bounded)).isInstanceOf(MetCandidate.class);
        var longSourcePath = bounded.deepCopy();
        firstCitation(longSourcePath).put("sourcePath", "x".repeat(4097));
        assertRefused(longSourcePath, CandidateRefusal.MALFORMED_CANDIDATE);
        var longPath = bounded.deepCopy();
        firstCitation(longPath).put("path", "y".repeat(4097));
        assertRefused(longPath, CandidateRefusal.MALFORMED_CANDIDATE);
        var longCommit = bounded.deepCopy();
        firstCitation(longCommit).put("commit", "r".repeat(1025));
        assertRefused(longCommit, CandidateRefusal.MALFORMED_CANDIDATE);
    }

    @Test
    void shouldBoundLineNumbersAsTheRunnerDoesWhenACitationIsRead() throws IOException {
        ObjectNode zero = fixture("candidate-met-with-example.json");
        firstCitation(zero).put("startLine", 0);
        assertRefused(zero, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode pastInt = fixture("candidate-met-with-example.json");
        firstCitation(pastInt).put("endLine", Integer.MAX_VALUE + 1L);
        assertRefused(pastInt, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode fraction = fixture("candidate-met-with-example.json");
        firstCitation(fraction).put("endLine", 72.5);
        assertRefused(fraction, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode last = fixture("candidate-met-with-example.json");
        firstCitation(last).put("startLine", Integer.MAX_VALUE).put("endLine", Integer.MAX_VALUE);
        assertThat(CandidateParser.parse(last)).isInstanceOf(MetCandidate.class);

        ObjectNode integralDouble = fixture("candidate-met-with-example.json");
        firstCitation(integralDouble).put("startLine", 72.0).put("endLine", 72.0);
        assertThat(CandidateParser.parse(integralDouble)).isInstanceOf(MetCandidate.class);
    }

    @Test
    void shouldRefuseAsMalformedWhenAProblemPartExceedsTheBoundTheRunnerUses() throws IOException {
        ObjectNode longBasis = fixture("candidate-not-met.json");
        basis(longBasis).put("explanation", "x".repeat(CandidateLimits.PROBLEM_EXPLANATION_CHARS + 1));
        assertRefused(longBasis, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode longCriterion = fixture("candidate-not-met.json");
        basis(longCriterion)
                .put("type", "duty")
                .put("criterionQuote", "x".repeat(CandidateLimits.QUOTE_CHARS + 1))
                .put("event", 0)
                .remove("head");
        assertRefused(longCriterion, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode notSlug = fixture("candidate-not-met.json");
        problem(notSlug, 0).put("key", "Swallowed-Timeout");
        assertRefused(notSlug, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode sameKey = fixture("candidate-not-met.json");
        problem(sameKey, 0).put("key", "swallowed-error");
        problem(sameKey, 1).put("key", "swallowed-error");
        assertRefused(sameKey, CandidateRefusal.DUPLICATE_PROBLEM_KEY);
    }

    @Test
    void shouldRefuseAsMalformedWhenAnOmissionOrWarrantExceedsItsBound() throws IOException {
        ObjectNode twoLines = fixture("candidate-not-met.json");
        omission(twoLines).put("what", "lines of the export client\nbeyond the read limit");
        assertRefused(twoLines, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode longWhat = fixture("candidate-not-met.json");
        omission(longWhat).put("what", "x".repeat(CandidateLimits.SUMMARY_CHARS + 1));
        assertRefused(longWhat, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode longItem = fixture("candidate-not-met.json");
        omission(longItem).putArray("items").add("x".repeat(CandidateLimits.WARRANT_FIELD_CHARS + 1));
        assertRefused(longItem, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode longConsulted = fixture("candidate-met-with-example.json");
        ((ObjectNode) evidence(longConsulted).get("search"))
                .putArray("consulted")
                .add("x".repeat(CandidateLimits.WARRANT_FIELD_CHARS + 1));
        assertRefused(longConsulted, CandidateRefusal.MALFORMED_CANDIDATE);
    }

    @Test
    void shouldRefuseAnUnknownKeyButNameTheOutcomeRuleWhenAKnownKeySitsOnTheWrongOutcome() throws IOException {
        ObjectNode unknownKey = fixture("candidate-met-with-example.json");
        unknownKey.put("confidence", "high");
        assertRefused(unknownKey, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode nullOptional = fixture("candidate-not-met.json");
        nullOptional.putNull("severity");
        assertRefused(nullOptional, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode severityOnMet = fixture("candidate-met-with-example.json");
        severityOnMet.put("severity", "MINOR");
        assertRefused(severityOnMet, CandidateRefusal.MALFORMED_CANDIDATE);
    }

    @Test
    void shouldCountCandidateBytesAsJavaScriptWritesThemWhenTheTreeHoldsDoublesOrSurrogates() {
        ObjectNode value = MAPPER.createObjectNode()
                .put("a", 3.0)
                .put("b", "\uD83D\uDE00\u00e9\n")
                .put("c", "\uD800");
        String javaScript = "{\"a\":3,\"b\":\"\uD83D\uDE00\u00e9\\n\",\"c\":\"\\ud800\"}";

        assertThat(CandidateParser.javaScriptJsonBytes(value))
                .isEqualTo(javaScript.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void shouldRefuseMissingRequiredCreatorsAndWrongScalarsWhenTypedBindingRuns() throws IOException {
        ObjectNode missingLine = fixture("candidate-met-with-example.json");
        firstCitation(missingLine).remove("startLine");
        assertRefused(missingLine, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode missingBasisIndex = fixture("candidate-not-met.json");
        basis(missingBasisIndex).remove("head");
        assertRefused(missingBasisIndex, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode numericText = fixture("candidate-met-with-example.json");
        numericText.put("summary", 42);
        assertRefused(numericText, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode numericEnum = fixture("candidate-not-met.json");
        problem(numericEnum, 0).put("severity", 0);
        assertRefused(numericEnum, CandidateRefusal.MALFORMED_CANDIDATE);
    }

    @Test
    void shouldRefuseExplicitNullAndForgedProofWhenOptionalCitationKeysArePresent() throws IOException {
        ObjectNode nullCommit = fixture("candidate-met-with-example.json");
        firstCitation(nullCommit).putNull("commit");
        assertRefused(nullCommit, CandidateRefusal.MALFORMED_CANDIDATE);

        ObjectNode forgedProof = fixture("candidate-met-with-example.json");
        firstCitation(forgedProof).putObject("verification").put("status", "MATCHED");
        assertRefused(forgedProof, CandidateRefusal.MALFORMED_CANDIDATE);
    }

    @Test
    void shouldRefuseClaimedCoverageWhenAnyOutcomeCarriesIt() throws IOException {
        for (String name : CANDIDATES.keySet()) {
            ObjectNode candidate = fixture(name);
            candidate
                    .putObject("coverage")
                    .put("source", "claimed")
                    .put("selected", 1)
                    .put("checked", 1)
                    .putNull("total")
                    .put("complete", true);
            assertRefused(candidate, CandidateRefusal.COVERAGE_INCONSISTENT);

            ((ObjectNode) candidate.get("coverage")).remove("total");
            assertRefused(candidate, CandidateRefusal.MALFORMED_CANDIDATE);
        }
    }

    @Test
    void shouldNameTheInvalidFieldAndConstraintWithoutItsValue() throws IOException {
        var candidate = fixture("candidate-met-with-example.json");
        String rejected = "private text\nfrom captured work";
        candidate.put("summary", rejected);
        assertThatThrownBy(() -> CandidateParser.parse(candidate))
                .isInstanceOf(CandidateRefusedException.class)
                .hasMessageContaining("summary (Pattern)")
                .hasMessageNotContaining(rejected);
    }

    @Test
    void shouldRejectExplicitNullsButAllowAbsentOptionalsAndRequiredNullableTotal() throws IOException {
        var valid = fixture("candidate-met-with-example.json");
        firstCitation(valid).remove("commit");
        assertThat(CandidateParser.parse(valid)).isInstanceOf(MetCandidate.class);
        for (var key : List.of("example", "problems")) {
            var explicit = valid.deepCopy();
            explicit.putNull(key);
            assertRefused(explicit, CandidateRefusal.MALFORMED_CANDIDATE);
        }
        var nullElement = valid.deepCopy();
        ((ArrayNode) nullElement.path("citations")).addNull();
        assertRefused(nullElement, CandidateRefusal.MALFORMED_CANDIDATE);
        var falseTotal = valid.deepCopy();
        evidence(falseTotal).putNull("total");
        assertRefused(falseTotal, CandidateRefusal.MALFORMED_CANDIDATE);
    }

    private static void assertRefused(JsonNode value, CandidateRefusal expected) {
        assertThatThrownBy(() -> CandidateParser.parse(value))
                .isInstanceOfSatisfying(
                        CandidateRefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(expected));
    }

    private static ObjectNode evidence(ObjectNode candidate) {
        return (ObjectNode) candidate.get("evidence");
    }

    private static ObjectNode firstCitation(ObjectNode candidate) {
        return (ObjectNode) candidate.get("citations").get(0);
    }

    private static ObjectNode problem(ObjectNode candidate, int index) {
        return (ObjectNode) candidate.get("problems").get(index);
    }

    private static ObjectNode basis(ObjectNode candidate) {
        return (ObjectNode) problem(candidate, 0).get("basis");
    }

    private static ObjectNode omission(ObjectNode candidate) {
        return (ObjectNode) ((ArrayNode) candidate.get("coverage").get("omitted")).get(0);
    }

    private static ObjectNode fixture(String name) throws IOException {
        try (InputStream in = CandidateFixturesTest.class.getResourceAsStream("/" + FIXTURES + name)) {
            return (ObjectNode) MAPPER.readTree(Objects.requireNonNull(in, name));
        }
    }

    private static Map<String, JsonNode> resources(String pattern) throws IOException {
        Map<String, JsonNode> found = new LinkedHashMap<>();
        Resource[] resources =
                new PathMatchingResourcePatternResolver().getResources("classpath:" + FIXTURES + pattern);
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                found.put(Objects.requireNonNull(resource.getFilename()), MAPPER.readTree(in));
            }
        }
        return found;
    }
}
