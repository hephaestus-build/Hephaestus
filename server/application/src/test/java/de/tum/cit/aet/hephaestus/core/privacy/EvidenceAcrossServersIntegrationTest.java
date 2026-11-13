package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog.ErasedFolders;
import de.tum.cit.aet.hephaestus.agent.context.AttemptInputs;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptInputRepository;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedResultProjectionService;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackCompositionService;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeResultStaging;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.StaleAttemptException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AttemptCaptureFailuresFixture;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SealResultsRequestDTO;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account.AppRole;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.evidence.capture.CheckoutKey;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.EvidenceSpool;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.CapturedCheckouts;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewRepositoryPreparer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturedCheckouts;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitTransportFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWriteGuard;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.PracticeModelTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.eclipse.jgit.api.Git;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Two servers that share only PostgreSQL: server A captures a review attempt, and server B, with its own disk, serves
 * the attempt. B rebuilds the attempt's folder from the recorded files and its own checkout, verified against the
 * recorded commit, tree and checkout digest. Erasure completes without either server and each removes its own folder.
 */
class EvidenceAcrossServersIntegrationTest extends BaseIntegrationTest {
    private static final String SOURCE = "class App {}\n";
    private static final String RECORD = "{\"title\":\"COPIED-RECORD-CANARY\"}";

    private final AtomicLong sourceIds = new AtomicLong();

    @TempDir
    Path temporary;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private ReviewAttemptInputRepository inputRows;

    @Autowired
    private PersonDataCopyFence fence;

    @Autowired
    private ObjectProvider<ReviewJobLifecycleService> lifecycles;

    @Autowired
    private PracticeModelTestFixtures modelFixtures;

    @Autowired
    private PlatformTransactionManager transactions;

    private Path upstream;
    private String head;

    @BeforeEach
    void setUp() throws Exception {
        upstream = Files.createDirectory(temporary.resolve("upstream"));
        try (Git repository = Git.init()
                .setInitialBranch("main")
                .setDirectory(upstream.toFile())
                .call()) {
            GitTestFixtures.disableSigning(repository.getRepository());
            Files.createDirectories(upstream.resolve("src"));
            Files.writeString(upstream.resolve("src/App.java"), SOURCE);
            repository.add().addFilepattern(".").call();
            head = repository
                    .commit()
                    .setSign(false)
                    .setMessage("Initial commit")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
                    .name();
            repository.branchCreate().setName("feature").setStartPoint(head).call();
        }
    }

    @Test
    void aServerWithoutTheCapturedFolderRebuildsItAndVerifiesTheExactQuote() throws Exception {
        var job = runningJob("evidence-rebuild");
        var a = new Server(temporary.resolve("a"), job);
        var b = new Server(temporary.resolve("b"), job);
        try (var captured = a.capture(job)) {
            var recorded = record(job, captured, null);
            String sourceSha = ProvenanceDigest.sha256Hex(SOURCE.getBytes(StandardCharsets.UTF_8));

            assertThat(b.folder(recorded)).doesNotExist();
            b.files.ensureAttemptFolder(recorded);
            assertThat(b.files.readText(recorded, "repos/reviewed/src/App.java", sourceSha))
                    .contains(SOURCE);
            assertThat(b.files.containsUtf8AtLines(
                            recorded, "repos/reviewed/src/App.java", sourceSha, "class App {}", 1, 1))
                    .contains(true);
            assertThat(b.files.readText(
                            recorded,
                            "context/item.json",
                            ProvenanceDigest.sha256Hex(RECORD.getBytes(StandardCharsets.UTF_8))))
                    .contains(RECORD);
            assertThat(b.folder(recorded).resolve("repos/reviewed/.git/" + GitRepositoryManager.CAPTURED_REFS))
                    .as("the captured refs are restored as the capture recorded them")
                    .hasSameBinaryContentAs(
                            a.folder(recorded).resolve("repos/reviewed/.git/" + GitRepositoryManager.CAPTURED_REFS));
        }
    }

