package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog.ErasedFolders;
import de.tum.cit.aet.hephaestus.agent.context.AttemptInputs;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptInputRepository;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AttemptCaptureFailuresFixture;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.evidence.capture.CheckoutKey;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewRepositoryPreparer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturedCheckouts;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitTransportFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.PracticeModelTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jgit.api.Git;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
                    CheckoutUnverifiedException.class,
                    () -> b.files.readText(
                            recorded,
                            "repos/reviewed/src/App.java",
                            ProvenanceDigest.sha256Hex(SOURCE.getBytes(StandardCharsets.UTF_8))));
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
            this.root = root;
            key = new RepositoryKey(job.getWorkspaceId(), 4242L);
            git = new GitRepositoryManager(
                    new GitRepositoryProperties(true, 2, 1L << 30, 1L << 40),
                    new GitRepositoryLockManager(),
                    new FabricLayout(root.toString()),
                    GitTransportFixtures.guarded(),
                    mock(RepositoryRefState.class));
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
                            new ScmCapturedCheckouts(
                                    git, mock(ReviewRepositoryPreparer.class), new SimpleMeterRegistry()),
                            transactions));
        }

        /** Captures the attempt as a review does: the checkout with its recorded refs, and an API record. */
        PreparedJobInputs capture(ReviewJob job) {
            return capture(job, Map.of());
        }

        PreparedJobInputs capture(ReviewJob job, Map<String, Path> filesOnDisk) {
            var snapshot = git.readTreeSnapshot(key, head);
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
                    List.of(snapshot),
                    null,
                    List.of(directory));
            files.beginPersonCapture(job);
            return files.prepare(job, evidence, null);
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
