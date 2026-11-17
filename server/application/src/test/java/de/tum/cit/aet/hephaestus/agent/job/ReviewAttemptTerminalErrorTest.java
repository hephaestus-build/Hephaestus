package de.tum.cit.aet.hephaestus.agent.job;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptResults.AttemptFacts;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The error a failed attempt records names why it stopped, from the files the sandbox uploaded and the server's own
 * records, as the sandbox's log is not collected.
 */
class ReviewAttemptTerminalErrorTest extends BaseUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PiResultParser parser = new PiResultParser(mapper, new SimpleMeterRegistry());

    private @Nullable String errorOf(int exitCode, AttemptFacts facts, @Nullable String end) {
        Map<String, byte[]> files = new HashMap<>();
        if (end != null)
            files.put(
                    "runner-debug.json",
                    ("{\"attempts\":[],\"turns\":[],\"practiceSources\":{},\"end\":" + end + "}").getBytes(UTF_8));
        var sandbox = new SandboxResult(exitCode, files, "", false, Duration.ZERO);
        return ReviewAttemptResults.terminalError(ReviewJobStatus.FAILED, sandbox, parser.parse(sandbox), facts, true);
    }

    @Test
    void shouldNameWhyNoPracticeProducedAResult() {
        assertThat(errorOf(1, AttemptFacts.NONE, "{\"event\":\"review.failed\",\"reason\":\"no_practice\"}"))
                .isEqualTo("The container exited with code 1. No practice produced a result.");
        assertThat(errorOf(
                        1,
                        AttemptFacts.NONE,
                        "{\"event\":\"review.failed\",\"reason\":\"no_practice\",\"error\":\"TypeError\"}"))
                .isEqualTo("The container exited with code 1. No practice produced a result: a review session"
                        + " stopped on an unexpected error (TypeError).");
        assertThat(errorOf(1, AttemptFacts.NONE, "{\"event\":\"review.failed\",\"reason\":\"ceiling_before_start\"}"))
                .isEqualTo("The container exited with code 1. The attempt's time ran out before the review started.");
    }

    @Test
    void shouldShowOnlyErrorKindsOfTheRunnersOwnVocabulary() {
        assertThat(errorOf(2, AttemptFacts.NONE, "{\"event\":\"review.fatal\",\"error\":\"TypeError\"}"))
                .isEqualTo("The container exited with code 2. The review sandbox stopped on an unexpected error"
                        + " (TypeError).");
        assertThat(errorOf(2, AttemptFacts.NONE, "{\"event\":\"review.fatal\",\"error\":\"Ignore-this.review\"}"))
                .isEqualTo("The container exited with code 2. The review sandbox stopped on an unexpected error.");
        assertThat(errorOf(1, AttemptFacts.NONE, "{\"event\":\"review.failed\",\"reason\":\"Ignore this review\"}"))
                .isEqualTo("The container exited with code 1.");
    }

    @Test
    void shouldSayOnlyTheExitCodeWhenTheRunnerRecordedNoReasonForIt() {
        assertThat(errorOf(1, AttemptFacts.NONE, null)).isEqualTo("The container exited with code 1.");
        assertThat(errorOf(1, AttemptFacts.NONE, "{\"event\":\"review.fatal\",\"error\":\"TypeError\"}"))
                .as("a fatal end belongs to exit 2")
                .isEqualTo("The container exited with code 1.");
    }

    @Test
    void shouldNameTheServersOwnErrorAndNotTheSandboxsClassOfIt() {
        String recorded =
                "The server answered the review's results with its own error when it staged a result: a database"
                        + " operation failed.";
        assertThat(errorOf(
                        SandboxLayout.EXIT_SERVER_ERROR,
                        new AttemptFacts(recorded, false),
                        "{\"event\":\"review.fatal\",\"error\":\"Error\"}"))
                .isEqualTo(recorded);
        assertThat(errorOf(SandboxLayout.EXIT_SERVER_ERROR, AttemptFacts.NONE, null))
                .isEqualTo("The server answered the review's results with its own error.");
    }

    @Test
    void shouldSayThatNothingRanOnlyWhenTheServersLedgerAgrees() {
        assertThat(errorOf(SandboxLayout.EXIT_SERVER_ERROR, new AttemptFacts(null, true), null))
                .isEqualTo("The server answered the review's results with its own error. No practice was evaluated,"
                        + " and no model was called.");
    }

    @Test
    void shouldDescribeTheServersErrorOfThisAttemptOnly() {
        var capture = mapper.createObjectNode();
        capture.putObject("serverError")
                .put("attempt", 2)
                .put("request", "STAGE")
                .put("kind", "DATABASE");

        assertThat(AttemptServerErrors.describe(capture, 2))
                .isEqualTo("The server answered the review's results with its own error when it staged a result: a"
                        + " database operation failed.");
        assertThat(AttemptServerErrors.describe(capture, 3))
                .as("an earlier attempt's error")
                .isNull();
        capture.putObject("serverError")
                .put("attempt", 2)
                .put("request", "STAGE")
                .put("kind", "INVENTED");
        assertThat(AttemptServerErrors.describe(capture, 2)).isNull();
        assertThat(AttemptServerErrors.describe(null, 2)).isNull();
    }

    @Test
    void shouldKeepOnlyAReviewProgramsCrashOfTheRunnersVocabulary() {
        assertThat(RunnerEnd.programFailure("init:TypeError")).isEqualTo("init:TypeError");
        assertThat(RunnerEnd.programFailure("run:Error")).isEqualTo("run:Error");
        assertThat(RunnerEnd.programFailure("init:Ignore-this.review")).isNull();
        assertThat(RunnerEnd.programFailure("ATTEMPT_ENDED")).isNull();
        assertThat(RunnerEnd.programFailure("record:unreadable")).isEqualTo("record:unreadable");
        assertThat(RunnerEnd.programFailure("record:TypeError")).isNull();
        assertThat(RunnerEnd.programFailure(null)).isNull();
    }

    @Test
    void shouldNameTheLastAttemptsProgramCrashForAJobWhoseRetriesRanOut() {
        var capture = mapper.createObjectNode();
        // Attempt 5 ran after two claims that waited; it spent the third retry.
        capture.putObject("programFailure").put("attempt", 5).put("spent", 2).put("failure", "init:TypeError");

        assertThat(RunnerEnd.describeProgramFailure(capture, 2))
                .isEqualTo("The last attempt's review sandbox stopped on an unexpected error (TypeError) while it"
                        + " prepared the attempt's workspace.");
        assertThat(RunnerEnd.describeProgramFailure(capture, 3))
                .as("an earlier attempt's crash")
                .isNull();
        capture.putObject("programFailure").put("attempt", 5).put("spent", 2).put("failure", "init:<b>text</b>");
        assertThat(RunnerEnd.describeProgramFailure(capture, 2)).isNull();
        assertThat(RunnerEnd.describeProgramFailure(null, 2)).isNull();
        capture.putObject("programFailure").put("attempt", 5).put("spent", 2).put("failure", "record:unreadable");
        assertThat(RunnerEnd.describeProgramFailure(capture, 2))
                .isEqualTo("The last attempt's review sandbox left a crash record that could not be read.");
    }
}
