package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ParseResult;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.practices.PracticeJudgment;
import de.tum.cit.aet.hephaestus.practices.PracticeQuestion;
import de.tum.cit.aet.hephaestus.practices.PracticeRule;
import de.tum.cit.aet.hephaestus.practices.model.ObservationAnswer;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.QuestionAnswer;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ReviewResultParserTest extends BaseUnitTest {

    private static final String SLUG = "ships-tests-with-the-change";

    /** A gate, a requirement and a severity distinction: the shape most bundled practices take. */
    private static final PracticeJudgment JUDGMENT = new PracticeJudgment(
            List.of(
                    new PracticeQuestion(
                            "adds_behaviour",
                            "Adds production behaviour",
                            "Does the diff add production behaviour?",
                            "A new branch or computation.",
                            "Layout or configuration only."),
                    new PracticeQuestion(
                            "test_covers",
                            "A test exercises it",
                            "Does a test in the diff exercise the new behaviour?",
                            "A test names the symbol.",
                            "No test touches it."),
                    new PracticeQuestion(
                            "is_fix",
                            "The change fixes a defect",
                            "Does the change fix a defect?",
                            "The description names a defect fixed.",
                            "No defect is named.")),
            List.of(
                    new PracticeRule(
                            "no-behaviour",
                            Map.of("adds_behaviour", QuestionAnswer.NO),
                            Outcome.NOT_APPLICABLE,
                            null,
                            "The change adds nothing a test could observe."),
                    new PracticeRule(
                            "untested-fix",
                            conditions("test_covers", QuestionAnswer.NO, "is_fix", QuestionAnswer.YES),
                            Outcome.NOT_MET,
                            Severity.MAJOR,
                            "A defect fix ships without a test that locks it in."),
                    new PracticeRule(
                            "untested",
                            Map.of("test_covers", QuestionAnswer.NO),
                            Outcome.NOT_MET,
                            Severity.MINOR,
                            "New behaviour ships without a test that exercises it."),
                    new PracticeRule("met", Map.of(), Outcome.MET, null, "The new behaviour ships with a test.")));

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private ReviewResultParser parser;

    @BeforeEach
    void setUp() {
        parser = new ReviewResultParser(objectMapper);
    }

    private static Map<String, QuestionAnswer> conditions(
            String first, QuestionAnswer firstAnswer, String second, QuestionAnswer secondAnswer) {
        Map<String, QuestionAnswer> when = new LinkedHashMap<>();
        when.put(first, firstAnswer);
        when.put(second, secondAnswer);
        return when;
    }

    private ObjectNode citation(String path, int line, String quote) {
        ObjectNode citation = objectMapper.createObjectNode();
        citation.put("sourceKind", "scm.pull-request.diff");
        citation.put("artifactPath", "context/change.json");
        citation.put("path", path);
        citation.put("side", "NEW");
        citation.put("startLine", line);
        citation.put("endLine", line);
        citation.put("quote", quote);
        return citation;
    }

    private ObjectNode answer(String question, String value, ObjectNode... citations) {
        ObjectNode answer = objectMapper.createObjectNode();
        answer.put("question", question);
        answer.put("answer", value);
        answer.put("because", "The cited line shows it.");
        ArrayNode cited = answer.putArray("citations");
        for (ObjectNode citation : citations) cited.add(citation);
        return answer;
    }

    private ObjectNode observation(ObjectNode... answers) {
        ObjectNode observation = objectMapper.createObjectNode();
        observation.put("practiceSlug", SLUG);
        observation.put("summary", "Parser error branch ships without a test");
        ArrayNode list = observation.putArray("answers");
        for (ObjectNode answer : answers) list.add(answer);
        return observation;
    }

    private ParseResult parse(ObjectNode... observations) {
        ArrayNode submitted = objectMapper.createArrayNode();
        for (ObjectNode observation : observations) submitted.add(observation);
        return parser.parseObservations(submitted, Map.of(SLUG, JUDGMENT));
    }

    private static tools.jackson.databind.JsonNode evidence(ValidatedObservation derived) {
        return java.util.Objects.requireNonNull(derived.evidence());
    }

    private static List<ObservationAnswer> answers(ValidatedObservation derived) {
        return java.util.Objects.requireNonNull(derived.answers());
    }

    private ObjectNode untestedFix() {
        return observation(
                answer("adds_behaviour", "YES", citation("App/Parser.swift", 30, "guard !text.isEmpty")),
                answer("test_covers", "NO", citation("Tests/ParserTests.swift", 12, "func testIso() {")),
                answer("is_fix", "YES", citation("App/Parser.swift", 30, "guard !text.isEmpty")));
    }

    @Nested
    class Derivation {

        @Test
        void shouldDeriveTheOutcomeSeverityAndRuleFromTheAnswers() {
            ValidatedObservation derived =
                    parse(untestedFix()).validObservations().getFirst();

            assertThat(derived.outcome()).isEqualTo(Outcome.NOT_MET);
            assertThat(derived.severity()).isEqualTo(Severity.MAJOR);
            assertThat(derived.ruleId()).isEqualTo("untested-fix");
            assertThat(derived.evidenceRationale()).startsWith("A defect fix ships without a test that locks it in.");
        }

        @Test
        void shouldRecordEveryAnswerInQuestionOrder() {
            ValidatedObservation derived =
                    parse(untestedFix()).validObservations().getFirst();

            assertThat(answers(derived))
                    .extracting(ObservationAnswer::question, ObservationAnswer::answer)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("adds_behaviour", QuestionAnswer.YES),
                            org.assertj.core.groups.Tuple.tuple("test_covers", QuestionAnswer.NO),
                            org.assertj.core.groups.Tuple.tuple("is_fix", QuestionAnswer.YES));
        }

        @Test
        void shouldListEachQuotedLineOnceWithTheDecidingAnswersFirst() {
            ValidatedObservation derived =
                    parse(untestedFix()).validObservations().getFirst();

            var citations = evidence(derived).path("citations");
            assertThat(citations).hasSize(2);
            // test_covers and is_fix decide; is_fix shares its line with adds_behaviour.
            assertThat(citations.get(0).path("path").asString()).isEqualTo("Tests/ParserTests.swift");
            ObservationAnswer added = answers(derived).getFirst();
            ObservationAnswer fix = answers(derived).getLast();
            assertThat(added.citations()).isEqualTo(fix.citations());
        }

        @Test
        void shouldGiveANotApplicableObservationItsRuleAsTheWarrant() {
            ValidatedObservation derived = parse(observation(
                            answer("adds_behaviour", "NO", citation("App/View.swift", 4, "Text(\"Hi\")")),
                            answer("test_covers", "NO", citation("App/View.swift", 4, "Text(\"Hi\")")),
                            answer("is_fix", "NO", citation("App/View.swift", 4, "Text(\"Hi\")"))))
                    .validObservations()
                    .getFirst();

            assertThat(derived.outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
            var warrant = evidence(derived).path("inapplicability");
            assertThat(warrant.path("ruledOutBy").asString())
                    .isEqualTo("The change adds nothing a test could observe.");
            assertThat(warrant.path("subject").asString()).isEqualTo("Adds production behaviour");
            assertThat(warrant.path("consulted").get(0).asString()).isEqualTo("scm.pull-request.diff");
        }

        @Test
        void shouldMergeTheDecidingAnswersSearchesIntoTheObservationsSearch() {
            ObjectNode covers =
                    answer("test_covers", "NO", citation("Tests/ParserTests.swift", 12, "func testIso() {"));
            ObjectNode search = covers.putObject("search");
            search.putArray("consulted").add("scm.pull-request.diff");
            search.put("lookedFor", "a test of the empty input");
            search.put("boundary", "the test files of this change");

            ValidatedObservation derived = parse(observation(
                            answer("adds_behaviour", "YES", citation("App/Parser.swift", 30, "guard")),
                            covers,
                            answer("is_fix", "NO", citation("App/Parser.swift", 30, "guard"))))
                    .validObservations()
                    .getFirst();

            assertThat(derived.ruleId()).isEqualTo("untested");
            assertThat(evidence(derived).path("search").path("lookedFor").asString())
                    .isEqualTo("a test of the empty input");
        }

        @Test
        void shouldLeaveTheOutcomeOpenWhenAnUndeterminedAnswerCouldChangeIt() {
            ObjectNode covers = answer("test_covers", "UNDETERMINED", citation("Tests/ParserTests.swift", 12, "func"));
            covers.put("wouldSettleIt", "the body of the test helper the test calls");

            ValidatedObservation derived = parse(observation(
                            answer("adds_behaviour", "YES", citation("App/Parser.swift", 30, "guard")),
                            covers,
                            answer("is_fix", "NO", citation("App/Parser.swift", 30, "guard"))))
                    .validObservations()
                    .getFirst();

            assertThat(derived.outcome()).isEqualTo(Outcome.UNDETERMINED);
            assertThat(derived.ruleId()).isNull();
            assertThat(evidence(derived)
                            .path("undecidability")
                            .path("wouldSettleIt")
                            .asString())
                    .isEqualTo("the body of the test helper the test calls");
        }

        @Test
        void shouldKeepAnOutcomeEveryResolutionAgreesOnAtTheLeastSevereBand() {
            ObjectNode fix = answer("is_fix", "UNDETERMINED", citation("App/Parser.swift", 30, "guard"));
            fix.put("wouldSettleIt", "the issue the change closes");

            ValidatedObservation derived = parse(observation(
                            answer("adds_behaviour", "YES", citation("App/Parser.swift", 30, "guard")),
                            answer("test_covers", "NO", citation("Tests/ParserTests.swift", 12, "func")),
                            fix))
                    .validObservations()
                    .getFirst();

            // "untested" holds whichever way is_fix is settled: its reason rests only on definite answers.
            assertThat(derived.outcome()).isEqualTo(Outcome.NOT_MET);
            assertThat(derived.severity()).isEqualTo(Severity.MINOR);
            assertThat(derived.ruleId()).isEqualTo("untested");
            assertThat(derived.answers())
                    .filteredOn(ObservationAnswer::decisive)
                    .extracting(ObservationAnswer::question)
                    .containsExactly("test_covers");
        }
    }

    @Nested
    class Refusal {

        @Test
        void shouldRefuseAnObservationThatLeavesAnOutcomeQuestionUnanswered() {
            ParseResult result = parse(observation(
                    answer("adds_behaviour", "YES", citation("App/Parser.swift", 30, "guard")),
                    answer("is_fix", "NO", citation("App/Parser.swift", 30, "guard"))));

            assertThat(result.validObservations()).isEmpty();
            assertThat(result.discarded().getFirst().reason()).contains("unanswered questions: [test_covers]");
        }

        @Test
        void shouldReadALeftOutSeverityQuestionAsOpenAndKeepTheLowerBand() {
            // Whether the untested behaviour fixes a defect only grades the shortfall; left out, it is open.
            ParseResult result = parse(observation(
                    answer("adds_behaviour", "YES", citation("App/Parser.swift", 30, "guard")),
                    answer("test_covers", "NO", citation("Tests/ParserTests.swift", 12, "func"))));

            assertThat(result.discarded()).isEmpty();
            assertThat(result.validObservations().getFirst().outcome()).isEqualTo(Outcome.NOT_MET);
            assertThat(result.validObservations().getFirst().severity()).isEqualTo(Severity.MINOR);
        }

        @Test
        void shouldRefuseAQuestionThePracticeDoesNotAsk() {
            ParseResult result = parse(observation(
                    answer("adds_behaviour", "YES", citation("App/Parser.swift", 30, "guard")),
                    answer("test_covers", "NO", citation("Tests/ParserTests.swift", 12, "func")),
                    answer("is_fix", "NO", citation("App/Parser.swift", 30, "guard")),
                    answer("names_why", "YES", citation("App/Parser.swift", 30, "guard"))));

            assertThat(result.discarded().getFirst().reason()).contains("unknown question: names_why");
        }

        @Test
        void shouldRefuseAnUndeterminedAnswerThatNamesNothingThatWouldSettleIt() {
            ParseResult result = parse(observation(
                    answer("adds_behaviour", "YES", citation("App/Parser.swift", 30, "guard")),
                    answer("test_covers", "UNDETERMINED", citation("Tests/ParserTests.swift", 12, "func")),
                    answer("is_fix", "NO", citation("App/Parser.swift", 30, "guard"))));

            assertThat(result.discarded().getFirst().reason()).contains("wouldSettleIt");
        }

        @Test
        void shouldRefuseAnAnswerWithoutCitations() {
            ParseResult result = parse(observation(
                    answer("adds_behaviour", "YES"),
                    answer("test_covers", "NO", citation("Tests/ParserTests.swift", 12, "func")),
                    answer("is_fix", "NO", citation("App/Parser.swift", 30, "guard"))));

            assertThat(result.discarded().getFirst().reason()).contains("at least one citation");
        }

        @ParameterizedTest
        @ValueSource(strings = {"outcome", "severity", "evidence", "evidenceRationale"})
        void shouldRefuseAStatedOutcomeOrItsWarrants(String field) {
            ObjectNode stated = untestedFix();
            stated.put(field, "MET");

            assertThat(parse(stated).discarded().getFirst().reason()).contains("unknown observation fields");
        }

        @Test
        void shouldRefuseTheWholeRunWhenAPracticeWasNotAdmitted() {
            ObjectNode foreign = untestedFix();
            foreign.put("practiceSlug", "describe-what-and-why");

            assertThatThrownBy(() -> parse(foreign))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("practice not admitted to the job: slug=describe-what-and-why");
        }

        @Test
        void shouldRefuseOnlyTheObservationWhoseSummaryExceedsTheBound() {
            ObjectNode tooLong = untestedFix();
            tooLong.put("summary", "x ".repeat(ReviewResultParser.MAX_SUMMARY_LENGTH));

            ParseResult result = parse(tooLong, untestedFix());

            assertThat(result.validObservations()).hasSize(1);
            assertThat(result.discarded()).hasSize(1);
        }

        @Test
        void shouldReportAnEmptySubmission() {
            assertThat(parser.parseObservations(objectMapper.createArrayNode(), Map.of(SLUG, JUDGMENT))
                            .discarded())
                    .singleElement()
                    .extracting(ReviewResultParser.DiscardedEntry::reason)
                    .isEqualTo("observations array is empty");
        }
    }
}