    @Test
    void shouldBuildAMissingFolderBeforeStagingTakesItsLocksAndNotBeforeASeal() throws Exception {
        var job = runningJob("evidence-admission-rebuild");
        var a = new Server(temporary.resolve("a"), job);
        try (var captured = a.capture(job)) {
            var recorded = record(job, captured, null);
            var identity = new AdmissionIdentity(
                    recorded.getId(), recorded.getWorkspaceId(), recorded.getAttempt(), recorded.getRunId());

            // Staging always reads the folder, so it builds it first. Another review id then refuses the request.
            var staging = new Replica("stage", recorded);
            assertThatThrownBy(() -> staging.admission.stage(
                            identity,
                            new AdmissionRequestDTO(
                                    SandboxLayout.PRACTICE_CONTRACT_VERSION,
                                    UUID.randomUUID(),
                                    recorded.getId(),
                                    recorded.getAttempt(),
                                    List.of(),
                                    List.of())))
                    .isInstanceOf(StaleAttemptException.class);
            assertThat(staging.builds).containsExactly(BUILT_WITHOUT_LOCKS);
            assertThat(staging.server.folder(recorded)).isDirectory();

            // A seal builds no folder before its transaction. Another review id refuses it before it reads any folder.
            var admitting = new Replica("admit", recorded);
            assertThatThrownBy(() -> admitting.admission.admit(
                            identity,
                            new SealResultsRequestDTO(
                                    SandboxLayout.PRACTICE_CONTRACT_VERSION,
                                    UUID.randomUUID(),
                                    recorded.getId(),
                                    recorded.getAttempt())))
                    .isInstanceOf(StaleAttemptException.class);
            assertThat(admitting.builds).isEmpty();
            assertThat(admitting.server.folder(recorded)).doesNotExist();
        }
    }

    private static final String BUILT_WITHOUT_LOCKS = "transaction=false, locks free=true";

    /** A server without the attempt's folder: its admission, and how each checkout build it ran found the locks. */
    private final class Replica {
        final List<String> builds = new ArrayList<>();
        final Server server;
        final ResultAdmissionService admission;

        Replica(String name, ReviewJob job) {
            server = new Server(
                    temporary.resolve("replica-" + name), job, checkouts -> (workspaceId, key, target, verified) -> {
                        builds.add("transaction=" + TransactionSynchronizationManager.isActualTransactionActive()
                                + ", locks free=" + locksFree(job));
                        checkouts.copy(workspaceId, key, target, verified);
                    });
            admission = admission(server.files);
        }
    }

    @Test
    void shouldPreserveFrozenFilePermissionsAcrossServers() throws Exception {
        var job = runningJob("evidence-permissions");
        var a = new Server(temporary.resolve("a"), job);
        var b = new Server(temporary.resolve("b"), job);
        Path program = Files.writeString(temporary.resolve("program.sh"), "#!/bin/sh\nprintf 'restored-program'\n");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path data = Files.writeString(temporary.resolve("data.txt"), "program input");
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rw-------"));

        try (var captured = a.capture(job, Map.of("practices/program.sh", program, "practices/data.txt", data))) {
            var recorded = record(job, captured, null);
            b.files.ensureAttemptFolder(recorded);

            for (String path : List.of("practices/program.sh", "practices/data.txt", "context/item.json")) {
                Path original = a.folder(recorded).resolve(path);
                Path restored = b.folder(recorded).resolve(path);
                assertThat(restored).hasSameBinaryContentAs(original);
                assertThat(Files.getPosixFilePermissions(restored))
                        .as("the frozen permissions of %s", path)
                        .isEqualTo(Files.getPosixFilePermissions(original));
            }
            assertThat(Files.isExecutable(b.folder(recorded).resolve("practices/program.sh")))
                    .isTrue();
            assertThat(Files.isExecutable(b.folder(recorded).resolve("practices/data.txt")))
                    .isFalse();
            assertThat(Files.isExecutable(b.folder(recorded).resolve("context/item.json")))
                    .isFalse();
        }
    }

