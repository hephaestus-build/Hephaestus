package de.tum.cit.aet.hephaestus.agent.sandbox;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInitializationException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;

/** The practice review attempt protocol of {@link SandboxAttemptRunner}, on a backend that runs nothing. */
class SandboxAttemptRunnerTest extends BaseUnitTest {

    private static final UUID JOB_ID = UUID.randomUUID();

    @Mock
    private SandboxWorkspaceManager workspaceManager;

    @TempDir
    Path temporary;

    private final SandboxGatewaySessions gatewaySessions = new SandboxGatewaySessions();
    private SimpleMeterRegistry meterRegistry;
    private FakeSandboxBackend backend;
    private SandboxAttemptRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        lenient()
                .when(workspaceManager.createInputTar(any(), any(), any()))
                .thenAnswer(invocation ->
                        Files.writeString(temporary.resolve("input-" + UUID.randomUUID() + ".tar"), "input"));
        meterRegistry = new SimpleMeterRegistry();
        backend = new FakeSandboxBackend(gatewaySessions);
        runner = new SandboxAttemptRunner(backend, workspaceManager, gatewaySessions, meterRegistry);
    }

    private static SandboxSpec spec(Map<String, String> environment) {
        return spec(environment, Map.of(".prompt", "test prompt".getBytes(UTF_8)), Map.of(), null);
    }

    private static SandboxSpec spec(
            Map<String, String> environment,
            Map<String, byte[]> files,
            Map<String, Path> filesOnDisk,
            @Nullable Instant workDeadline) {
        return new SandboxSpec(
                JOB_ID,
                null,
                "alpine:latest",
                List.of("echo", "hello"),
                environment,
                "test-token",
                SandboxTestLimits.ATTEMPT,
                workDeadline,
                files,
                filesOnDisk,
                List.of());
    }

    private static SandboxSpec createSpec() {
        return spec(Map.of("FOO", "bar"));
    }

    private double executions(String outcome) {
        return meterRegistry.counter("sandbox.executions", "outcome", outcome).count();
    }

    @Nested
    class Protocol {

        @Test
        void shouldInitializeThenRunTheRuntimeThenReadItsTranscriptThenRemoveTheSandbox() throws Exception {
            backend.runtimeTranscript = "hello\n";

            SandboxResult result = runner.execute(createSpec());

            assertThat(result.exitCode()).isZero();
            assertThat(result.timedOut()).isFalse();
            assertThat(result.outputFiles()).containsKey("result.json");
            assertThat(result.logs()).isEqualTo("hello\n");
            assertThat(result.duration()).isPositive();
            assertThat(backend.calls)
                    .containsExactly("launch", "observe INITIALIZED", "observe ENDED", "transcript RUNTIME", "remove");
            verify(workspaceManager).createInputTar(any(), any(), any());
        }

        @Test
        void shouldLaunchAReviewOfTheJobWithTheWrappedCommandAndTheUploadDeadline() throws Exception {
            runner.execute(createSpec());

            SandboxBackend.Launch launch = backend.launch();
            assertThat(launch.kind()).isEqualTo(SandboxBackend.Kind.REVIEW);
            assertThat(launch.id()).isEqualTo(JOB_ID);
            assertThat(launch.attempt()).isNull();
            assertThat(launch.image()).isEqualTo("alpine:latest");
            List<String> command = new ArrayList<>(SandboxLayout.RUNTIME_COMMAND);
            command.addAll(List.of("echo", "hello"));
            assertThat(launch.command()).isEqualTo(command);
            assertThat(launch.limits()).isEqualTo(SandboxTestLimits.ATTEMPT);
            long workDeadline = Long.parseLong(backend.environment.get("SANDBOX_WORK_DEADLINE_MS"));
            long uploadDeadline = Long.parseLong(backend.environment.get("SANDBOX_UPLOAD_DEADLINE_MS"));
            assertThat(uploadDeadline - workDeadline).isEqualTo(SandboxLayout.RESULT_UPLOAD_GRACE.toMillis());
            assertThat(launch.deadline())
                    .isNotNull()
                    .satisfies(deadline -> assertThat(deadline.toEpochMilli()).isEqualTo(uploadDeadline));
        }

        @Test
        void shouldBoundTheWorkByTheSpecsDeadlineWhenItIsEarlierThanTheLimits() throws Exception {
            Instant deadline = Instant.now().plus(Duration.ofMinutes(3));

            runner.execute(spec(Map.of(), Map.of(), Map.of(), deadline));

            assertThat(backend.environment)
                    .containsEntry("SANDBOX_WORK_DEADLINE_MS", Long.toString(deadline.toEpochMilli()));
        }

        @Test
        void shouldLaunchNothingWhenTheWorkDeadlinePassedBeforeTheStart() {
            assertThatThrownBy(() -> runner.execute(spec(Map.of(), Map.of(), Map.of(), Instant.now())))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("deadline exceeded");

            assertThat(backend.launches).isEmpty();
        }

        @Test
        void shouldPackTheDiskInputs() throws Exception {
            Map<String, Path> files = Map.of("inputs/source.txt", Path.of("/captured/source.txt"));

            runner.execute(spec(Map.of(), Map.of(), files, null));

            verify(workspaceManager).createInputTar(Map.of(), files, List.of());
        }

        @Test
        void shouldPackAnEmptyWorkspaceWhenThereAreNoFiles() throws Exception {
            runner.execute(spec(Map.of(), Map.of(), Map.of(), null));

            verify(workspaceManager).createInputTar(Map.of(), Map.of(), List.of());
        }
    }

    @Nested
    class Environment {

        @Test
        void shouldPointTheSandboxAtItsGatewayWithItsCredential() throws Exception {
            runner.execute(createSpec());

            // No per-provider path segment: the connection is identified from the authenticated token, not the URL.
            assertThat(backend.environment)
                    .containsEntry("LLM_PROXY_URL", FakeSandboxBackend.GATEWAY + "/internal/llm")
                    .containsEntry("LLM_PROXY_TOKEN", "test-token")
                    .doesNotContainKey("GATEWAY_URL");
            assertThat(backend.environment.get("SANDBOX_RUNTIME_URL"))
                    .startsWith(FakeSandboxBackend.GATEWAY + "/internal/llm/runtime/");
        }

        @Test
        void shouldKeepTheCallersSafeVariablesAndDropTheBlockedOnes() throws Exception {
            runner.execute(spec(Map.of(
                    "SAFE_VAR",
                    "ok",
                    "LD_PRELOAD",
                    "/evil.so",
                    "AWS_SECRET_ACCESS_KEY",
                    "leaked",
                    "DOCKER_HOST",
                    "tcp://evil",
                    "GOOGLE_CLOUD_PROJECT",
                    "stolen",
                    "HOME",
                    "/home/agent")));

            assertThat(backend.environment)
                    .containsEntry("SAFE_VAR", "ok")
                    .containsEntry("HOME", "/home/agent")
                    .doesNotContainKeys("LD_PRELOAD", "AWS_SECRET_ACCESS_KEY", "DOCKER_HOST", "GOOGLE_CLOUD_PROJECT");
        }

        @Test
        void shouldNeutraliseEveryGitConfigKeyThatCanRunCode() throws Exception {
            assertThat(SandboxAttemptRunner.GIT_SECURITY_CONFIGS)
                    .extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder(
                            "core.hooksPath",
                            "core.fsmonitor",
                            "core.sshCommand",
                            "core.askPass",
                            "core.editor",
                            "core.pager",
                            "core.gitProxy",
                            "sequence.editor",
                            "credential.helper",
                            "diff.external",
                            "protocol.ext.allow");

            runner.execute(createSpec());

            Map<String, String> env = backend.environment;
            assertThat(Integer.parseInt(env.get("GIT_CONFIG_COUNT")))
                    .isEqualTo(SandboxAttemptRunner.GIT_SECURITY_CONFIGS.size());
            for (int i = 0; i < SandboxAttemptRunner.GIT_SECURITY_CONFIGS.size(); i++) {
                var expected = SandboxAttemptRunner.GIT_SECURITY_CONFIGS.get(i);
                assertThat(env.get("GIT_CONFIG_KEY_" + i)).isEqualTo(expected.getKey());
                assertThat(env.get("GIT_CONFIG_VALUE_" + i)).isEqualTo(expected.getValue());
            }
            assertThat(env).containsEntry("GIT_TERMINAL_PROMPT", "0").containsEntry("GIT_ATTR_NOSYSTEM", "1");
        }

        @Test
        void shouldKeepTheEnforcedGitPromptValueWhenTheCallerSuppliesItsOwn() throws Exception {
            runner.execute(spec(Map.of("GIT_TERMINAL_PROMPT", "1")));

            assertThat(backend.environment).containsEntry("GIT_TERMINAL_PROMPT", "0");
        }
    }

    @Nested
    class Timeouts {

        @Test
        void shouldReportATimedOutRuntimeWithWhatItUploaded() throws Exception {
            backend.ended = new SandboxBackend.Observation(true, null);
            backend.result = Map.of("partial.json", "{}".getBytes(UTF_8));

            SandboxResult result = runner.execute(createSpec());

            assertThat(result.timedOut()).isTrue();
            assertThat(result.exitCode()).isEqualTo(SandboxLayout.EXIT_KILLED);
            assertThat(result.outputFiles()).containsKey("partial.json");
            assertThat(executions("timeout")).isEqualTo(1.0);
            assertThat(executions("success")).isZero();
        }

        @Test
        void shouldFailATimedOutInitializerAsARetryableInfrastructureFailureWithItsTranscriptTail() {
            backend.initialized = new SandboxBackend.Observation(true, null);
            backend.initializerTranscript = "downloading";

            assertThatThrownBy(() -> runner.execute(createSpec()))
                    .isInstanceOfSatisfying(SandboxInitializationException.class, failure -> {
                        assertThat(failure).isInstanceOf(SandboxInfrastructureException.class);
                        assertThat(failure.timedOut()).isTrue();
                        assertThat(failure.exitCode()).isNull();
                        assertThat(failure.transcriptTail()).isEqualTo("downloading");
                    });
            assertThat(backend.calls).doesNotContain("observe ENDED").endsWith("remove");
        }

        @ParameterizedTest
        @CsvSource({"137,true,true", "42,false,false", "43,false,false", "124,false,true"})
        void shouldPreserveTerminalFailureWhenTheSandboxUploadedNoResult(
                int exitCode, boolean backendTimedOut, boolean expectedTimedOut) throws Exception {
            backend.ended = new SandboxBackend.Observation(backendTimedOut, exitCode);
            backend.result = null;

            SandboxResult result = runner.execute(createSpec());

            assertThat(result.timedOut()).isEqualTo(expectedTimedOut);
            assertThat(result.exitCode()).isEqualTo(exitCode);
            assertThat(result.outputFiles()).isEmpty();
            assertThat(backend.calls).endsWith("remove");
        }
    }

    @Nested
    class Failures {

        @Test
        void shouldFailAndRemoveTheSandboxWhenItUploadedNoResultDespiteAZeroExit() {
            backend.result = null;

            assertThatThrownBy(() -> runner.execute(createSpec()))
                    .isInstanceOf(SandboxException.class)
                    .hasRootCauseMessage("Sandbox exited without uploading its result");
            assertThat(backend.calls).endsWith("remove");
        }

        @Test
        void shouldFailWithoutRunningTheRuntimeWhenTheInitializerFails() {
            backend.initialized = new SandboxBackend.Observation(false, 2);
            backend.initializerTranscript = "tar: work: Cannot utime: Operation not permitted\n";

            assertThatThrownBy(() -> runner.execute(createSpec()))
                    .isInstanceOfSatisfying(SandboxInitializationException.class, failure -> {
                        assertThat(failure).hasMessage("Sandbox workspace initialization failed: exit 2");
                        assertThat(failure.exitCode()).isEqualTo(2);
                        assertThat(failure.timedOut()).isFalse();
                        assertThat(failure.transcriptTail())
                                .isEqualTo("tar: work: Cannot utime: Operation not permitted");
                    });
            assertThat(backend.calls).doesNotContain("observe ENDED").endsWith("remove");
        }

        @Test
        void shouldKeepTheInitializerTranscriptOutOfTheLogButNameTheFailureInIt() {
            backend.initialized = new SandboxBackend.Observation(false, 2);
            backend.initializerTranscript = "tar: private-path-canary: Cannot utime\n";

            Logger logger = (Logger) LoggerFactory.getLogger(SandboxAttemptRunner.class);
            Level previous = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.TRACE);
            try {
                assertThatThrownBy(() -> runner.execute(createSpec()))
                        .isInstanceOf(SandboxInitializationException.class);
            } finally {
                logger.setLevel(previous);
                logger.detachAppender(appender);
                appender.stop();
            }

            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("canary");
                assertThat(String.valueOf(event.getKeyValuePairs())).doesNotContain("canary");
            });
            assertThat(appender.list)
                    .filteredOn(event -> Level.ERROR.equals(event.getLevel()))
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("Sandbox execution failed: errorType="
                            + SandboxInitializationException.class.getName()
                            + ", initializerExit=2, timedOut=false");
        }

        @Test
        void shouldFailWhenTheRuntimeNeverRan() {
            backend.ended = new SandboxBackend.Observation(false, null);

            assertThatThrownBy(() -> runner.execute(createSpec()))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("before its runtime ran");
        }

        @Test
        void shouldPassOnTheBackendsRefusalAndCountAFailure() {
            backend.onLaunch = () -> {
                throw new SandboxException("Network creation failed");
            };

            assertThatThrownBy(() -> runner.execute(createSpec()))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("Network creation failed");
            assertThat(executions("failure")).isEqualTo(1.0);
            assertThat(meterRegistry.timer("sandbox.execution.duration").count())
                    .isEqualTo(1);
        }

        @Test
        void shouldReadTheTranscriptBeforeTheRemovalWhenTheRuntimeFails() {
            backend.onEnded = () -> {
                throw new SandboxInfrastructureException("daemon lost");
            };

            assertThatThrownBy(() -> runner.execute(createSpec()))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("daemon lost");
            assertThat(backend.calls).endsWith("transcript RUNTIME", "remove");
        }

        @Test
        void shouldReturnTheResultAndCountTheCleanupFailureWhenTheRemovalFails() throws Exception {
            backend.removeFailure = new SandboxException("container stuck");

            SandboxResult result = runner.execute(createSpec());

            assertThat(result.exitCode()).isZero();
            assertThat(meterRegistry
                            .counter("sandbox.cleanup.failures", "step", "remove sandbox")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void shouldReportTheTranscriptSizeWithoutItsContentAtAnyLevelWhenExecutionFails() {
            backend.onEnded = () -> {
                throw new SandboxInfrastructureException("daemon lost");
            };
            String transcript = "task: private-prompt-canary\nread: private-source-canary\n";
            backend.runtimeTranscript = transcript;

            Logger logger = (Logger) LoggerFactory.getLogger(SandboxAttemptRunner.class);
            Level previous = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            // TRACE: no level of this logger may carry the transcript.
            logger.setLevel(Level.TRACE);
            try {
                assertThatThrownBy(() -> runner.execute(createSpec())).isInstanceOf(SandboxException.class);
            } finally {
                logger.setLevel(previous);
                logger.detachAppender(appender);
                appender.stop();
            }

            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("canary");
                assertThat(String.valueOf(event.getKeyValuePairs())).doesNotContain("canary");
            });
            ILoggingEvent withheld = appender.list.stream()
                    .filter(event -> Level.WARN.equals(event.getLevel())
                            && event.getFormattedMessage().contains("withheld"))
                    .findFirst()
                    .orElseThrow();
            assertThat(withheld.getKeyValuePairs())
                    .extracting(pair -> pair.key, pair -> pair.value)
                    .contains(
                            tuple("sandbox.transcript.characters", transcript.length()),
                            tuple("sandbox.transcript.lines", 2L));
        }
    }

    @Nested
    class Cancellation {

        @Test
        void shouldCancelAnAttemptWhoseCancellationRacedItsLaunch() {
            backend.onLaunch = () -> runner.cancel(JOB_ID);

            assertThatThrownBy(() -> runner.execute(createSpec()))
                    .isInstanceOf(SandboxCancelledException.class)
                    .hasMessageContaining("cancelled");
            assertThat(executions("cancelled")).isEqualTo(1.0);
            assertThat(executions("failure")).isZero();
            assertThat(backend.calls).doesNotContain("observe INITIALIZED").endsWith("remove");
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void shouldKillTheSandboxAndStayCancelledWhenAKillFails(boolean killFails) throws Exception {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch cancelled = new CountDownLatch(1);
            backend.onEnded = () -> {
                running.countDown();
                cancelled.await(5, TimeUnit.SECONDS);
            };
            var thrown = new AtomicReference<Exception>();
            Thread attempt = new Thread(() -> {
                try {
                    runner.execute(createSpec());
                } catch (Exception e) {
                    thrown.set(e);
                }
            });
            attempt.start();
            try {
                assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
                if (killFails) {
                    backend.killFailure = new SandboxInfrastructureException("daemon unavailable");
                    assertThatThrownBy(() -> runner.cancel(JOB_ID)).isInstanceOf(SandboxInfrastructureException.class);
                }
                runner.cancel(JOB_ID);
            } finally {
                cancelled.countDown();
                attempt.join(5000);
            }

            assertThat(attempt.isAlive()).isFalse();
            assertThat(backend.calls.stream().filter("kill"::equals)).hasSize(killFails ? 2 : 1);
            assertThat(thrown.get()).isInstanceOf(SandboxCancelledException.class);
        }

        @Test
        void shouldIgnoreTheCancellationOfAnUnknownJob() {
            assertThatCode(() -> runner.cancel(UUID.randomUUID())).doesNotThrowAnyException();
            assertThat(backend.calls).isEmpty();
        }
    }

    @Nested
    class Bookkeeping {

        @Test
        void shouldCountASuccess() throws Exception {
            runner.execute(createSpec());

            assertThat(executions("success")).isEqualTo(1.0);
            assertThat(executions("failure")).isZero();
        }

        @Test
        void shouldGaugeTheAttemptWhileItRuns() throws Exception {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            backend.onEnded = () -> {
                running.countDown();
                release.await(5, TimeUnit.SECONDS);
            };

            FutureTask<SandboxResult> execution = new FutureTask<>(() -> runner.execute(createSpec()));
            new Thread(execution).start();
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(meterRegistry.get("sandbox.containers.active").gauge().value())
                    .isEqualTo(1.0);
            release.countDown();
            execution.get(5, TimeUnit.SECONDS);
            assertThat(meterRegistry.get("sandbox.containers.active").gauge().value())
                    .isZero();
        }

        @Test
        void shouldRefuseASecondAttemptOfAJobThatRuns() throws Exception {
            CountDownLatch launched = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            backend.onLaunch = () -> {
                launched.countDown();
                release.await(5, TimeUnit.SECONDS);
            };
            FutureTask<SandboxResult> first = new FutureTask<>(() -> runner.execute(createSpec()));
            new Thread(first).start();
            assertThat(launched.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> runner.execute(createSpec()))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("already executing");

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
    }
}
