package de.tum.cit.aet.hephaestus.agent.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser.PracticeScriptRunReport;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptKind;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptRunStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class PiResultParserPracticeScriptReportTest extends BaseUnitTest {

    private static final Map<String, ScriptKind> ADMITTED =
            Map.of("comment-quality", ScriptKind.BUILTIN, "error-handling", ScriptKind.CUSTOM);

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private static final String ERROR_PLACEHOLDER = "\"@@error@@\"";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PiResultParser parser;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        parser = new PiResultParser(new ObjectMapper(), meterRegistry);
    }

    private List<PracticeScriptRunReport> parse(String report) {
        return parser.parsePracticeScriptReport(report.getBytes(UTF_8), ADMITTED);
    }

    private double failures() {
        return meterRegistry
                .counter("agent.pi.result.parse.failure", "stage", "practice_script_report")
                .count();
    }

    private static String report(String... entries) {
        return report(false, entries);
    }

    private static String report(boolean truncated, String... entries) {
        return "{\"schemaVersion\":3,\"truncated\":" + truncated + ",\"practices\":[" + String.join(",", entries)
                + "]}";
    }

    private static ObjectNode entry(String slug, String status, @Nullable String stopReason) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("practiceSlug", slug);
        ObjectNode program = entry.putObject("program");
        if (ADMITTED.get(slug) == ScriptKind.CUSTOM) {
            program.put("kind", "custom");
            program.put("digest", DIGEST);
        } else {
            program.put("kind", "builtin");
            program.putNull("digest");
        }
        entry.put("status", status);
        if (stopReason == null) {
            entry.putNull("stopReason");
        } else {
            entry.put("stopReason", stopReason);
        }
        ObjectNode calls = entry.putObject("calls");
        PiResultParser.CALL_KINDS.forEach(kind -> calls.put(kind, 0));
        ObjectNode coverage = entry.putObject("coverageReached");
        Stream.of("reads", "truncatedReads", "failedReads").forEach(count -> coverage.put(count, 0));
        if ("not-reached".equals(status)) {
            entry.putNull("durationMs");
        } else {
            entry.put("durationMs", 10);
        }
        return entry;
    }

    private static PracticeScriptRunReport run(List<PracticeScriptRunReport> runs, String slug) {
        return runs.stream()
                .filter(run -> run.practiceSlug().equals(slug))
                .findFirst()
                .orElseThrow();
    }

    private static String ok(String slug) {
        return entry(slug, "ok", null).toString();
    }

    private static String failedWithError(String slug, String error) {
        return entry(slug, "failed", "error")
                .put("error", "@@error@@")
                .toString()
                .replace(ERROR_PLACEHOLDER, asciiJson(error));
    }

    private static String asciiJson(String text) {
        return text.chars()
                .mapToObj(c ->
                        c >= ' ' && c <= '~' && c != '"' && c != '\\' ? Character.toString(c) : "\\u%04x".formatted(c))
                .collect(Collectors.joining("", "\"", "\""));
    }

    @Test
    void shouldReadEveryAdmittedPracticeWhenTheSharedExecutionReportIsParsed() throws IOException {
        byte[] fixture;
        try (InputStream in = getClass().getResourceAsStream("/practice-results/execution-report.json")) {
            fixture = Objects.requireNonNull(in, "practice-results/execution-report.json")
                    .readAllBytes();
        }
        Map<String, ScriptKind> admitted = Map.of(
                "avoids-insecure-defaults-and-over-broad-permissions", ScriptKind.CUSTOM,
                "commit-subjects-explain-each-change", ScriptKind.BUILTIN,
                "handles-errors-instead-of-swallowing-them", ScriptKind.CUSTOM,
                "leaves-the-code-clean-with-intent-revealing-comments", ScriptKind.BUILTIN,
                "makes-ui-accessible-by-default", ScriptKind.CUSTOM,
                "ships-tests-with-the-change", ScriptKind.CUSTOM,
                "validates-and-escapes-untrusted-input", ScriptKind.BUILTIN);

        List<PracticeScriptRunReport> runs = parser.parsePracticeScriptReport(fixture, admitted);

        assertThat(failures()).isZero();
        assertThat(runs)
                .extracting(
                        PracticeScriptRunReport::practiceSlug,
                        PracticeScriptRunReport::status,
                        PracticeScriptRunReport::programKind,
                        PracticeScriptRunReport::stopReason)
                .containsExactly(
                        tuple(
                                "avoids-insecure-defaults-and-over-broad-permissions",
                                ScriptRunStatus.WITHHELD,
                                ScriptKind.CUSTOM,
                                ScriptStopReason.UNAVAILABLE),
                        tuple(
                                "commit-subjects-explain-each-change",
                                ScriptRunStatus.NOT_REACHED,
                                ScriptKind.BUILTIN,
                                ScriptStopReason.DEADLINE),
                        tuple("handles-errors-instead-of-swallowing-them", ScriptRunStatus.OK, ScriptKind.CUSTOM, null),
                        tuple(
                                "leaves-the-code-clean-with-intent-revealing-comments",
                                ScriptRunStatus.OK,
                                ScriptKind.BUILTIN,
                                null),
                        tuple("makes-ui-accessible-by-default", ScriptRunStatus.OK, ScriptKind.CUSTOM, null),
                        tuple(
                                "ships-tests-with-the-change",
                                ScriptRunStatus.FAILED,
                                ScriptKind.CUSTOM,
                                ScriptStopReason.INVALID_SCRIPT),
                        tuple("validates-and-escapes-untrusted-input", ScriptRunStatus.OK, ScriptKind.BUILTIN, null));
        assertThat(runs).extracting(PracticeScriptRunReport::status).doesNotContain(ScriptRunStatus.NOT_FINISHED);
        PracticeScriptRunReport custom = run(runs, "handles-errors-instead-of-swallowing-them");
        assertThat(custom.programDigest())
                .isEqualTo("b71c3e9a5d2f8046c1e7a9b3d5f2e8c6a4b1d7f3e9c2a5b8d0f6e1c4a7b3d9e2");
        assertThat(custom.calls()).isEqualTo(Map.of("review", 1));
        assertThat(custom.coverageReached()).isEqualTo(Map.of("reads", 9, "truncatedReads", 1, "failedReads", 0));
        assertThat(custom.durationMs()).isEqualTo(61234);
        assertThat(run(runs, "leaves-the-code-clean-with-intent-revealing-comments")
                        .programDigest())
                .isNull();
        assertThat(run(runs, "commit-subjects-explain-each-change").durationMs())
                .isNull();
        assertThat(run(runs, "ships-tests-with-the-change").error())
                .startsWith("This practice script uses the retired leads contract (meta.version).");
    }

    @Test
    void shouldKeepTheFirstLineOfAFailedProgramsErrorCutToItsLimit() {
        // Characters outside the BMP count once each, as PostgreSQL counts them.
        String longLine = "TypeError: " + "🙂".repeat(PiResultParser.PRACTICE_SCRIPT_ERROR_MAX_LENGTH);
        List<PracticeScriptRunReport> runs = parse(report(
                failedWithError("comment-quality", longLine + "\n    at decide (script.ts:4:9)"),
                failedWithError("error-handling", "Cannot read properties of undefined")));

        assertThat(runs)
                .extracting(PracticeScriptRunReport::error)
                .containsExactly(
                        longLine.substring(
                                0, longLine.offsetByCodePoints(0, PiResultParser.PRACTICE_SCRIPT_ERROR_MAX_LENGTH)),
                        "Cannot read properties of undefined");
        assertThat(failures()).isZero();
    }

    @Test
    void shouldKeepTheErrorLineThatTheReportWriterKeepsWhenBothReadTheSameError() throws IOException {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/agent/practice-script-error-lines.json")) {
            cases = new ObjectMapper()
                    .readTree(Objects.requireNonNull(in, "agent/practice-script-error-lines.json")
                            .readAllBytes());
        }
        List<String> slugs = IntStream.range(0, cases.size())
                .mapToObj(index -> "case-%02d".formatted(index))
                .toList();
        String report = report(IntStream.range(0, cases.size())
                .mapToObj(index -> failedWithError(
                        slugs.get(index), cases.get(index).path("error").asString()))
                .toArray(String[]::new));

        List<PracticeScriptRunReport> runs = parser.parsePracticeScriptReport(
                report.getBytes(UTF_8),
                slugs.stream().collect(Collectors.toMap(slug -> slug, slug -> ScriptKind.BUILTIN)));

        assertThat(runs)
                .extracting(PracticeScriptRunReport::practiceSlug, PracticeScriptRunReport::error)
                .containsExactlyElementsOf(IntStream.range(0, cases.size())
                        .mapToObj(index -> tuple(
                                slugs.get(index),
                                cases.get(index).path("line").isString()
                                        ? cases.get(index).path("line").asString()
                                        : null))
                        .toList());
        assertThat(failures()).isZero();
    }

    @Test
    void shouldKeepOnlyTextPostgresqlCanStoreWhenTheErrorHoldsANulOrAnUnpairedSurrogate() {
        List<PracticeScriptRunReport> runs = parse(report(
                failedWithError("comment-quality", "a\u0000b\tc"),
                failedWithError("error-handling", "bad \uD800 thing")));

        assertThat(runs)
                .extracting(PracticeScriptRunReport::status, PracticeScriptRunReport::error)
                .containsExactly(tuple(ScriptRunStatus.FAILED, "a b c"), tuple(ScriptRunStatus.FAILED, null));
        assertThat(failures()).isZero();
    }

    @Test
    void shouldKeepTheRunnersReviewSessionCountsWhenTheReportNamesThemAndNoneOtherwise() {
        List<PracticeScriptRunReport> runs = parse(report(
                entry("comment-quality", "failed", "off-format")
                        .set(
                                "reviewSessions",
                                MAPPER.readTree("{\"modelCalls\":6,\"evidenceRefusals\":4,\"formatRefusals\":1}"))
                        .toString(),
                ok("error-handling")));

        assertThat(run(runs, "comment-quality").reviewSessions())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("modelCalls", 6, "evidenceRefusals", 4, "formatRefusals", 1));
        // A report from before the runner counted them says nothing, which is not zero.
        assertThat(run(runs, "error-handling").reviewSessions()).isNull();
        assertThat(failures()).isZero();
    }

    @Test
    void shouldKeepHowLongAProgramRanWhenTheRunnerSaysAndLeaveItOutOtherwise() {
        List<PracticeScriptRunReport> runs = parse(report(
                entry("comment-quality", "ok", null).put("durationMs", 4200).toString(),
                entry("error-handling", "withheld", "unavailable")
                        .put("durationMs", -1)
                        .toString()));

        assertThat(runs).extracting(PracticeScriptRunReport::durationMs).containsExactly(4200, null);
        // A program that did not start took no time, whatever the runner wrote.
        assertThat(parse(report(entry("comment-quality", "not-reached", "deadline")
                        .put("durationMs", 40)
                        .toString())))
                .filteredOn(run -> run.practiceSlug().equals("comment-quality"))
                .extracting(PracticeScriptRunReport::durationMs)
                .containsOnlyNulls();
        assertThat(failures()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"ok,", "withheld,unavailable", "not-reached,deadline"})
    void shouldLeaveTheErrorOutWhenTheProgramDidNotFail(String status, @Nullable String stopReason) {
        String entry = entry("comment-quality", status, stopReason)
                .put("error", "Something broke")
                .toString();

        assertThat(parse(report(entry)).getFirst().error()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"  \"", "42", "{}", "null"})
    void shouldKeepAFailedRunWithoutAnErrorWhenTheErrorIsNotOneLineOfText(String error) {
        String entry = entry("comment-quality", "failed", "error")
                .put("error", "@@error@@")
                .toString()
                .replace(ERROR_PLACEHOLDER, error);

        List<PracticeScriptRunReport> runs = parse(report(entry));

        assertThat(runs.getFirst().status()).isEqualTo(ScriptRunStatus.FAILED);
        assertThat(runs.getFirst().error()).isNull();
        assertThat(failures()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "ok,,OK",
        "withheld,unavailable,WITHHELD",
        "failed,error,FAILED",
        "not-reached,deadline,NOT_REACHED",
        "not-finished,cancelled,NOT_FINISHED"
    })
    void shouldMapEachRunnerStatusWhenTheReportNamesIt(
            String wire, @Nullable String stopReason, ScriptRunStatus status) {
        assertThat(parse(report(entry("comment-quality", wire, stopReason).toString())))
                .extracting(PracticeScriptRunReport::practiceSlug, PracticeScriptRunReport::status)
                .contains(tuple("comment-quality", status));
        assertThat(failures()).isZero();
    }

    @ParameterizedTest
    @EnumSource(ScriptStopReason.class)
    void shouldMapEachStopReasonWhenAFailedRunNamesIt(ScriptStopReason stopReason) {
        assertThat(parse(report(entry(
                                "comment-quality",
                                "failed",
                                stopReason == ScriptStopReason.INVALID_SCRIPT
                                        ? "invalid-program"
                                        : stopReason
                                                .name()
                                                .toLowerCase(Locale.ROOT)
                                                .replace('_', '-'))
                        .toString())))
                .extracting(PracticeScriptRunReport::practiceSlug, PracticeScriptRunReport::stopReason)
                .contains(tuple("comment-quality", stopReason));
        assertThat(failures()).isZero();
    }

    @Test
    void shouldRecordAnAdmittedPracticeAsNotFinishedWhenAWholeReportDoesNotNameIt() {
        List<PracticeScriptRunReport> runs = parse(report(ok("comment-quality")));

        assertThat(runs)
                .contains(new PracticeScriptRunReport(
                        "error-handling",
                        ScriptRunStatus.NOT_FINISHED,
                        ScriptKind.CUSTOM,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
        assertThat(failures()).isZero();
    }

    @Test
    void shouldRecordNoRunForAnAdmittedPracticeWhenATruncatedReportDoesNotNameIt() {
        List<PracticeScriptRunReport> runs = parse(report(true, ok("comment-quality")));

        assertThat(runs).extracting(PracticeScriptRunReport::practiceSlug).containsExactly("comment-quality");
        assertThat(failures()).isZero();
    }

    @Test
    void shouldDropAnEntryWhenTheJobDidNotAdmitItsPractice() {
        List<PracticeScriptRunReport> runs =
                parse(report(ok("comment-quality"), ok("error-handling"), ok("not-admitted")));

        assertThat(runs).extracting(PracticeScriptRunReport::practiceSlug).doesNotContain("not-admitted");
        assertThat(failures()).isEqualTo(1d);
    }

    static Stream<Consumer<ObjectNode>> entriesOutsideTheContract() {
        return Stream.<Consumer<ObjectNode>>of(
                entry -> entry.put("status", "crashed"),
                entry -> entry.set("program", MAPPER.readTree("{\"kind\":\"custom\",\"digest\":\"" + DIGEST + "\"}")),
                entry -> entry.set("program", MAPPER.readTree("{\"kind\":\"builtin\",\"digest\":\"" + DIGEST + "\"}")),
                entry -> entry.set("program", MAPPER.readTree("{\"kind\":\"script\",\"digest\":null}")),
                entry -> entry.remove("program"),
                // Ok exactly when the program stopped for no reason.
                entry -> entry.put("stopReason", "error"),
                entry -> entry.put("status", "failed"),
                entry -> entry.put("status", "failed").put("stopReason", "bored?"),
                // A program that did not start made no call.
                entry -> entry.put("status", "not-reached")
                        .put("stopReason", "deadline")
                        .set("calls", MAPPER.readTree("{\"review\":1}")),
                entry -> entry.remove("calls"),
                entry -> entry.set("calls", MAPPER.readTree("{\"review\":-1}")),
                // The direct model calls of an earlier report version are no longer part of the contract.
                entry -> entry.set("calls", MAPPER.readTree("{\"review\":0,\"decision\":0}")),
                entry -> entry.remove("coverageReached"),
                entry -> entry.set(
                        "coverageReached", MAPPER.readTree("{\"reads\":\"2\",\"truncatedReads\":0,\"failedReads\":0}")),
                // The model slots of an earlier report version are no longer part of the contract.
                entry -> entry.putArray("models"),
                entry -> entry.set("reviewSessions", MAPPER.readTree("{\"modelCalls\":3,\"evidenceRefusals\":0}")),
                entry -> entry.set(
                        "reviewSessions",
                        MAPPER.readTree("{\"modelCalls\":-1,\"evidenceRefusals\":0,\"formatRefusals\":0}")),
                // A program that did not start opened no review session.
                entry -> entry.put("status", "not-reached")
                        .put("stopReason", "deadline")
                        .set(
                                "reviewSessions",
                                MAPPER.readTree("{\"modelCalls\":2,\"evidenceRefusals\":0,\"formatRefusals\":0}")),
                entry -> entry.put("leads", 2));
    }

    @ParameterizedTest
    @MethodSource("entriesOutsideTheContract")
    void shouldDropAnEntryWithoutClaimingTheProgramDidNotFinishWhenItIsOutsideTheContract(Consumer<ObjectNode> defect) {
        ObjectNode entry = entry("comment-quality", "ok", null);
        defect.accept(entry);

        List<PracticeScriptRunReport> runs = parse(report(entry.toString(), ok("error-handling")));

        assertThat(runs).extracting(PracticeScriptRunReport::practiceSlug).containsExactly("error-handling");
        assertThat(failures()).isEqualTo(1d);
    }

    @Test
    void shouldDropTheEntryWhenACustomProgramNamesNoDigest() {
        ObjectNode entry = entry("error-handling", "ok", null);
        entry.set("program", MAPPER.readTree("{\"kind\":\"custom\",\"digest\":null}"));

        List<PracticeScriptRunReport> runs = parse(report(ok("comment-quality"), entry.toString()));

        assertThat(runs).extracting(PracticeScriptRunReport::practiceSlug).containsExactly("comment-quality");
        assertThat(failures()).isEqualTo(1d);
    }

    @Test
    void shouldDropEveryEntryOfASlugWhenTheReportNamesItTwice() {
        List<PracticeScriptRunReport> runs =
                parse(report(ok("comment-quality"), failedWithError("comment-quality", "boom"), ok("error-handling")));

        assertThat(runs).extracting(PracticeScriptRunReport::practiceSlug).containsExactly("error-handling");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "{\"schemaVersion\":3,\"practices\":[],\"truncated\":false} trailing",
                "{\"schemaVersion\":3,\"practices\":{},\"truncated\":false}",
                "{\"schemaVersion\":3,\"practices\":[]}",
                "{\"schemaVersion\":3,\"practices\":[],\"truncated\":\"no\"}",
                "{\"practices\":[],\"truncated\":false}",
                "{\"schemaVersion\":2,\"practices\":[],\"truncated\":false}",
                "{\"schemaVersion\":\"3\",\"practices\":[],\"truncated\":false}",
                "[]",
                "{}"
            })
    void shouldRecordNothingWhenTheReportIsInvalid(String report) {
        assertThat(parse(report)).isEmpty();
        assertThat(failures()).isEqualTo(1d);
    }

    @Test
    void shouldRecordNothingWhenThereIsNoReport() {
        assertThat(parser.parsePracticeScriptReport(null, ADMITTED)).isEmpty();
        assertThat(parser.parsePracticeScriptReport(new byte[0], ADMITTED)).isEmpty();
        assertThat(failures()).isZero();
    }

    @Test
    void shouldRefuseTheReportWhenItIsLargerThanItsLimit() {
        String entries = report(ok("comment-quality"));
        String atLimit = entries + " ".repeat(PiResultParser.PRACTICE_SCRIPT_REPORT_MAX_BYTES - entries.length());

        assertThat(parse(atLimit)).hasSize(ADMITTED.size());
        assertThat(parse(atLimit + " ")).isEmpty();
        assertThat(failures()).isEqualTo(1d);
    }

    @Test
    void shouldRefuseTheReportWhenItHasMoreEntriesThanItsLimit() {
        Map<String, ScriptKind> admitted = IntStream.rangeClosed(0, PiResultParser.PRACTICE_SCRIPT_REPORT_MAX_ENTRIES)
                .mapToObj(i -> "p-" + i)
                .collect(Collectors.toMap(slug -> slug, slug -> ScriptKind.BUILTIN));
        // Short entries, so that the entry limit and not the size limit refuses the larger report.
        Function<String, String> shortEntry = slug -> """
            {"practiceSlug":"%s","program":{"kind":"builtin","digest":null},"status":"ok",\
            "stopReason":null,"calls":{"review":0},\
            "coverageReached":{"reads":0,"truncatedReads":0,"failedReads":0}}\
            """.formatted(slug);
        String atLimit = report(admitted.keySet().stream()
                .sorted()
                .limit(PiResultParser.PRACTICE_SCRIPT_REPORT_MAX_ENTRIES)
                .map(shortEntry)
                .toArray(String[]::new));
        String overLimit = report(admitted.keySet().stream().map(shortEntry).toArray(String[]::new));

        assertThat(parser.parsePracticeScriptReport(atLimit.getBytes(UTF_8), admitted))
                .hasSize(admitted.size());
        assertThat(parser.parsePracticeScriptReport(overLimit.getBytes(UTF_8), admitted))
                .isEmpty();
    }
}
