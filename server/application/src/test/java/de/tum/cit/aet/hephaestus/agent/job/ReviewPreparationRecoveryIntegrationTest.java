package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.AttemptInputs;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxResultListener;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.core.lock.ClusterLock;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ReviewPreparationRecoveryIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private AttemptInputs inputs;

    @TempDir
    Path root;

    @BeforeEach
    void cleanRows() {
        databaseTestUtils.cleanDatabase();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAPTURING", "READY"})
    void shouldContinueAnAbandonedInputPublicationOnAFreshEpoch(String receiptState) throws Exception {
        var job = running("abandoned-inputs");
        var abandoned = RuntimeJobTestData.fence(jdbc, job);
        Path source = Files.writeString(root.resolve("INDEX.json"), "Original frozen snapshot");
        byte[] original = Files.readAllBytes(source);
        // These commits survive the old process. Its provenance write and execution start never ran.
        inputs.record(job.getId(), job.getWorkspaceId(), job.getAttempt(), Map.of("INDEX.json", source));
        jdbc.update("""
                INSERT INTO person_evidence_copy(id,workspace_id,job_id,state,payload)
                VALUES(gen_random_uuid(),?,?,?,jsonb_build_object('attempt',0,'identities','[]'::jsonb,
                    'repositories','[]'::jsonb,'dependencies','COMPLETE'))
                """, job.getWorkspaceId(), job.getId(), receiptState);
        var handler = mock(DefaultReviewExecutionHandler.class);
        when(handler.prepareInputs(any())).thenAnswer(invocation -> {
            ReviewJob recaptured = invocation.getArgument(0);
            inputs.record(
                    recaptured.getId(),
                    recaptured.getWorkspaceId(),
                    recaptured.getAttempt(),
                    Map.of("INDEX.json", source));
            return new PreparedJobInputs(Map.of("INDEX.json", source), List.of(), List.of(), null, null);
        });
        var replacement = replacement(handler, new AgentResult(false, Map.of()));
        String subject = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT concurrency_key FROM agent_run WHERE id=? AND workspace_id=?",
                String.class,
                job.getRunId(),
                job.getWorkspaceId()));
        var sandbox = new AgentSandboxes.LiveSandbox(
                UUID.randomUUID(), job.getWorkspaceId(), subject, AgentSandboxKind.REVIEW);
        assertThatThrownBy(() -> replacement.context(new SandboxRun(sandbox, abandoned, AgentRunKind.REVIEW, 0)))
                .isInstanceOf(StaleRunException.class);
        var queued = jobs.findById(job.getId()).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(queued.spentAttempts()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT content FROM review_attempt_input WHERE job_id=? AND attempt=0",
                        byte[].class,
                        job.getId()))
                .isEqualTo(original);

        // Advance only the backoff clock; the normal run claim still assigns the next epoch.
        jdbc.update(
                "UPDATE agent_run SET available_at=clock_timestamp()-interval '1 second' WHERE id=?", job.getRunId());
        var next = Objects.requireNonNull(transactions.execute(tx -> {
            var claimed = runs.claimSubject(job.getWorkspaceId(), subject, Set.of(AgentRunKind.REVIEW))
                    .orElseThrow();
            // This storage test omits model admission; keep the job in the state of that real run claim.
            jdbc.update(
                    "UPDATE review_job SET status='RUNNING',started_at=clock_timestamp() WHERE id=? AND workspace_id=?",
                    job.getId(),
                    job.getWorkspaceId());
            return claimed;
        }));
        assertThat(next.fence().epoch()).isEqualTo(abandoned.epoch() + 1);
        var resumed = jobs.findById(job.getId()).orElseThrow();
        Files.writeString(source, "Next epoch snapshot");
        inputs.record(resumed.getId(), resumed.getWorkspaceId(), resumed.getAttempt(), Map.of("INDEX.json", source));
        assertThat(jdbc.queryForList(
                        "SELECT attempt FROM review_attempt_input WHERE job_id=? ORDER BY attempt",
                        Integer.class,
                        job.getId()))
                .containsExactly(0, 1);
        assertThat(jdbc.queryForObject(
                        "SELECT content FROM review_attempt_input WHERE job_id=? AND attempt=0",
                        byte[].class,
                        job.getId()))
                .isEqualTo(original);
    }

    @Test
    void shouldKeepProgressAndTheOriginalDeadlineWhenAColdReplicaReceivesTheResult() {
        var job = running("progressed-result");
        Instant started = Instant.now().minusSeconds(20);
        job.setExecutionStartedAt(started);
        job.setCapture(new JsonMapper()
                .createObjectNode()
                .put("attempt", job.getAttempt())
                .put("runId", job.getRunId().toString())
                .put("endedBefore", 2)
                .put("image", "agent:test"));
        jobs.saveAndFlush(job);
        var fence = RuntimeJobTestData.fence(jdbc, job);
        var handler = mock(DefaultReviewExecutionHandler.class);
        when(handler.hasStaged(any())).thenReturn(true);
        when(handler.endedPractices(any())).thenReturn(3);
        var replacement = replacement(handler, new AgentResult(false, Map.of()));
        var sandbox = new AgentSandboxes.LiveSandbox(
                UUID.randomUUID(),
                job.getWorkspaceId(),
                ReviewRunKind.subject(job.getReviewId()),
                AgentSandboxKind.REVIEW);
        var run = new SandboxRun(sandbox, fence, AgentRunKind.REVIEW, 0);
        assertThat(replacement.attempt(run).orElseThrow().workDeadline())
                .isEqualTo(ReviewAttemptPreparation.workDeadline(job, snapshot()));

        replacement.measured(run, new SandboxResult(1, Map.of(), "", false, Duration.ZERO));

        var queued = jobs.findById(job.getId()).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(queued.spentAttempts())
                .as("the original baseline still proves progress")
                .isZero();
        assertThat(Objects.requireNonNull(queued.getCapture())
                        .path("endedBefore")
                        .asInt())
                .isEqualTo(2);
    }

    @Test
    void shouldReceiveASuccessfulMeasurementOnAColdReplicaWithoutPreparingItsInputs() throws Exception {
        var job = running("cold-completed-result");
        job.setExecutionStartedAt(Instant.now());
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("a".repeat(64));
        job.setCapture(new JsonMapper()
                .createObjectNode()
                .put("attempt", job.getAttempt())
                .put("runId", job.getRunId().toString())
                .put("endedBefore", 0)
                .put("image", "agent:test"));
        jobs.saveAndFlush(job);
        var fence = RuntimeJobTestData.fence(jdbc, job);
        var sandbox = new AgentSandboxes.LiveSandbox(
                UUID.randomUUID(),
                job.getWorkspaceId(),
                ReviewRunKind.subject(job.getReviewId()),
                AgentSandboxKind.REVIEW);
        var run = new SandboxRun(sandbox, fence, AgentRunKind.REVIEW, 0);
        var replacement = replacement(
                mock(DefaultReviewExecutionHandler.class), new AgentResult(true, Map.of("runnerFinished", true)));
        byte[] body = resultArchive();
        String digest = "sha-256=:"
                + Base64.getEncoder()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(body)) + ":";
        var received = replacement.receive(run, new ByteArrayInputStream(body), digest);
        replacement.measured(run, new SandboxResult(0, received.files(), "", false, Duration.ZERO));
        var composing = jobs.findById(job.getId()).orElseThrow();
        assertThat(composing.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(composing.getRunId()).isNotEqualTo(fence.runId());
        assertThat(jdbc.queryForObject(
                        "SELECT kind FROM agent_run WHERE id=? AND workspace_id=?",
                        String.class,
                        composing.getRunId(),
                        job.getWorkspaceId()))
                .isEqualTo("REVIEW_COMPOSE");
        assertThat(Objects.requireNonNull(composing.getOutput())
                        .path("runnerFinished")
                        .asBoolean())
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM review_attempt_input WHERE job_id=?", Integer.class, job.getId()))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRetainColdReplicaTranscriptsOnlyWhileTheReceiptIsLive(boolean erased) throws Exception {
        var job = running("cold-transcript");
        jdbc.update("""
                INSERT INTO person_evidence_copy(id,workspace_id,job_id,state,payload)
                VALUES(gen_random_uuid(),?,?,?,jsonb_build_object('attempt',0,'identities','[]'::jsonb,
                    'repositories','[]'::jsonb,'dependencies','COMPLETE'))
                """, job.getWorkspaceId(), job.getId(), erased ? "ERASED" : "READY");
        var layout = new FabricLayout(root.toString());
        var files = new JobEvidenceFiles(
                layout, jobs, Clock.systemUTC(), context.getBean(EvidenceFolderPersonDataCatalog.class), inputs);
        @SuppressWarnings("unchecked")
        ObjectProvider<SandboxResultListener> listeners = mock(ObjectProvider.class);
        when(listeners.orderedStream()).thenAnswer(invocation -> Stream.of(files));
        var sessions = new SandboxGatewaySessions(listeners);
        byte[] body = resultArchive(Map.of("traces/0001.jsonl", "{\"type\":\"session\"}\n"));
        String digest = "sha-256=:"
                + Base64.getEncoder()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(body)) + ":";
        sessions.receiveAttempt(job, "agent:test", new ByteArrayInputStream(body), digest);
        Path jobRoot = layout.jobsRoot()
                .resolve(Long.toString(job.getWorkspaceId()))
                .resolve(job.getId().toString());
        if (erased) {
            assertThat(jobRoot).doesNotExist();
        } else {
            try (var paths = Files.walk(jobRoot)) {
                assertThat(paths.filter(path -> path.getFileName().toString().equals("0001.jsonl")))
                        .singleElement()
                        .satisfies(path -> assertThat(path).hasContent("{\"type\":\"session\"}\n"));
            }
            assertThat(sessions.jobIds()).isEmpty();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM review_attempt_input WHERE job_id=?", Integer.class, job.getId()))
                    .isZero();
        }
    }

    private static byte[] resultArchive() throws Exception {
        return resultArchive(Map.of("observations.json", "{}"));
    }

    private static byte[] resultArchive(Map<String, String> files) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var output = new TarArchiveOutputStream(buffer)) {
            for (var file : files.entrySet()) {
                byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry("out/" + file.getKey());
                entry.setSize(bytes.length);
                output.putArchiveEntry(entry);
                output.write(bytes);
                output.closeArchiveEntry();
            }
        }
        return buffer.toByteArray();
    }

    @Test
    void shouldRefuseRestorationIfErasureRevokesTheReceiptAfterCheckpointClassification() throws Exception {
        var job = running("erased-before-restore");
        Path source = Files.writeString(root.resolve("INDEX.json"), "Frozen snapshot");
        inputs.record(job.getId(), job.getWorkspaceId(), job.getAttempt(), Map.of("INDEX.json", source));
        job.setCapture(new JsonMapper()
                .createObjectNode()
                .put("attempt", job.getAttempt())
                .put("runId", job.getRunId().toString()));
        jobs.saveAndFlush(job);
        UUID copy = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO person_evidence_copy(id,workspace_id,job_id,state,payload)
                VALUES(?,?,?,'READY',jsonb_build_object('attempt',0,'identities','[]'::jsonb,
                    'repositories','[]'::jsonb,'dependencies','COMPLETE'))
                """, copy, job.getWorkspaceId(), job.getId());
        var files = context.getBean(JobEvidenceFiles.class);
        assertThat(files.preparation(job)).isEqualTo(JobEvidenceFiles.Preparation.PUBLISHED);
        jdbc.update(
                "UPDATE person_evidence_copy SET state='ERASED',payload='{}'::jsonb WHERE id=? AND workspace_id=?",
                copy,
                job.getWorkspaceId());
        assertThatThrownBy(() -> files.resume(job))
                .isInstanceOf(EvidenceFolderPersonDataCatalog.CopyUnavailableException.class);
    }

    private ReviewJob running(String slug) {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(slug));
        var job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setConfigSnapshot(snapshot().toJson(new JsonMapper()));
        job.prePersist();
        return jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
    }

    private ReviewSandboxRuns replacement(DefaultReviewExecutionHandler handler, AgentResult parsedResult) {
        var mapper = new JsonMapper();
        var files = context.getBean(JobEvidenceFiles.class);
        var agent = mock(PracticePiAdapter.class);
        when(agent.parseResult(any())).thenReturn(parsedResult);
        var telemetry = new ReviewJobTelemetry(new SimpleMeterRegistry(), Tracer.NOOP);
        var preparation = new ReviewAttemptPreparation(
                jobs,
                handler,
                agent,
                mock(AttemptTokenIssuer.class),
                SandboxTestLimits.PROPERTIES,
                transactions,
                mapper,
                telemetry,
                files);
        var reviewRuns = context.getBean(ReviewRunKind.class);
        var terminal = new ReviewJobTerminal(jobs, runs, mock(ReviewCompositionContinuation.class));
        var results = new ReviewAttemptResults(
                jobs,
                handler,
                agent,
                preparation,
                terminal,
                reviewRuns,
                mock(PracticeScriptRunRecorder.class),
                transactions,
                mapper,
                new SimpleMeterRegistry(),
                telemetry);
        return new ReviewSandboxRuns(
                mock(ReviewJobClaimService.class),
                preparation,
                new ReviewAttemptCustody(runs),
                terminal,
                jobs,
                mock(SandboxWorkspaceManager.class),
                new SandboxGatewaySessions(),
                transactions,
                mapper,
                results,
                reviewRuns,
                runs,
                files,
                context.getBean(ClusterLock.class));
    }

    private static ConfigSnapshot snapshot() {
        return new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                new FrozenModel(
                        LlmApiProtocol.OPENAI_COMPLETIONS,
                        "https://model.invalid/v1",
                        "model",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false),
                null,
                null,
                600);
    }
}
