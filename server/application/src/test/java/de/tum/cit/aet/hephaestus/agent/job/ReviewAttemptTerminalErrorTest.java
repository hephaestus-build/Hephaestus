package de.tum.cit.aet.hephaestus.agent.job;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The error a failed attempt records names why the runner stopped, from the files it uploaded, without its log. */
class ReviewAttemptTerminalErrorTest extends BaseUnitTest {

    private final PiResultParser parser = new PiResultParser(new ObjectMapper(), new SimpleMeterRegistry());

    private String errorOf(int exitCode, Map<String, String> files) {
        Map<String, byte[]> outputFiles = new HashMap<>();
        files.forEach((name, content) -> outputFiles.put(name, content.getBytes(UTF_8)));
        var sandbox = new SandboxResult(exitCode, outputFiles, "", false, Duration.ZERO);
        return ReviewAttemptResults.terminalError(ReviewJobStatus.FAILED, sandbox, parser.parse(sandbox), true);
    }

    private static String ended(String end) {
        return "{\"attempts\":[],\"turns\":[],\"practiceSources\":{},\"end\":" + end + "}";
    }

    private static final String NOTHING_RAN =
            "{\"eligible\":1,\"evaluated\":0,\"outcomes\":" + "[{\"practiceSlug\":\"a\",\"outcome\":\"NOT_REACHED\"}]}";

    private static final String NO_CALLS = "{\"model\":null,\"inputTokens\":0,\"outputTokens\":0,\"reasoningTokens\":0,"
            + "\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"costUsd\":0,\"totalCalls\":0}";

    @Test
    void shouldNameTheRunnersReasonWhenTheContainerExitsWithCodeOne() {
        assertThat(errorOf(
                        1,
                        Map.of("runner-debug.json", ended("{\"event\":\"review.failed\",\"reason\":\"no_practice\"}"))))
                .isEqualTo("The container exited with code 1. No practice produced a result.");
        assertThat(errorOf(
                        1,
                        Map.of(
                                "runner-debug.json",
                                ended("{\"event\":\"review.failed\",\"reason\":\"ceiling_before_start\"}"))))
                .isEqualTo("The container exited with code 1. The attempt's time ran out before the review started.");
    }

    @Test
    void shouldNameTheErrorKindOfAFatalStopAndNothingElseTheSandboxWrote() {
        assertThat(errorOf(
                        2, Map.of("runner-debug.json", ended("{\"event\":\"review.fatal\",\"error\":\"TypeError\"}"))))
                .isEqualTo("The container exited with code 2. The runner stopped on an unexpected error (TypeError).");
        assertThat(errorOf(
                        2,
                        Map.of(
                                "runner-debug.json",
                                ended("{\"event\":\"review.fatal\",\"error\":\"token ghp_secret in <b>text</b>\"}"))))
                .isEqualTo("The container exited with code 2. The runner stopped on an unexpected error.");
    }

    @Test
    void shouldSayOnlyTheExitCodeWhenTheRunnerRecordedNoReason() {
        assertThat(errorOf(1, Map.of())).isEqualTo("The container exited with code 1.");
        assertThat(errorOf(1, Map.of("runner-debug.json", ended("{\"event\":\"review.paced\"}"))))
                .isEqualTo("The container exited with code 1.");
    }

    @Test
    void shouldSayThatNothingRanWhenTheServerRefusedAReviewThatEvaluatedNoPracticeAndCalledNoModel() {
        assertThat(errorOf(
                        SandboxLayout.EXIT_SERVER_ERROR,
                        Map.of("practice-coverage.json", NOTHING_RAN, "usage.json", NO_CALLS)))
                .isEqualTo("The server answered the review's results with its own error before any practice was"
                        + " evaluated or any model was called.");
    }

    @Test
    void shouldNotClaimThatNothingRanWhenTheRunnerDidNotReportItsCalls() {
        assertThat(errorOf(SandboxLayout.EXIT_SERVER_ERROR, Map.of("practice-coverage.json", NOTHING_RAN)))
                .isEqualTo("The server answered the review's results with its own error.");
    }
}
