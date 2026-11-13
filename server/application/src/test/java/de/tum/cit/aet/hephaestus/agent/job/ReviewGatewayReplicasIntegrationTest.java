package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.AgentControllerAdvice;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.AttemptInputs;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndexBuilder;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentGatewayErrorFixtures;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentRunController;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConfiguration;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunArgumentResolver;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeAgentRequest;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAccounting;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxOwnerFixtures;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.lock.ClusterLock;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two gateway processes share PostgreSQL, with separate sessions, custody and evidence folders. Capture and judgement
 * are fixtures; the run fence, published custody, recorded inputs, HTTP security and archive handling are real.
 */
class ReviewGatewayReplicasIntegrationTest extends BaseIntegrationTest {
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();
    private static final byte[] CAPTURED = "the frozen captured input".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROGRAM = "export {};".getBytes(StandardCharsets.UTF_8);
    private final AttemptTokenIssuer issuer = new AttemptTokenIssuer(AttemptKeyFixtures.keyring(KEY));
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private ObjectMapper mapper;

    @TempDir
    Path folders;

    private ReviewJob job;
    private AgentRunFence fence;
    private AgentSandboxes owner;
    private AgentSandboxes.LiveSandbox sandbox;
    private String token;

    @BeforeEach
    void leaseTheReview() {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("gateway-" + UUID.randomUUID()));
        job = Objects.requireNonNull(transactions.execute(status -> {
            var created = new ReviewJob();
            RuntimeJobTestData.initialize(created, workspace.getId());
            created.setStatus(ReviewJobStatus.QUEUED);
            created.setConfigSnapshot(configuration());
            created.prePersist();
            var saved = jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, created));
            jdbc.update(
                    "UPDATE agent_run SET concurrency_key=? WHERE id=?",
                    ReviewRunKind.subject(saved.getReviewId()),
                    saved.getRunId());
            assertThat(runs.claimSubject(
                            saved.getWorkspaceId(),
                            ReviewRunKind.subject(saved.getReviewId()),
                            Set.of(AgentRunKind.REVIEW)))
                    .isPresent();
            RuntimeJobTestData.leaseUntil(jdbc, saved, Instant.now().plus(RuntimeJobTestData.LEASE));
            saved.setStatus(ReviewJobStatus.RUNNING);
            saved.setStartedAt(Instant.now());
            return jobs.saveAndFlush(saved);
        }));
        fence = RuntimeJobTestData.fence(jdbc, job);
        owner = SandboxOwnerFixtures.owner(beans, issuer);
        sandbox = owner.ensure(job.getWorkspaceId(), ReviewRunKind.subject(job.getReviewId()), AgentSandboxKind.REVIEW);
        token = issuer.issueForSandbox(
                sandbox.id(),
                job.getWorkspaceId(),
                sandbox.subjectKey(),
                Instant.now().plusSeconds(600));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldContinueTheSameLeaseOnAnotherReadyServerWhenThePreparingServerStops(boolean lostCommitReply)
            throws Exception {
        try (var second = replica(folders.resolve("second"), false)) {
            JsonNode frozen;
            Instant started;
            Path stoppedRoot;
            try (var first = replica(folders.resolve("first"), lostCommitReply)) {
                stoppedRoot = first.layout().root();
                var prepared = call(first, "/context", null, fence.epoch());
                assertThat(prepared.statusCode()).isEqualTo(lostCommitReply ? 503 : 200);
                var recorded = jobs.findById(job.getId()).orElseThrow();
                frozen = Objects.requireNonNull(recorded.getCapture()).path("program");
                started = Objects.requireNonNull(recorded.getExecutionStartedAt());
                assertThat(recorded.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
                assertThat(recorded.getRunId()).isEqualTo(fence.runId());
                if (lostCommitReply)
                    assertThat(prepared.headers().firstValue("Retry-After")).hasValue("1");

                assertThat(frozen.path("environment").has("LLM_PROXY_TOKEN")).isFalse();
                assertThat(frozen.path("environment").has("LLM_PROXY_CLAIM")).isFalse();
                assertThat(frozen.path("environment").has("SANDBOX_RUNTIME_URL"))
                        .isFalse();
            }
            assertThat(FileSystemUtils.deleteRecursively(stoppedRoot)).isTrue();

            var run = new SandboxRun(sandbox, fence, AgentRunKind.REVIEW, 0);
            // The model gate works before this replica has loaded a session or an input folder.
            assertThat(second.kind().attempt(run)).hasValueSatisfying(attempt -> {
                assertThat(attempt.runId()).isEqualTo(fence.runId());
                assertThat(attempt.epoch()).isEqualTo(fence.epoch());
            });
            assertThat(call(second, "/context", null, fence.epoch() + 1).statusCode())
                    .isEqualTo(409);
            var resumed = call(second, "/context", null, fence.epoch());
            assertThat(resumed.statusCode()).isEqualTo(200);
            var context = mapper.readTree(resumed.body());
            assertThat(context.path("command")).isEqualTo(frozen.path("command"));
            assertThat(context.path("workDeadline").asLong())
                    .isEqualTo(started.plusSeconds(600)
                            .truncatedTo(ChronoUnit.SECONDS)
                            .toEpochMilli());
            assertThat(jobs.findById(job.getId()).orElseThrow().getExecutionStartedAt())
                    .isEqualTo(started);
            assertThat(archive(call(second, "/runtime/workspace", null, fence.epoch())
                            .body()))
                    .containsEntry("context/input.txt", CAPTURED)
                    .containsEntry("program.js", PROGRAM);
            second.custody().releaseJob(job.getId());
            assertThat(call(second, "/context", null, fence.epoch()).statusCode())
                    .isEqualTo(200);
            assertThat(archive(call(second, "/runtime/workspace", null, fence.epoch())
                            .body()))
                    .containsEntry("context/input.txt", CAPTURED)
                    .containsEntry("program.js", PROGRAM);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM review_attempt_input WHERE workspace_id=? AND job_id=?",
                            Integer.class,
                            job.getWorkspaceId(),
                            job.getId()))
                    .isEqualTo(2);
            assertThat(captures).hasValue(1);
            transactions.executeWithoutResult(status -> {
                var admitted = jobs.findById(job.getId()).orElseThrow();
                admitted.setAdmissionAttempt(admitted.getAttempt());
                admitted.setAdmissionDigest("a".repeat(64));
                jobs.saveAndFlush(admitted);
            });

            byte[] result = resultArchive();
            var uploaded = call(second, "/runtime/result", result, fence.epoch());
            assertThat(uploaded.statusCode()).isEqualTo(204);
            assertThat(uploaded.headers().firstValue("ETag")).isPresent();
            assertThat(call(second, "/runtime/result", result, fence.epoch()).statusCode())
                    .isEqualTo(404);
            var ended = jobs.findById(job.getId()).orElseThrow();
            assertThat(ended.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(ended.getRunId()).isNotEqualTo(fence.runId());
            assertThat(ended.getAttempt()).isEqualTo(job.getAttempt() + 1);
            assertThat(ended.spentAttempts()).isEqualTo(job.spentAttempts());
            assertThat(runs.isCurrent(fence.run(), fence.epoch())).isFalse();
        }
    }

    @AfterEach
    void endOnlyThisTestsUnfinishedWork() {
        transactions.executeWithoutResult(status -> {
            var current = jobs.findById(job.getId()).orElseThrow();
            if (current.getStatus() == ReviewJobStatus.RUNNING || current.getStatus() == ReviewJobStatus.QUEUED) {
                current.setStatus(ReviewJobStatus.CANCELLED);
                current.setCompletedAt(Instant.now());
                jobs.saveAndFlush(current);
                RuntimeJobTestData.agree(jdbc, current.getId());
            }
        });
        owner.release(job.getWorkspaceId(), sandbox.id());
    }

    @Test
    void shouldRecordAConfirmedPublicationRollbackAndNeverRecaptureTheSameLease() throws Exception {
        try (var replica = replica(folders.resolve("rollback"), Publication.ROLLED_BACK)) {
            assertThat(call(replica, "/context", null, fence.epoch()).statusCode())
                    .isEqualTo(409);
            var failed = jobs.findById(job.getId()).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
            assertThat(failed.getExecutionStartedAt()).isNull();
            var capture = Objects.requireNonNull(failed.getCapture());
            assertThat(capture.has("program")).isFalse();
            assertThat(capture.path("preparationFailure").path("stage").asString())
                    .isEqualTo("PROVENANCE");
            // The injected cause is a TransactionSystemException, which PreparationFailure classifies as DATABASE.
            assertThat(capture.path("preparationFailure").path("kind").asString())
                    .isEqualTo("DATABASE");
            assertThat(call(replica, "/context", null, fence.epoch()).statusCode())
                    .isEqualTo(409);
            assertThat(captures).hasValue(1);
        }
    }

    @Test
    void shouldKeepTheClaimWhileThePublicationOutcomeCannotBeRead() throws Exception {
        try (var first = replica(folders.resolve("unreadable"), Publication.UNREADABLE);
                var second = replica(folders.resolve("healthy"), Publication.NORMAL)) {
            var unknown = call(first, "/context", null, fence.epoch());
            assertThat(unknown.statusCode()).isEqualTo(503);
            assertThat(unknown.headers().firstValue("Retry-After")).hasValue("1");
            var committed = jobs.findById(job.getId()).orElseThrow();
            assertThat(committed.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
            assertThat(committed.getRunId()).isEqualTo(fence.runId());
            assertThat(committed.getAttempt()).isEqualTo(job.getAttempt());
            assertThat(committed.spentAttempts()).isEqualTo(job.spentAttempts());
            Instant started = Objects.requireNonNull(committed.getExecutionStartedAt());
            assertThat(Objects.requireNonNull(committed.getCapture()).has("preparationFailure"))
                    .isFalse();
            var restored = call(second, "/context", null, fence.epoch());
            assertThat(restored.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(restored.body()).path("workDeadline").asLong())
                    .isEqualTo(started.plusSeconds(600)
                            .truncatedTo(ChronoUnit.SECONDS)
                            .toEpochMilli());
            assertThat(captures).hasValue(1);
        }
    }

    private JsonNode configuration() {
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
                        600)
                .toJson(mapper);
    }

    private enum Publication {
        NORMAL,
        LOST_REPLY,
        ROLLED_BACK,
        UNREADABLE
    }

    private final AtomicInteger captures = new AtomicInteger();

    private Replica replica(Path root, boolean lostCommitReply) throws Exception {
        return replica(root, lostCommitReply ? Publication.LOST_REPLY : Publication.NORMAL);
    }

    private Replica replica(Path root, Publication publication) throws Exception {
        var layout = new FabricLayout(root.toString());
        var custody = new ReviewAttemptCustody(runs);
        var localSessions = new SandboxGatewaySessions();
        var catalog = new EvidenceFolderPersonDataCatalog(
                layout,
                jdbc,
                beans.getBean(NamedParameterJdbcTemplate.class),
                mapper,
                beans.getBean(PersonDataCopyRecorder.class),
                beans.getBean(PersonDataCopyFence.class),
                beans.getBeanProvider(ReviewAttemptCustody.class),
                beans.getBeanProvider(AgentSandboxes.class),
                beans.getBeanProvider(SandboxGatewaySessions.class),
                beans.getBeanProvider(ReviewJobLifecycleService.class));
        var files = new JobEvidenceFiles(
                layout, jobs, beans.getBean(Clock.class), catalog, beans.getBean(AttemptInputs.class));
        var handler = mock(DefaultReviewExecutionHandler.class, invocation -> {
            if (!invocation.getMethod().getName().equals("prepareInputs"))
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            ReviewJob current = invocation.getArgument(0);
            captures.incrementAndGet();
            var manifest = Objects.requireNonNull(new JobFolderIndexBuilder(
                            new ClasspathSourceCatalogRegistry(
                                    beans.getBean(JsonMapper.class), beans.getBean(Clock.class)),
                            mapper,
                            beans.getBean(Clock.class))
                    .build(
                            new ItemKind("scm.pull_request"),
                            Map.of(new SourceKind("scm.repository.tree"), CaptureContribution.empty()))
                    .manifest());
            files.beginPersonCapture(current);
            return files.prepare(
                    current,
                    new PreparedEvidence(
                            Map.of(
                                    SandboxLayout.MANIFEST_PATH,
                                    mapper.writeValueAsBytes(manifest),
                                    "context/input.txt",
                                    CAPTURED),
                            manifest),
                    null);
        });
        var adapter = mock(PracticePiAdapter.class, invocation -> switch (invocation
                .getMethod()
                .getName()) {
            case "buildSandboxSpec" -> {
                PracticeAgentRequest request = invocation.getArgument(0);
                yield new PracticeSandboxSpec(
                        "agent:test",
                        List.of("node", "/workspace/program.js"),
                        Map.of("AGENT_BUDGET_MS", "600000"),
                        Map.of("program.js", PROGRAM),
                        request.jobToken(),
                        "prompt-digest");
            }
            case "parseResult" -> new AgentResult(true, Map.of("runnerFinished", true));
            default -> Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        ReviewJobRepository preparationJobs = jobs;
        if (publication == Publication.UNREADABLE) {
            preparationJobs = mock(ReviewJobRepository.class, AdditionalAnswers.delegatesTo(jobs));
            doThrow(new DataAccessResourceFailureException("The publication outcome cannot be read"))
                    .when(preparationJobs)
                    .hasPublishedPreparation(any(), anyLong(), any(), anyInt());
        }
        var meters = new SimpleMeterRegistry();
        var telemetry = new ReviewJobTelemetry(meters, Tracer.NOOP);
        var preparation = new ReviewAttemptPreparation(
                preparationJobs,
                handler,
                adapter,
                issuer,
                SandboxTestLimits.PROPERTIES,
                publicationTransactions(publication),
                mapper,
                telemetry,
                files);
        var terminal = SandboxReviewClaims.terminal(beans);
        var results = new ReviewAttemptResults(
                jobs,
                handler,
                adapter,
                preparation,
                terminal,
                beans.getBean(ReviewRunKind.class),
                mock(PracticeScriptRunRecorder.class),
                transactions,
                mapper,
                meters,
                telemetry);
        var kind = new ReviewSandboxRuns(
                mock(ReviewJobClaimService.class),
                preparation,
                custody,
                terminal,
                jobs,
                new SandboxWorkspaceManager(),
                localSessions,
                transactions,
                mapper,
                results,
                beans.getBean(ReviewRunKind.class),
                runs,
                files,
                beans.getBean(ClusterLock.class));
        return new Replica(gateway(kind, localSessions), kind, custody, layout, job.getId(), preparation);
    }

    /** Adapted from SBX's after-commit injector: the failure is after the real outer commit. */
    private TransactionTemplate publicationTransactions(Publication publication) {
        var fail = new AtomicBoolean(publication != Publication.NORMAL);
        return new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager())) {
            @Override
            public <T extends @Nullable Object> T execute(TransactionCallback<T> action) {
                boolean nested = TransactionSynchronizationManager.isActualTransactionActive();
                if (!nested && publication == Publication.ROLLED_BACK && fail.compareAndSet(true, false))
                    return super.execute(status -> {
                        action.doInTransaction(status);
                        throw new TransactionSystemException("The publication rolled back");
                    });
                T result = super.execute(action);
                if (!nested && fail.compareAndSet(true, false))
                    throw new TransactionSystemException("The commit reply was lost");
                return result;
            }
        };
    }

    private ConfigurableApplicationContext gateway(SandboxRunKind kind, SandboxGatewaySessions sessions)
            throws Exception {
        var application = new SpringApplication(
                GatewayApplication.class,
                Class.forName("de.tum.cit.aet.hephaestus.agent.proxy.LlmProxySecurityConfig"));
        application.addInitializers(context -> {
            var factory = context.getBeanFactory();
            factory.registerSingleton(
                    "agentRunController", new AgentRunController(runs, owner, transactions, List.of(kind)));
            if (kind instanceof ReviewSandboxRuns reviews)
                factory.registerSingleton("reviewSandboxController", new ReviewSandboxController(reviews, runs));
            factory.registerSingleton("reviewSandboxRuns", kind);
            factory.registerSingleton("sandboxGatewaySessions", sessions);
            factory.registerSingleton("agentSandboxes", owner);
            factory.registerSingleton("agentRuns", runs);
            factory.registerSingleton("reviewJobRepository", jobs);
            factory.registerSingleton("tracer", beans.getBean(Tracer.class));
            factory.registerSingleton(
                    "attemptTokenVerifier",
                    new AttemptTokenVerifier(AttemptKeyFixtures.keyring(KEY), new SimpleMeterRegistry()));
            factory.registerSingleton("proxyAccounting", mock(ProxyAccounting.class));
            factory.registerSingleton("bucketResolver", beans.getBean(BucketResolver.class));
            factory.registerSingleton("agentGatewayErrors", AgentGatewayErrorFixtures.errors());
            factory.registerSingleton("agentControllerAdvice", new AgentControllerAdvice());
            factory.registerSingleton("globalControllerAdvice", new GlobalControllerAdvice());
        });
        return application.run(
                "--server.port=0",
                "--server.shutdown=immediate",
                "--spring.main.banner-mode=off",
                "--spring.mvc.problemdetails.enabled=true",
                "--" + RuntimeRole.AGENT_ENABLED_PROPERTY + "=true",
                "--hephaestus.sandbox.gateway.port=0");
    }

    private HttpResponse<byte[]> call(Replica replica, String route, byte @Nullable [] body, int epoch)
            throws Exception {
        return call(replica.web(), route, body, epoch);
    }

    private HttpResponse<byte[]> call(
            ConfigurableApplicationContext web, String route, byte @Nullable [] body, int epoch) throws Exception {
        int port = web.getBean(SandboxGatewayConnector.class).port();
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/internal/agent/runs/" + fence.runId() + route))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("X-Agent-Run", fence.runId().toString())
                .header("X-Agent-Epoch", Integer.toString(epoch));
        if (body == null) request.GET();
        else {
            request.header("Content-Type", "application/x-tar")
                    .header(
                            "Content-Digest",
                            "sha-256=:"
                                    + Base64.getEncoder()
                                            .encodeToString(MessageDigest.getInstance("SHA-256")
                                                    .digest(body))
                                    + ":")
                    .header("x-sandbox-exit-code", "0")
                    .header("x-sandbox-timed-out", "false")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Map<String, byte[]> archive(byte[] bytes) throws Exception {
        var files = new LinkedHashMap<String, byte[]>();
        try (var input = new TarArchiveInputStream(new ByteArrayInputStream(bytes))) {
            TarArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null)
                if (entry.isFile()) files.put(entry.getName(), input.readAllBytes());
        }
        return files;
    }

    private static byte[] resultArchive() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(bytes)) {
            byte[] content = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
            var entry = new TarArchiveEntry("out/result.json");
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    private record Replica(
            ConfigurableApplicationContext web,
            ReviewSandboxRuns kind,
            ReviewAttemptCustody custody,
            FabricLayout layout,
            UUID jobId,
            ReviewAttemptPreparation preparation)
            implements AutoCloseable {
        @Override
        public void close() {
            web.close();
            custody.releaseJob(jobId);
        }
    }

    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
                WebMvcAutoConfiguration.class,
        JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, ValidationAutoConfiguration.class,
        SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
    })
    @Import(SandboxGatewayConfiguration.class)
    static class GatewayApplication implements WebMvcConfigurer {
        private final AgentRuns runs;
        private final Tracer tracer;

        GatewayApplication(AgentRuns runs, Tracer tracer) {
            this.runs = runs;
            this.tracer = tracer;
        }

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new SandboxRunArgumentResolver(runs, tracer));
        }
    }
}
