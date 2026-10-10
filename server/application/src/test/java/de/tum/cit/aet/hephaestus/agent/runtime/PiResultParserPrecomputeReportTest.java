package de.tum.cit.aet.hephaestus.agent.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser.PrecomputeRunReport;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeModelPurpose;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeModelUseDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeNeed;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeNotRatedDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeNotRatedReason;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeRunStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PiResultParserPrecomputeReportTest extends BaseUnitTest {

    private static final Set<String> STAGED = Set.of("comment-quality", "error-handling");

    private PiResultParser parser;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        parser = new PiResultParser(new ObjectMapper(), meterRegistry);
    }

    private List<PrecomputeRunReport> parse(String report) {
        return parser.parsePrecomputeReport(report.getBytes(UTF_8), STAGED);
    }

    private double failures() {
        return meterRegistry
                .counter("agent.pi.result.parse.failure", "stage", "precompute_report")
                .count();
    }

    private static String report(String... entries) {
        return "{\"practices\":[" + String.join(",", entries) + "],\"truncated\":false}";
    }

    private static String positional(String slug, String status, int leads) {
        return """
                {"slug":"%s","status":"%s","leads":%d,"models":[]}""".formatted(slug, status, leads);
    }

    /**
     * {@code pi-precompute-report.spec.ts} writes this file from results of every status, slot, need and reason. A
     * status word or a field name that the writer and this parser spell differently fails one of the two tests.
     */
    @Test
    void shouldReadEveryStatusAndFieldWhenTheReportWriterWritesThem() throws IOException {
        byte[] fixture;
        try (InputStream in = getClass().getResourceAsStream("/agent/precompute-report.json")) {
            fixture = Objects.requireNonNull(in, "agent/precompute-report.json").readAllBytes();
        }
        Set<String> staged = Set.of("broken", "decides", "needs-a-model", "never-ran", "scans", "slow");

        List<PrecomputeRunReport> runs = parser.parsePrecomputeReport(fixture, staged);

        assertThat(failures()).isZero();
        assertThat(runs)
                .extracting(PrecomputeRunReport::practiceSlug)
                .containsExactlyElementsOf(staged.stream().sorted().toList());
        assertThat(runs).extracting(PrecomputeRunReport::status).containsOnly(PrecomputeRunStatus.values());
        assertThat(runs)
                .filteredOn(run -> run.models() == null)
                .extracting(PrecomputeRunReport::practiceSlug)
                .containsExactly("never-ran", "slow");
        assertThat(runs)
                .filteredOn(run -> run.durationMs() == null)
                .extracting(PrecomputeRunReport::practiceSlug)
                .containsExactly("needs-a-model", "never-ran");
        List<PrecomputeModelUseDTO> uses = runs.stream()
                .flatMap(run -> run.models() == null ? Stream.empty() : run.models().stream())
                .toList();
        assertThat(uses).extracting(PrecomputeModelUseDTO::purpose).contains(PrecomputeModelPurpose.values());
        assertThat(uses).extracting(PrecomputeModelUseDTO::need).contains(PrecomputeNeed.values());
        assertThat(uses).extracting(PrecomputeModelUseDTO::bound).contains(true, false);
        assertThat(uses.stream().flatMap(use -> use.notRated().stream()))
                .extracting(PrecomputeNotRatedDTO::reason)
                .contains(PrecomputeNotRatedReason.values());
    }

    @Test
    void shouldReadEachModelWithItsPurposeNeedAndReasonsNotRatedWhenTheScriptDeclaredThem() {
        String decision = """
                {"slug":"comment-quality","status":"ok","leads":3,"models":[
                   {"slot":"decision","need":"required","bound":true,"notRated":{"too-large":2,"deadline":5,"budget":0}},
                   {"slot":"chat","need":"optional","bound":true,"notRated":{}}]}""";

        List<PrecomputeRunReport> runs = parse(report(decision, positional("error-handling", "ok", 7)));

        assertThat(runs)
                .containsExactly(
                        new PrecomputeRunReport(
                                "comment-quality",
                                PrecomputeRunStatus.OK,
                                3,
                                List.of(
                                        new PrecomputeModelUseDTO(
                                                PrecomputeModelPurpose.PRACTICE_DECISION,
                                                PrecomputeNeed.REQUIRED,
                                                true,
                                                // In the declared order of the reasons; a zero count is left out.
                                                List.of(
                                                        new PrecomputeNotRatedDTO(PrecomputeNotRatedReason.DEADLINE, 5),
                                                        new PrecomputeNotRatedDTO(
                                                                PrecomputeNotRatedReason.TOO_LARGE, 2))),
                                        new PrecomputeModelUseDTO(
                                                PrecomputeModelPurpose.PRACTICE_REVIEW,
                                                PrecomputeNeed.OPTIONAL,
                                                true,
                                                List.of())),
                                null,
                                null),
                        new PrecomputeRunReport("error-handling", PrecomputeRunStatus.OK, 7, List.of(), null, null));
        assertThat(failures()).isZero();
    }

    /** "Uses no model" is a claim about the script, so a script that ended before it declared its models never makes it. */
    @Test
    void shouldLeaveTheModelsUnknownWhenTheScriptEndedBeforeItDeclaredThem() {
        List<PrecomputeRunReport> runs = parse(report("""
                {"slug":"comment-quality","status":"timeout","leads":0}""", positional("error-handling", "error", 0)));

        assertThat(runs)
                .containsExactly(
                        new PrecomputeRunReport("comment-quality", PrecomputeRunStatus.TIMED_OUT, 0, null, null, null),
                        new PrecomputeRunReport(
                                "error-handling", PrecomputeRunStatus.FAILED, 0, List.of(), null, null));
        assertThat(failures()).isZero();
    }

    @Test
    void shouldKeepTheFirstLineOfAFailedScriptsErrorCutToItsLimit() {
        // Characters outside the BMP count once each, as PostgreSQL counts them.
        String longLine = "TypeError: " + "\uD83D\uDE42".repeat(PiResultParser.PRECOMPUTE_ERROR_MAX_LENGTH);
        List<PrecomputeRunReport> runs = parse(report("""
                {"slug":"comment-quality","status":"error","leads":0,"error":"%s\\n    at decide (script.ts:4:9)"}""".formatted(longLine), """
                {"slug":"error-handling","status":"error","leads":0,"error":"Cannot read properties of undefined"}"""));

        assertThat(runs)
                .extracting(PrecomputeRunReport::error)
                .containsExactly(
                        longLine.substring(
                                0, longLine.offsetByCodePoints(0, PiResultParser.PRECOMPUTE_ERROR_MAX_LENGTH)),
                        "Cannot read properties of undefined");
        assertThat(failures()).isZero();
    }

    /**
     * {@code pi-precompute-report.spec.ts} reads the same cases, so the writer and this parser keep one rule for the
     * error line.
     */
    @Test
    void shouldKeepTheErrorLineThatTheReportWriterKeepsWhenBothReadTheSameError() throws IOException {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/agent/precompute-error-lines.json")) {
            cases = new ObjectMapper()
                    .readTree(Objects.requireNonNull(in, "agent/precompute-error-lines.json")
                            .readAllBytes());
        }
        List<String> slugs = IntStream.range(0, cases.size())
                .mapToObj(index -> "case-%02d".formatted(index))
                .toList();
        String report = report(IntStream.range(0, cases.size())
                .mapToObj(index -> """
                        {"slug":"%s","status":"error","leads":0,"error":%s}""".formatted(
                                slugs.get(index),
                                asciiJson(cases.get(index).path("error").asString())))
                .toArray(String[]::new));

        List<PrecomputeRunReport> runs = parser.parsePrecomputeReport(report.getBytes(UTF_8), Set.copyOf(slugs));

        assertThat(runs)
                .extracting(PrecomputeRunReport::practiceSlug, PrecomputeRunReport::error)
                .containsExactlyElementsOf(IntStream.range(0, cases.size())
                        .mapToObj(index -> tuple(
                                slugs.get(index),
                                cases.get(index).path("line").isString()
                                        ? cases.get(index).path("line").asString()
                                        : null))
                        .toList());
        assertThat(failures()).isZero();
    }

    /** A JSON string with every character outside printable ASCII escaped, so an unpaired surrogate survives. */
    private static String asciiJson(String text) {
        return text.chars()
                .mapToObj(c ->
                        c >= ' ' && c <= '~' && c != '"' && c != '\\' ? Character.toString(c) : "\\u%04x".formatted(c))
                .collect(Collectors.joining("", "\"", "\""));
    }

    /**
     * The run is written in the attempt's terminal transaction, so a value that PostgreSQL refuses would fail the
     * whole review. A control character becomes a space; a line with an unpaired surrogate is left out.
     */
    @Test
    void shouldKeepOnlyTextPostgresqlCanStoreWhenTheErrorHoldsANulOrAnUnpairedSurrogate() {
        List<PrecomputeRunReport> runs = parse(report("""
                {"slug":"comment-quality","status":"error","leads":0,"error":"a\\u0000b\\tc"}""", """
                {"slug":"error-handling","status":"error","leads":0,"error":"bad \\uD800 thing"}"""));

        assertThat(runs)
                .extracting(PrecomputeRunReport::status, PrecomputeRunReport::error)
                .containsExactly(tuple(PrecomputeRunStatus.FAILED, "a b c"), tuple(PrecomputeRunStatus.FAILED, null));
        assertThat(failures()).isZero();
    }

    @Test
    void shouldKeepHowLongAScriptRanWhenTheRunnerSaysAndLeaveItOutOtherwise() {
        List<PrecomputeRunReport> runs = parse(report("""
                {"slug":"comment-quality","status":"ok","leads":0,"models":[],"durationMs":4200}""", """
                {"slug":"error-handling","status":"skipped","leads":0,"models":[],"durationMs":-1}"""));

        assertThat(runs).extracting(PrecomputeRunReport::durationMs).containsExactly(4200, null);
        // A script that did not run took no time, whatever the runner wrote.
        assertThat(parse(report("""
                        {"slug":"comment-quality","status":"skipped","leads":0,"models":[],"durationMs":40}""")))
                .extracting(PrecomputeRunReport::durationMs)
                .containsOnlyNulls();
        assertThat(parse(report("""
                        {"slug":"comment-quality","status":"not-finished","leads":0,"durationMs":5}""")))
                .extracting(PrecomputeRunReport::durationMs)
                .containsOnlyNulls();
        assertThat(failures()).isZero();
    }

    /** The error only explains a failure, so a run that did not fail never carries one. */
    @ParameterizedTest
    @ValueSource(strings = {"ok", "timeout", "skipped"})
    void shouldLeaveTheErrorOutWhenTheScriptDidNotFail(String status) {
        String entry = """
                {"slug":"comment-quality","status":"%s","leads":0,"error":"Something broke"}""".formatted(status);

        assertThat(parse(report(entry)).getFirst().error()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"  \"", "42", "{}", "null"})
    void shouldKeepAFailedRunWithoutAnErrorWhenTheErrorIsNotOneLineOfText(String error) {
        List<PrecomputeRunReport> runs = parse(report("""
                {"slug":"comment-quality","status":"error","leads":0,"error":%s}""".formatted(error)));

        assertThat(runs.getFirst().status()).isEqualTo(PrecomputeRunStatus.FAILED);
        assertThat(runs.getFirst().error()).isNull();
        assertThat(failures()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"ok,OK", "skipped,SKIPPED", "error,FAILED", "timeout,TIMED_OUT", "not-finished,NOT_FINISHED"})
    void shouldMapEachRunnerStatusWhenTheReportNamesIt(String wire, PrecomputeRunStatus status) {
        assertThat(parse(report("""
                        {"slug":"comment-quality","status":"%s","leads":0}""".formatted(wire))))
                .extracting(PrecomputeRunReport::practiceSlug, PrecomputeRunReport::status)
                .contains(tuple("comment-quality", status));
        assertThat(failures()).isZero();
    }

    @Test
    void shouldMapEachModelSlotToThePurposeThatServesItWhenTheScriptDeclaredIt() {
        String entry = """
                {"slug":"comment-quality","status":"skipped","leads":0,"models":[
                  {"slot":"embedding","need":"optional","bound":false,"notRated":{"unavailable":4}},
                  {"slot":"reranking","need":"required","bound":false,"notRated":{}}]}""";

        assertThat(Objects.requireNonNull(parse(report(entry)).getFirst().models()))
                .extracting(PrecomputeModelUseDTO::purpose, PrecomputeModelUseDTO::bound)
                .containsExactly(
                        tuple(PrecomputeModelPurpose.PRACTICE_EMBEDDING, false),
                        tuple(PrecomputeModelPurpose.PRACTICE_RERANKING, false));
    }

    /**
     * The practices module cannot name {@link AgentPurpose}, so it mirrors the purposes of the precompute slots. A
     * purpose without its mirror leaves its slot unknown to the parser, which then drops every entry that uses it.
     */
    @Test
    void shouldMirrorEveryAgentPurposeOfAPrecomputeSlotWhenAPurposeIsAdded() {
        assertThat(Arrays.stream(PrecomputeModelPurpose.values()).map(Enum::name))
                .containsExactlyInAnyOrderElementsOf(
                        Stream.concat(Stream.of(AgentPurpose.PRACTICE_REVIEW), AgentPurpose.precompute().stream())
                                .map(Enum::name)
                                .toList());
    }

    @Test
    void shouldRecordAStagedScriptAsNotFinishedWhenAWholeReportDoesNotNameIt() {
        List<PrecomputeRunReport> runs = parse(report(positional("comment-quality", "ok", 2)));

        assertThat(runs)
                .contains(new PrecomputeRunReport(
                        "error-handling", PrecomputeRunStatus.NOT_FINISHED, 0, null, null, null));
        assertThat(failures()).isZero();
    }

    /** A truncated report left scripts out for its size, not because they did not finish. */
    @Test
    void shouldRecordNoRunForAStagedScriptWhenATruncatedReportDoesNotNameIt() {
        List<PrecomputeRunReport> runs =
                parse("{\"practices\":[" + positional("comment-quality", "ok", 2) + "],\"truncated\":true}");

        assertThat(runs).extracting(PrecomputeRunReport::practiceSlug).containsExactly("comment-quality");
        assertThat(failures()).isZero();
    }

    @Test
    void shouldDropAnEntryWhenTheJobDidNotStageItsScript() {
        List<PrecomputeRunReport> runs =
                parse(report(positional("comment-quality", "ok", 2), positional("not-admitted", "ok", 9)));

        assertThat(runs).extracting(PrecomputeRunReport::practiceSlug).doesNotContain("not-admitted");
        assertThat(failures()).isEqualTo(1d);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"slug\":\"comment-quality\",\"status\":\"crashed\",\"leads\":0,\"models\":[]}",
                "{\"slug\":\"comment-quality\",\"status\":\"ok\",\"leads\":-1,\"models\":[]}",
                "{\"slug\":\"comment-quality\",\"status\":\"ok\",\"leads\":\"2\",\"models\":[]}",
                "{\"slug\":\"comment-quality\",\"status\":\"ok\",\"models\":[]}",
                "{\"slug\":\"comment-quality\",\"status\":\"ok\",\"leads\":2,\"models\":{}}",
                "{\"slug\":\"comment-quality\",\"status\":\"ok\",\"leads\":2,\"models\":null}",
                "{\"slug\":\"comment-quality\",\"status\":\"ok\",\"leads\":2,\"models\":["
                        + "{\"slot\":\"vision\",\"need\":\"required\",\"bound\":true,\"notRated\":{}}]}",
                "{\"slug\":\"comment-quality\",\"status\":\"ok\",\"leads\":2,\"models\":["
                        + "{\"slot\":\"decision\",\"need\":\"nice-to-have\",\"bound\":true,\"notRated\":{}}]}",
                "{\"slug\":\"comment-quality\",\"status\":\"ok\",\"leads\":2,\"models\":["
                        + "{\"slot\":\"decision\",\"need\":\"required\",\"bound\":\"yes\",\"notRated\":{}}]}",
                "{\"slug\":\"comment-quality\",\"status\":\"ok\",\"leads\":2,\"models\":["
                        + "{\"slot\":\"decision\",\"need\":\"required\",\"bound\":true,\"notRated\":{\"bored\":1}}]}",
                "{\"slug\":\"comment-quality\",\"status\":\"ok\",\"leads\":2,\"models\":["
                        + "{\"slot\":\"decision\",\"need\":\"required\",\"bound\":true,\"notRated\":{}},"
                        + "{\"slot\":\"decision\",\"need\":\"optional\",\"bound\":true,\"notRated\":{}}]}",
                "{\"slug\":\"comment-quality\",\"status\":\"skipped\",\"leads\":3,\"models\":[]}",
                "{\"slug\":\"comment-quality\",\"status\":\"not-finished\",\"leads\":1}",
                "{\"slug\":\"comment-quality\",\"status\":\"not-finished\",\"leads\":0,\"models\":[]}"
            })
    void shouldDropAnEntryWithoutClaimingTheScriptDidNotFinishWhenItIsOutsideTheContract(String entry) {
        List<PrecomputeRunReport> runs = parse(report(entry, positional("error-handling", "ok", 1)));

        assertThat(runs).extracting(PrecomputeRunReport::practiceSlug).containsExactly("error-handling");
        assertThat(failures()).isEqualTo(1d);
    }

    @Test
    void shouldDropEveryEntryOfASlugWhenTheReportNamesItTwice() {
        List<PrecomputeRunReport> runs = parse(report(
                positional("comment-quality", "ok", 1),
                positional("comment-quality", "error", 0),
                positional("error-handling", "ok", 1)));

        assertThat(runs).extracting(PrecomputeRunReport::practiceSlug).containsExactly("error-handling");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "{\"practices\":[],\"truncated\":false} trailing",
                "{\"practices\":{},\"truncated\":false}",
                "{\"practices\":[]}",
                "{\"practices\":[],\"truncated\":\"no\"}",
                "[]",
                "{}"
            })
    void shouldRecordNothingWhenTheReportCannotBeRead(String report) {
        assertThat(parse(report)).isEmpty();
        assertThat(failures()).isEqualTo(1d);
    }

    @Test
    void shouldRecordNothingWhenThereIsNoReport() {
        assertThat(parser.parsePrecomputeReport(null, STAGED)).isEmpty();
        assertThat(parser.parsePrecomputeReport(new byte[0], STAGED)).isEmpty();
        assertThat(failures()).isZero();
    }

    @Test
    void shouldRefuseTheReportWhenItIsLargerThanItsLimit() {
        String entries = report(positional("comment-quality", "ok", 1));
        String atLimit = entries + " ".repeat(PiResultParser.PRECOMPUTE_REPORT_MAX_BYTES - entries.length());

        assertThat(parse(atLimit)).hasSize(STAGED.size());
        assertThat(parse(atLimit + " ")).isEmpty();
        assertThat(failures()).isEqualTo(1d);
    }

    @Test
    void shouldRefuseTheReportWhenItHasMoreEntriesThanItsLimit() {
        Set<String> staged = IntStream.rangeClosed(0, PiResultParser.PRECOMPUTE_REPORT_MAX_ENTRIES)
                .mapToObj(i -> "practice-" + i)
                .collect(Collectors.toSet());
        String atLimit = report(staged.stream()
                .sorted()
                .limit(PiResultParser.PRECOMPUTE_REPORT_MAX_ENTRIES)
                .map(slug -> positional(slug, "ok", 0))
                .toArray(String[]::new));
        String overLimit =
                report(staged.stream().map(slug -> positional(slug, "ok", 0)).toArray(String[]::new));

        assertThat(parser.parsePrecomputeReport(atLimit.getBytes(UTF_8), staged))
                .hasSize(staged.size());
        assertThat(parser.parsePrecomputeReport(overLimit.getBytes(UTF_8), staged))
                .isEmpty();
    }
}