    @Test
    void aRebuildThatDiffersFromTheRecordedDigestIsACaptureFailureNotMissingEvidence() throws Exception {
        var job = runningJob("evidence-mismatch");
        var a = new Server(temporary.resolve("a"), job);
        var b = new Server(temporary.resolve("b"), job);
        try (var captured = a.capture(job)) {
            var recorded = record(job, captured, "0".repeat(64));

            var failure = catchThrowableOfType(
                    CheckoutUnverifiedException.class, () -> b.files.ensureAttemptFolder(recorded));
            assertThat(failure.reason()).isEqualTo(CheckoutUnverifiedException.Reason.MISMATCH);
            assertThat(b.folder(recorded)).doesNotExist();

            // What the admission endpoint does with it: the attempt ends with the capture failure as its reason.
            var captureFailures = AttemptCaptureFailuresFixture.from(context);
            var identity = new AdmissionIdentity(
                    recorded.getId(), recorded.getWorkspaceId(), recorded.getAttempt(), recorded.getRunId());
            assertThat(captureFailures.fail(identity, failure)).isTrue();
            assertThat(captureFailures.fail(identity, failure))
                    .as("the ended attempt no longer holds its run, so nothing more is written")
                    .isFalse();
            var row = jdbc.queryForMap("""
                    SELECT status, capture->'preparationFailure' AS failure,
                      COALESCE(capture::text,'') || COALESCE(output::text,'') AS recorded
                    FROM review_job WHERE id=?
                    """, job.getId());
            assertThat(row.get("status")).isEqualTo("FAILED");
            var diagnosis = mapper.readTree(String.valueOf(row.get("failure")));
            assertThat(diagnosis.path("captureCode").asString()).isEqualTo("CHECKOUT_UNVERIFIED");
            assertThat(diagnosis.path("stage").asString()).isEqualTo("INPUTS");
            assertThat(diagnosis.path("kind").asString()).isEqualTo("SOURCE_CAPTURE");
            assertThat(diagnosis.path("attempt").asInt()).isEqualTo(recorded.getAttempt());
            assertThat(String.valueOf(row.get("recorded"))).doesNotContain("SOURCE_UNAVAILABLE");
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM review_practice p WHERE p.review_id=?"
                                    + " AND row_to_json(p)::text LIKE '%SOURCE_UNAVAILABLE%'",
                            Long.class, recorded.getReviewId()))
                    .as("no practice refuses a citation for a source that only this server lacked")
                    .isZero();
        }
    }

    @Test
    void erasureCompletesWithoutEitherServerAndEachRemovesItsOwnFolderInItsNextPass() throws Exception {
        var job = runningJob("evidence-erasure");
        var a = new Server(temporary.resolve("a"), job);
        var b = new Server(temporary.resolve("b"), job);
        var captured = a.capture(job);
        ReviewJob recorded;
        try {
            recorded = record(job, captured, null);
            b.files.ensureAttemptFolder(recorded);
            assertThat(b.files.readText(
                            recorded,
                            "context/item.json",
                            ProvenanceDigest.sha256Hex(RECORD.getBytes(StandardCharsets.UTF_8))))
                    .contains(RECORD);
            assertThat(rows(job)).isPositive();

            a.catalog.eraseJobEvidence(Set.of(job.getId()));

            assertThat(rows(job))
                    .as("the erasure deletes the recorded files at once")
                    .isZero();
            assertThat(jdbc.queryForList(
                            "SELECT state FROM person_evidence_copy WHERE job_id=?", String.class, job.getId()))
                    .containsOnly("ERASED");
            assertThat(b.catalog.removeErasedFolders()).isEqualTo(new ErasedFolders(1, 0));
            assertThat(b.folder(recorded)).doesNotExist();
            // Server A's runtime still holds its folder: its pass leaves it for the next one.
            assertThat(a.catalog.removeErasedFolders())
                    .as("the held folder remains, and the pass counts it")
                    .isEqualTo(new ErasedFolders(0, 1));
            assertThat(a.folder(recorded)).exists();
        } finally {
            captured.close();
        }
        assertThat(a.catalog.removeErasedFolders()).isEqualTo(new ErasedFolders(1, 0));
        assertThat(a.folder(recorded)).doesNotExist();
        var c = new Server(temporary.resolve("c"), job);
        c.files.ensureAttemptFolder(recorded);
        assertThat(c.files.readText(
                        recorded,
                        "context/item.json",
                        ProvenanceDigest.sha256Hex(RECORD.getBytes(StandardCharsets.UTF_8))))
                .as("no server rebuilds an erased attempt")
                .isEmpty();
    }

    @Test
    void shouldDeleteRecordedInputsWhenTheCaptureContainsAnErasedPerson() {
        var provider = context.getBean(IdentityProviderRepository.class)
                .saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, "https://suppressed-evidence.example"));
        databaseTestUtils.erase(Objects.requireNonNull(provider.getId()), "42", "");
        var job = runningJob("suppressed-capture-inputs");
        var a = new Server(temporary.resolve("suppressed"), job);
        a.files.beginPersonCapture(job);
        a.recorder.recordIdentity(new PersonCopyIdentity("GITLAB", "https://suppressed-evidence.example", "42", null));

        assertThatThrownBy(() -> a.files.prepare(
                        job,
                        new PreparedEvidence(
                                Map.of("context/person.json", RECORD.getBytes(StandardCharsets.UTF_8)), null),
                        null))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Copied evidence contains an erased native identity");
        assertThat(rows(job)).isZero();
        assertThat(a.folder(job)).doesNotExist();
    }

    @Test
    void shouldWaitForSnapshotAdmissionBeforeHoldingTheMirrorReadLock() throws Exception {
        var job = runningJob("checkout-lock-order");
        var key = new RepositoryKey(job.getWorkspaceId(), 4242L);
        var locks = new GitRepositoryLockManager();
        var requested = new CountDownLatch(1);
        var admission = mock(PersonDataCopyFence.class, invocation -> {
            if (!invocation.getMethod().getName().equals("capture"))
                throw new AssertionError("Only capture admission is needed for a checkout");
            requested.countDown();
            return fence.capture();
        });
        var manager = new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, 1L << 30, 1L << 40),
                locks,
                new FabricLayout(temporary.resolve("checkout-lock-order").toString()),
                GitTransportFixtures.guarded(),
                mock(RepositoryRefState.class),
                admission);
        manager.ensureRepository(key, upstream.toUri().toString(), null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<GitRepositoryManager.GitTreeSnapshot> copy;
            var erasure = fence.erase();
            try (erasure) {
                copy = executor.submit(() -> manager.readTreeSnapshot(key, head));
                assertThat(requested.await(30, TimeUnit.SECONDS)).isTrue();
                // A blocked database admission must not prevent a mirror writer from completing.
                assertThat(executor.submit(() -> locks.withWriteLock(key, () -> true))
                                .get(30, TimeUnit.SECONDS))
                        .isTrue();
            }
            try (var snapshot = copy.get(30, TimeUnit.SECONDS)) {
                assertThat(snapshot.stagingDir().resolve("src/App.java")).hasContent(SOURCE);
            }
        }
    }

    @Test
    void shouldBuildRepositoryOwnedCheckoutsWhileErasureWaitsForTheAdmittingTransaction() throws Exception {
        var job = runningJob("checkout-transaction-admission");
        var key = new RepositoryKey(job.getWorkspaceId(), 4242L);
        var postgres = PostgreSQLTestContainer.getInstance();
        var guardedSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var options = new Properties();
        // A regression that requests another session must fail rather than hang this test indefinitely.
        options.setProperty("options", "-c lock_timeout=5s");
        guardedSource.setConnectionProperties(options);
        var manager = new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, 1L << 30, 1L << 40),
                new GitRepositoryLockManager(),
                new FabricLayout(temporary.resolve("checkout-transaction").toString()),
                GitTransportFixtures.guarded(),
                mock(RepositoryRefState.class),
                new PostgresPersonDataCopyFence(guardedSource, new JdbcTemplate(guardedSource)));
        manager.ensureRepository(key, upstream.toUri().toString(), null);
        String tree;
        try (var git = Git.open(upstream.toFile())) {
            tree = Objects.requireNonNull(git.getRepository().resolve(head + "^{tree}"))
                    .name();
        }
        Path target = temporary.resolve("transaction-checkout");
        var eraserPid = new AtomicLong();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var erasure = new AtomicReference<Future<?>>();
            try {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    fence.holdForCapture();
                    erasure.set(executor.submit(() -> new TransactionTemplate(transactions)
                            .executeWithoutResult(erase -> {
                                eraserPid.set(Objects.requireNonNull(
                                        jdbc.queryForObject("SELECT pg_backend_pid()", Long.class)));
                                fence.holdForErasure();
                            })));
                    await().atMost(Duration.ofSeconds(30))
                            .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND locktype='advisory'"
                                            + " AND classid=2165 AND objid=1 AND NOT granted)",
                                    Boolean.class,
                                    eraserPid.get())));
                    assertThat(manager.copyCheckout(key, head, tree, null, target))
                            .isTrue();
                    assertThat(manager.copyCheckout(key, head, tree, null, temporary.resolve("cached-copy")))
                            .isFalse();
                    assertThat(manager.copyCheckout(
                                    key, head, tree, List.of("src/App.java"), temporary.resolve("scan-copy")))
                            .isTrue();
                    assertThat(target.resolve("src/App.java")).hasContent(SOURCE);
                    assertThat(temporary.resolve("scan-copy/src/App.java")).hasContent(SOURCE);
                });
            } finally {
                Future<?> waitingErasure = erasure.get();
                if (waitingErasure != null) waitingErasure.get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void shouldRetainRealErasureAdmissionAfterWorkerDetachAndFailedPhysicalCleanup() throws Exception {
        var job = runningJob("failed-context-copy");
        var spool = new FabricLayout(temporary.resolve("context-copy").toString()).spool();
        EvidenceSpool.Copy copy;
        var scope = spool.capture(job.getWorkspaceId(), fence::capture);
        try (scope) {
            copy = spool.directory(job.getWorkspaceId());
            Files.writeString(copy.path().resolve("private.java"), SOURCE);
        }
        try (copy) {
            var io = mockStatic(FileSystemUtils.class, invocation -> {
                if (invocation.getMethod().getName().equals("deleteRecursively")
                        && copy.path().equals(invocation.getArgument(0))) throw new IOException("volume unavailable");
                return invocation.callRealMethod();
            });
            try (io) {
                assertThatThrownBy(copy::close).isInstanceOf(IOException.class);
                var acquired = new TransactionTemplate(transactions)
                        .execute(status ->
                                jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(2165,1)", Boolean.class));
                assertThat(acquired).isFalse();
                assertThat(copy.path().resolve("private.java")).hasContent(SOURCE);
            }
            assertThat(spool.reconcile()).isZero();
            assertThat(copy.path()).doesNotExist();
            var acquired = new TransactionTemplate(transactions)
                    .execute(status -> jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(2165,1)", Boolean.class));
            assertThat(acquired).isTrue();
        }
    }

    @Test
    void shouldRemoveGatewayArchivesWithTheirReceiptOwnedAttempt() throws Exception {
        var job = runningJob("receipt-owned-archives");
        var server = new Server(temporary.resolve("archives"), job);
        var sessions = new SandboxGatewaySessions();
        Path archive;
        try {
            try (var captured = server.capture(job)) {
                record(job, captured, null);
                archive = new SandboxWorkspaceManager()
                        .createInputTar(
                                server.files.archiveDirectory(job),
                                Map.of(),
                                captured.filesOnDisk(),
                                captured.directories());
                assertThat(archive).startsWith(server.folder(job));
                var session = sessions.register(job.getId(), archive);
                try (var download = session.download()) {
                    assertThat(download.readAllBytes()).containsSequence(SOURCE.getBytes(StandardCharsets.UTF_8));
                }
                server.catalog.eraseJobEvidence(Set.of(job.getId()));
                assertThat(jdbc.queryForObject(
                                "SELECT state FROM person_evidence_copy WHERE job_id=? AND workspace_id=?",
                                String.class,
                                job.getId(),
                                job.getWorkspaceId()))
                        .isEqualTo("ERASED");
                assertThat(server.catalog.removeErasedFolders().remaining()).isPositive();
                assertThat(archive).exists();
            }
            assertThat(server.catalog.removeErasedFolders().remaining()).isZero();
            assertThat(archive).doesNotExist();
        } finally {
            sessions.closeJob(job.getId());
        }
    }

    @Test
    void shouldRemoveACrashedCaptureBeforeAdmittingWorkAndRetainItsErasureReceipt() throws Exception {
        var job = runningJob("spool-crash");
        String origin = "https://spool-" + job.getId() + ".example";
        var provider = context.getBean(IdentityProviderRepository.class)
                .saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, origin));
        var administrator = new Account("Spool erasure administrator");
        administrator.setAppRole(AppRole.APP_ADMIN);
        long administratorId = Objects.requireNonNull(context.getBean(AccountRepository.class)
                .saveAndFlush(administrator)
                .getId());
        var personData = context.getBean(PersonDataService.class);
        Path home = temporary.resolve("crashed-server");
        Path output = temporary.resolve("capture.log");
        var postgres = PostgreSQLTestContainer.getInstance();
        try (var signal = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            signal.setSoTimeout(30_000);
            var builder = new ProcessBuilder(
                            ProcessHandle.current().info().command().orElseThrow(),
                            "-Xmx128m",
                            "-cp",
                            System.getProperty("java.class.path"),
                            CrashedCapture.class.getName(),
                            home.toString(),
                            job.getId().toString(),
                            Long.toString(job.getWorkspaceId()),
                            Integer.toString(job.getAttempt()),
                            origin,
                            Integer.toString(signal.getLocalPort()))
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().put("SPOOL_TEST_JDBC_URL", postgres.getJdbcUrl());
            builder.environment().put("SPOOL_TEST_JDBC_USER", postgres.getUsername());
            builder.environment().put("SPOOL_TEST_JDBC_PASSWORD", postgres.getPassword());
            Process capture = builder.start();
            try (Socket ready = signal.accept()) {
                ready.setSoTimeout(30_000);
                Path copied = Path.of(new DataInputStream(ready.getInputStream()).readUTF());
                assertThat(copied).startsWith(home.resolve(".evidence-spool/" + job.getWorkspaceId()));
                assertThat(copied.resolve("private.java")).hasContent(SOURCE);
                var receipt = jdbc.queryForMap(
                        "SELECT state,payload::text AS payload FROM person_evidence_copy WHERE job_id=? AND workspace_id=?",
                        job.getId(),
                        job.getWorkspaceId());
                assertThat(receipt.get("state")).isEqualTo("CAPTURING");
                assertThat(String.valueOf(receipt.get("payload"))).contains(origin, "42");
                var erasureAdmitted = new TransactionTemplate(transactions)
                        .execute(status ->
                                jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(2165,1)", Boolean.class));
                assertThat(erasureAdmitted).isFalse();
                // Forced termination runs neither close nor finally, and leaves the receipt committed.
                capture.destroyForcibly();
                assertThat(capture.waitFor(30, TimeUnit.SECONDS)).isTrue();
                var preview = personData.preview(
                        administratorId,
                        null,
                        List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)));
                personData.requestErasure(preview.request().getId(), administratorId, true);
                personData.run(preview.request().getId());
                assertThat(personData.get(preview.request().getId()).request().getState())
                        .isEqualTo(PersonDataRequest.State.COMPLETE);
                assertThat(jdbc.queryForObject(
                                "SELECT state FROM person_evidence_copy WHERE job_id=? AND workspace_id=?",
                                String.class,
                                job.getId(),
                                job.getWorkspaceId()))
                        .isEqualTo("ERASED");
                var restarted = new FabricLayout(home.toString());
                assertThat(copied.getParent()).doesNotExist();
                assertThat(restarted.spool().reconcile()).isZero();
                assertThat(jdbc.queryForObject(
                                "SELECT payload::text FROM person_evidence_copy WHERE job_id=? AND workspace_id=?",
                                String.class,
                                job.getId(),
                                job.getWorkspaceId()))
                        .doesNotContain(origin, "42");
            } finally {
                capture.destroyForcibly();
                assertThat(capture.waitFor(30, TimeUnit.SECONDS))
                        .as(Files.readString(output))
                        .isTrue();
            }
        }
    }

    /** Runs the production capture fence and receipt writer, then dies while its owned copy is live. */
    public static final class CrashedCapture {
        public static void main(String[] args) throws Exception {
            var source = new DriverManagerDataSource(
                    Objects.requireNonNull(System.getenv("SPOOL_TEST_JDBC_URL")),
                    Objects.requireNonNull(System.getenv("SPOOL_TEST_JDBC_USER")),
                    Objects.requireNonNull(System.getenv("SPOOL_TEST_JDBC_PASSWORD")));
            var jdbc = new JdbcTemplate(source);
            long workspace = Long.parseLong(args[2]);
            var job = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT id,review_id,run_id FROM review_job WHERE id=? AND workspace_id=?",
                    (row, number) -> {
                        var found = new ReviewJob();
                        found.setId(row.getObject("id", UUID.class));
                        found.setReviewId(row.getObject("review_id", UUID.class));
                        found.setRunId(row.getObject("run_id", UUID.class));
                        found.setWorkspaceId(workspace);
                        return RuntimeJobTestData.attempt(found, Integer.parseInt(args[3]));
                    },
                    UUID.fromString(args[1]),
                    workspace));
            var layout = new FabricLayout(args[0]);
            var recorder = new ExactPersonDataCopyRecorder();
            var beans = new DefaultListableBeanFactory();
            var catalog = new EvidenceFolderPersonDataCatalog(
                    layout,
                    jdbc,
                    new NamedParameterJdbcTemplate(jdbc),
                    new ObjectMapper(),
                    recorder,
                    new PostgresPersonDataCopyFence(source, jdbc),
                    beans.getBeanProvider(ReviewAttemptCustody.class),
                    beans.getBeanProvider(AgentSandboxes.class),
                    beans.getBeanProvider(SandboxGatewaySessions.class),
                    beans.getBeanProvider(ReviewJobLifecycleService.class));
            catalog.beginCapture(job);
            recorder.recordIdentity(new PersonCopyIdentity("GITLAB", args[4], "42", null));
            var copy = layout.spool().directory(workspace);
            Files.writeString(copy.path().resolve("private.java"), SOURCE);
            try (var signal = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(args[5]))) {
                var ready = new DataOutputStream(signal.getOutputStream());
                ready.writeUTF(copy.path().toString());
                ready.flush();
                signal.getInputStream().read();
            }
        }
    }

    /** Whether another session takes the job's row lock, its review's row lock and the erasure side of copy admission. */
    private boolean locksFree(ReviewJob job) {
        return CompletableFuture.supplyAsync(() -> {
                    try {
                        return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> {
                            jdbc.queryForList("SELECT id FROM review_job WHERE id=? FOR UPDATE NOWAIT", job.getId());
                            jdbc.queryForList("SELECT id FROM review WHERE id=? FOR UPDATE NOWAIT", job.getReviewId());
                            return jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(2165,1)", Boolean.class);
                        }));
                    } catch (DataAccessException held) {
                        return false;
                    }
                })
                .orTimeout(30, TimeUnit.SECONDS)
                .join();
    }

    /** This server's admission: its own evidence folders, and the shared PostgreSQL. */
    private ResultAdmissionService admission(JobEvidenceFiles files) {
        return new ResultAdmissionService(
                jobs,
                context.getBean(AdmittedResultQuery.class),
                context.getBean(ReviewJobAssignments.class),
                context.getBean(AdmittedResultProjectionService.class),
                files,
                context.getBean(ReviewHistoryContentSource.class),
                mapper,
                transactions,
                context.getBean(FeedbackCompositionService.class),
                context.getBean(ReviewWriteGuard.class),
                context.getBean(PracticeResultStaging.class),
                context.getBean(AgentRuns.class));
    }

    /** What the executor records with the attempt's capture: its directories, checkout digests and checkout keys. */
    private ReviewJob record(ReviewJob job, PreparedJobInputs captured, @Nullable String digestOverride) {
        var capture = mapper.createObjectNode();
        capture.putArray("directories").add("repos/reviewed/");
        var digests = JobEvidenceFiles.checkoutDigests(captured.directories());
        capture.set(
                JobEvidenceFiles.CHECKOUTS,
                mapper.valueToTree(digestOverride == null ? digests : Map.of("repos/reviewed/", digestOverride)));
        var keys = mapper.createObjectNode();
        for (var directory : captured.directories())
            keys.set(directory.target(), mapper.valueToTree(directory.checkout()));
        capture.set(JobEvidenceFiles.CHECKOUT_KEYS, keys);
        RuntimeJobTestData.updateJobs(
                jdbc,
                "UPDATE review_job SET capture=CAST(? AS jsonb) WHERE id=?",
                mapper.writeValueAsString(capture),
                job.getId());
        return jobs.findById(job.getId()).orElseThrow();
    }

    private long rows(ReviewJob job) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM review_attempt_input WHERE job_id=?", Long.class, job.getId()));
    }

    private ReviewJob runningJob(String name) {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(name));
        var work = modelFixtures.work(workspace, name, "chat.thread", sourceIds.incrementAndGet());
        var job = work.job();
        job.setStatus(ReviewJobStatus.RUNNING);
        return RuntimeJobTestData.save(new TransactionTemplate(transactions), jdbc, jobs, job);
    }

    /** One server: its own disk, mirror, checkout cache and evidence folders, and the shared PostgreSQL. */
    private final class Server {
        final GitRepositoryManager git;
        final EvidenceFolderPersonDataCatalog catalog;
        final JobEvidenceFiles files;
        final RepositoryKey key;
        final ExactPersonDataCopyRecorder recorder = new ExactPersonDataCopyRecorder();
        private final Path root;

        Server(Path root, ReviewJob job) {
            this(root, job, UnaryOperator.identity());
        }

        Server(Path root, ReviewJob job, UnaryOperator<CapturedCheckouts> checkouts) {
            this.root = root;
            key = new RepositoryKey(job.getWorkspaceId(), 4242L);
            git = new GitRepositoryManager(
                    new GitRepositoryProperties(true, 2, 1L << 30, 1L << 40),
                    new GitRepositoryLockManager(),
                    new FabricLayout(root.toString()),
                    GitTransportFixtures.guarded(),
                    mock(RepositoryRefState.class),
                    fence);
            // The server's sync has mirrored the monitored repository.
            git.ensureRepository(key, upstream.toUri().toString(), null);
            catalog = new EvidenceFolderPersonDataCatalog(
                    new FabricLayout(root.toString()),
                    jdbc,
                    namedJdbc,
                    mapper,
                    recorder,
                    fence,
                    new DefaultListableBeanFactory().getBeanProvider(ReviewAttemptCustody.class),
                    new DefaultListableBeanFactory().getBeanProvider(AgentSandboxes.class),
                    new DefaultListableBeanFactory().getBeanProvider(SandboxGatewaySessions.class),
                    lifecycles);
            files = new JobEvidenceFiles(
                    new FabricLayout(root.toString()),
                    jobs,
                    Clock.systemUTC(),
                    catalog,
                    new AttemptInputs(
                            inputRows,
                            checkouts.apply(new ScmCapturedCheckouts(
                                    git, mock(ReviewRepositoryPreparer.class), new SimpleMeterRegistry())),
                            transactions));
        }

        /** Captures the attempt as a review does: the checkout with its recorded refs, and an API record. */
        PreparedJobInputs capture(ReviewJob job) {
            return capture(job, Map.of());
        }

        PreparedJobInputs capture(ReviewJob job, Map<String, Path> filesOnDisk) {
            files.beginPersonCapture(job);
            try (var snapshot = git.readTreeSnapshot(key, head)) {
                var directory = new EvidenceDirectory(
                        "repos/reviewed/",
                        snapshot.stagingDir(),
                        new CheckoutKey(key.repositoryId(), head, snapshot.treeSha(), null, null));
                var staged = new LinkedHashMap<>(filesOnDisk);
                staged.put("repos/reviewed/.git/HEAD", snapshot.stagingDir().resolve(".git/HEAD"));
                staged.put(
                        "repos/reviewed/.git/" + GitRepositoryManager.CAPTURED_REFS,
                        snapshot.stagingDir().resolve(".git/" + GitRepositoryManager.CAPTURED_REFS));
                var evidence = new PreparedEvidence(
                        Map.of("context/item.json", RECORD.getBytes(StandardCharsets.UTF_8)),
                        staged,
                        List.of(),
                        null,
                        List.of(directory));
                return files.prepare(job, evidence, null);
            } finally {
                files.abortPersonCapture(job);
            }
        }

        Path folder(ReviewJob job) {
            return root.resolve("jobs")
                    .resolve(Long.toString(job.getWorkspaceId()))
                    .resolve(job.getId().toString())
                    .resolve(job.getAttempt() + "-"
                            + ProvenanceDigest.sha256Hex(
                                    job.getRunId().toString().getBytes(StandardCharsets.UTF_8)));
        }
    }
}
