package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderLease;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog.CopyUnavailableException;
import de.tum.cit.aet.hephaestus.agent.context.HistoryCopy;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobExecutor;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account.AppRole;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SchemaRowSeeder;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.PracticeModelTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class EvidenceFolderAccountErasureIntegrationTest extends BaseIntegrationTest {
    private static final UUID LEASE = UUID.fromString("c6c70fa5-5434-5d4b-a30b-561c0e4664cc");
    private static final UUID OTHER_LEASE = UUID.fromString("357b1a4e-c0c5-53f2-ba0c-53de7d0cb279");

    private final AtomicLong sourceIds = new AtomicLong();

    @Autowired
    private PracticeModelTestFixtures modelFixtures;

    @TempDir
    Path root;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private UserRepository users;

    @Autowired
    private PersonDataCopyFence fence;

    @Autowired
    private ObjectProvider<ReviewJobLifecycleService> lifecycles;

    @Autowired
    private PersonDataService personData;

    @Autowired
    private PersonDataRequestRepository requests;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PlatformTransactionManager transactions;

    private EvidenceFolderPersonDataCatalog catalog(Path store, PersonDataCopyRecorder recorder) {
        return catalog(store, recorder, fence);
    }

    private EvidenceFolderPersonDataCatalog catalog(
            Path store, PersonDataCopyRecorder recorder, PersonDataCopyFence captureFence) {
        ObjectProvider<ReviewJobExecutor> executor =
                new DefaultListableBeanFactory().getBeanProvider(ReviewJobExecutor.class);
        return new EvidenceFolderPersonDataCatalog(
                new FabricLayout(store.toString()),
                jdbc,
                namedJdbc,
                mapper,
                recorder,
                captureFence,
                executor,
                lifecycles);
    }

    private EvidenceFolderPersonDataCatalog failingCloseCatalog(
            RuntimeException provenanceFailure, RuntimeException admissionFailure) {
        var nativeRecorder = new ExactPersonDataCopyRecorder();
        var nativeCapture = nativeRecorder.begin();
        var capture = spy(nativeCapture);
        doAnswer(invocation -> {
                    nativeCapture.close();
                    throw provenanceFailure;
                })
                .when(capture)
                .close();
        var recorder = mock(PersonDataCopyRecorder.class);
        when(recorder.begin()).thenReturn(capture).thenAnswer(invocation -> nativeRecorder.begin());
        var nativeAdmission = fence.capture();
        var admission = spy(nativeAdmission);
        doAnswer(invocation -> {
                    nativeAdmission.close();
                    throw admissionFailure;
                })
                .when(admission)
                .close();
        var captureFence = mock(PersonDataCopyFence.class);
        when(captureFence.capture()).thenReturn(admission).thenAnswer(invocation -> fence.capture());
        return catalog(root, recorder, captureFence);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepProgressDurableWithoutRewritingUnchangedCopies(boolean finish) throws Exception {
        databaseTestUtils.cleanDatabase();
        var job = job("unchanged-copy-progress");
        var source = jobIn(workspaces.findById(job.getWorkspaceId()).orElseThrow());
        var inherited = new PersonCopyIdentity("GITLAB", "https://copy-progress.example", "43", null);
        receipt(
                source,
                "READY",
                Map.of(
                        "attempt",
                        0,
                        "identities",
                        List.of(inherited),
                        "repositories",
                        List.of(19L),
                        "copiedJobs",
                        List.of(),
                        "dependencies",
                        "COMPLETE"));
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        catalog.beginCapture(job);
        try {
            String initial = progressVersion(job);
            var direct = new PersonCopyIdentity("GITLAB", "https://copy-progress.example", "42", null);
            recorder.recordIdentity(direct);
            String identified = progressVersion(job);
            assertThat(identified).isNotEqualTo(initial);
            assertThat(identities(job)).containsExactly("https://copy-progress.example#42");
            recorder.recordRepository(17L);
            String indexed = progressVersion(job);
            assertThat(indexed).isNotEqualTo(identified);
            assertThat(payload(job).path("repositories").get(0).asLong()).isEqualTo(17L);
            var copied = new ArrayList<UUID>();
            copied.add(source.getId());
            for (int index = 0; index < 32; index++) copied.add(UUID.randomUUID());
            copied.forEach(recorder::recordCopiedJob);
            assertThat(progressVersion(job)).isEqualTo(indexed);
            assertThat(payload(job).has("copiedJobs")).isFalse();
            if (finish) {
                try (var lease = catalog.finishCapture(job)) {
                    assertThat(lease).isNotNull();
                }
            } else {
                catalog.abortCapture(job);
            }
            assertThat(identities(job))
                    .containsExactlyInAnyOrder("https://copy-progress.example#42", "https://copy-progress.example#43");
            var finalPayload = payload(job);
            assertThat(finalPayload.path("dependencies").asString()).isEqualTo("UNKNOWN");
            assertThat(List.of(mapper.convertValue(finalPayload.path("copiedJobs"), UUID[].class)))
                    .containsExactlyElementsOf(copied.stream().sorted().toList());
            assertThat(List.of(mapper.convertValue(finalPayload.path("repositories"), Long[].class)))
                    .containsExactly(17L, 19L);
        } finally {
            catalog.abortCapture(job);
        }
    }

    @Test
    void shouldRefuseUnchangedProgressWhenCaptureOwnershipWasRevoked() {
        databaseTestUtils.cleanDatabase();
        var job = job("revoked-copy-progress");
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        catalog.beginCapture(job);
        try {
            jdbc.update("UPDATE person_evidence_copy SET state='ERASE_REQUESTED' WHERE job_id=?", job.getId());
            assertThatThrownBy(() -> recorder.recordCopiedJob(UUID.randomUUID()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Evidence capture ownership changed");
            assertThat(payload(job).path("identities").isEmpty()).isTrue();
        } finally {
            assertThatThrownBy(() -> catalog.abortCapture(job)).hasMessage("Evidence capture ownership changed");
        }
    }

    private String progressVersion(ReviewJob job) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT xmin::text FROM person_evidence_copy WHERE job_id=?", String.class, job.getId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldReleaseCaptureAndUntransferredFolderWhenCloseFails(boolean finish) throws Exception {
        databaseTestUtils.cleanDatabase();
        var job = job("close-failure");
        var provenanceFailure = new IllegalStateException("provenance close failed");
        var admissionFailure = new IllegalStateException("admission close failed");
        var catalog = failingCloseCatalog(provenanceFailure, admissionFailure);
        catalog.beginCapture(job);
        var failure = Objects.requireNonNull(catchThrowable(() -> {
            if (finish) catalog.finishCapture(job);
            else catalog.abortCapture(job);
        }));
        assertThat(failure).isSameAs(provenanceFailure);
        assertThat(failure.getSuppressed()).containsExactly(admissionFailure);
        try (var released = EvidenceFolderLease.tryAcquire(root, job.getWorkspaceId(), job.getId())
                .orElseThrow()) {
            assertThat(released).isNotNull();
        }
        var next = job("same-thread-after-close-failure");
        catalog.beginCapture(next);
        try (var transferred = catalog.finishCapture(next)) {
            assertThat(transferred).isNotNull();
            assertThat(EvidenceFolderLease.tryAcquire(root, next.getWorkspaceId(), next.getId()))
                    .isEmpty();
        }
        try (var released = EvidenceFolderLease.tryAcquire(root, next.getWorkspaceId(), next.getId())
                .orElseThrow()) {
            assertThat(released).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"begin", "finish", "abort"})
    void shouldPreserveCaptureFailureAndSuppressedCloseErrors(String operation) throws Exception {
        databaseTestUtils.cleanDatabase();
        var job = job("capture-failure");
        var provenanceFailure = new IllegalStateException("provenance close failed");
        var admissionFailure = new IllegalStateException("admission close failed");
        var catalog = failingCloseCatalog(provenanceFailure, admissionFailure);
        if (operation.equals("begin")) {
            jdbc.update("UPDATE review_job SET status='QUEUED' WHERE id=?", job.getId());
        } else {
            catalog.beginCapture(job);
            jdbc.update("UPDATE person_evidence_copy SET state='ERASED' WHERE job_id=?", job.getId());
        }
        var failure = Objects.requireNonNull(catchThrowable(() -> {
            switch (operation) {
                case "begin" -> catalog.beginCapture(job);
                case "finish" -> catalog.finishCapture(job);
                case "abort" -> catalog.abortCapture(job);
                default -> throw new IllegalArgumentException(operation);
            }
        }));
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        operation.equals("begin")
                                ? "This review attempt is no longer admitted"
                                : "Evidence capture ownership changed");
        assertThat(failure.getSuppressed()).containsExactly(provenanceFailure, admissionFailure);
        try (var released = EvidenceFolderLease.tryAcquire(root, job.getWorkspaceId(), job.getId())
                .orElseThrow()) {
            assertThat(released).isNotNull();
        }
        var next = job("same-thread-after-capture-failure");
        catalog.beginCapture(next);
        try (var transferred = catalog.finishCapture(next)) {
            assertThat(transferred).isNotNull();
        }
    }

    @Test
    void shouldDeferTheCaptureUntilTheHolderOfTheJobsEvidenceFolderReleasesIt() throws Exception {
        databaseTestUtils.cleanDatabase();
        var catalog = catalog(root, new ExactPersonDataCopyRecorder());
        var job = job("held-evidence-folder");
        long workspaceId = job.getWorkspaceId();
        var holder =
                EvidenceFolderLease.tryAcquire(root, workspaceId, job.getId()).orElseThrow();
        try (holder) {
            assertThatThrownBy(() -> catalog.beginCapture(job)).isInstanceOf(ReviewSourceNotReadyException.class);

            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM person_evidence_copy WHERE job_id=?", Long.class, job.getId()))
                    .isZero();
            assertThat(EvidenceFolderLease.tryAcquire(root, workspaceId, job.getId()))
                    .as("the holder keeps the folder")
                    .isEmpty();
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks
                WHERE locktype='advisory' AND classid=2165 AND objid=1 AND objsubid=2
                  AND database=(SELECT oid FROM pg_database WHERE datname=current_database())
                  AND granted
                """, Long.class))
                    .as("the deferred capture holds no copy admission")
                    .isZero();
        }

        catalog.beginCapture(job);
        try (var transferred = catalog.finishCapture(job)) {
            assertThat(transferred).isNotNull();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM person_evidence_copy WHERE job_id=?", String.class, job.getId()))
                .isEqualTo("READY");
    }

    @Test
    void shouldKeepTheRightfulCaptureWhenAnotherJobTriesToAbort() throws Exception {
        databaseTestUtils.cleanDatabase();
        var catalog = catalog(root, new ExactPersonDataCopyRecorder());
        var owner = job("capture-owner");
        var foreign = job("foreign-capture-owner");
        catalog.beginCapture(owner);
        try {
            assertThatThrownBy(() -> catalog.abortCapture(foreign)).hasMessage("Wrong evidence capture owner");
            try (var transferred = catalog.finishCapture(owner)) {
                assertThat(transferred).isNotNull();
            }
        } finally {
            catalog.abortCapture(owner);
        }
    }

    @Test
    void shouldVouchForCopiedHistoryOnlyThroughReceiptsThatIndexedTheirOwnCopies() throws Exception {
        databaseTestUtils.cleanDatabase();
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        var indexed = job("copied-history");
        var workspace = workspaces.findById(indexed.getWorkspaceId()).orElseThrow();
        var quoted = new PersonCopyIdentity("GITLAB", "https://quoted.example", "7", null);
        copy(root, catalog, recorder, indexed, quoted).close();
        assertThat(payload(indexed).path("dependencies").asString()).isEqualTo("COMPLETE");
        var legacyQuoted = new PersonCopyIdentity("GITLAB", "https://legacy.example", "8", null);
        var legacy = legacyCopy(workspace, legacyQuoted);

        var vouched = jobIn(workspace);
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.systemUTC(), catalog);
        files.beginPersonCapture(vouched);
        recorder.recordCopiedJob(indexed.getId());
        files.prepare(
                        vouched,
                        new PreparedEvidence(Map.of("inputs/history/results.json", new byte[] {'{', '}'}), null),
                        null)
                .close();
        assertThat(payload(vouched).path("dependencies").asString()).isEqualTo("COMPLETE");
        assertThat(identities(vouched)).contains("https://quoted.example#7");

        var unvouched = jobIn(workspace);
        files.beginPersonCapture(unvouched);
        recorder.recordCopiedJob(legacy.getId());
        files.prepare(
                        unvouched,
                        new PreparedEvidence(Map.of("inputs/history/results.json", new byte[] {'{', '}'}), null),
                        null)
                .close();
        assertThat(payload(unvouched).path("dependencies").asString())
                .as("a receipt from before dependencies were recorded cannot vouch for the rows" + " it copied")
                .isEqualTo("UNKNOWN");
        assertThat(identities(unvouched))
                .as("what it does name still indexes erasure")
                .contains("https://legacy.example#8");
        assertThat(catalog.traceCustody(unvouched.getId(), workspace.getId(), 0))
                .isEqualTo(EvidenceFolderPersonDataCatalog.TraceCustody.UNINDEXED_DEPENDENCY);
    }

    @Test
    void shouldReuseTheOuterReadFenceWhenErasureQueuesBeforeHistoricalAuthorization() throws Exception {
        databaseTestUtils.cleanDatabase();
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        var running = job("nested-history-fence");
        var workspace = workspaces.findById(running.getWorkspaceId()).orElseThrow();
        var source = jobIn(workspace);
        copy(root, catalog, recorder, source, new PersonCopyIdentity("GITLAB", "https://quoted.example", "7", null))
                .close();
        try (var inputs = copy(
                root,
                catalog,
                recorder,
                running,
                new PersonCopyIdentity("GITLAB", "https://quoted.example", "8", null))) {
            assertThat(inputs.filesOnDisk()).isNotEmpty();
            var pool = Executors.newFixedThreadPool(2);
            var queuedPid = new AtomicInteger();
            var erased = new AtomicBoolean();
            var erasureTask = new AtomicReference<Future<?>>();
            try {
                var read = pool.submit(() -> catalog.indexLateRead(
                        running.getId(),
                        workspace.getId(),
                        0,
                        () -> {
                            erasureTask.set(pool.submit(() -> {
                                try (var lease = fence.erase()) {
                                    assertThat(lease.jdbc()
                                                    .queryForObject(
                                                            "SELECT"
                                                                    + " EXISTS"
                                                                    + " (SELECT"
                                                                    + " 1 FROM"
                                                                    + " pg_locks"
                                                                    + " WHERE"
                                                                    + " pid=pg_backend_pid()"
                                                                    + " AND locktype='advisory'"
                                                                    + " AND classid=2165"
                                                                    + " AND objid=1"
                                                                    + " AND mode='ExclusiveLock'"
                                                                    + " AND granted)",
                                                            Boolean.class))
                                            .isTrue();
                                    erased.set(true);
                                }
                            }));
                            await().atMost(Duration.ofSeconds(5)).until(() -> {
                                var waiting = jdbc.queryForList(
                                        "SELECT pid"
                                                + " FROM"
                                                + " pg_locks"
                                                + " WHERE"
                                                + " locktype='advisory'"
                                                + " AND classid=2165"
                                                + " AND objid=1"
                                                + " AND mode='ExclusiveLock'"
                                                + " AND NOT"
                                                + " granted",
                                        Integer.class);
                                if (waiting.size() != 1) return false;
                                queuedPid.set(waiting.getFirst());
                                return true;
                            });
                            assertThat(erased.get()).isFalse();
                            return catalog.recordVouchedHistoricalJobs(workspace.getId(), Set.of(source.getId()));
                        },
                        result -> List.of()));
                assertThat(read.get(10, TimeUnit.SECONDS)).containsExactly(source.getId());
                Objects.requireNonNull(erasureTask.get()).get(5, TimeUnit.SECONDS);
                assertThat(erased.get()).isTrue();
            } finally {
                // Only this test's exact queued erasure may need cancellation after an assertion
                // timeout.
                if (!erased.get() && queuedPid.get() != 0)
                    jdbc.queryForObject("SELECT pg_cancel_backend(?)", Boolean.class, queuedPid.get());
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void shouldIndexALateReadsPeopleUnderTheFenceBeforeReturningItAndNeverReviveAnErasedReceipt() throws Exception {
        databaseTestUtils.cleanDatabase();
        var provider =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, "https://late.example"));
        var developer = new User();
        developer.setProvider(provider);
        developer.setNativeId(77L);
        developer.setLogin("late-developer");
        developer.setType(User.Type.USER);
        long developerId = Objects.requireNonNull(users.saveAndFlush(developer).getId());
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        var running = job("late-read");
        var workspace = workspaces.findById(running.getWorkspaceId()).orElseThrow();
        var source = jobIn(workspace);
        copy(root, catalog, recorder, source, new PersonCopyIdentity("GITLAB", "https://source.example", "5", null))
                .close();
        var inputs = copy(
                root, catalog, recorder, running, new PersonCopyIdentity("GITLAB", "https://late.example", "1", null));
        try (inputs) {
            var developerIdentity = new PersonCopyIdentity(
                    "GITLAB", provider.getServerUrl(), Long.toString(developer.getNativeId()), null);

            String answered = catalog.indexLateRead(
                    running.getId(),
                    workspace.getId(),
                    0,
                    () -> {
                        assertThat(jdbc.queryForObject("SELECT pg_try_advisory_lock(2165,1)", Boolean.class))
                                .as("an erasure cannot start between the read and its" + " indexing")
                                .isFalse();
                        return "support read";
                    },
                    read -> List.of(
                            historyCopy(catalog, workspace.getId(), List.of(source.getId()), developerIdentity)));

            assertThat(answered).isEqualTo("support read");
            assertThat(identities(running))
                    .as("the developer the support is about, and whoever its source review copied")
                    .contains("https://late.example#77", "https://source.example#5");
            assertThat(payload(running).path("dependencies").asString()).isEqualTo("COMPLETE");
            var person = new PersonScope(
                    null,
                    List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "77", null)),
                    List.of(developerId));
            assertThat(catalog.jobsContaining(person)).contains(running.getId());

            var legacy = legacyCopy(workspace, new PersonCopyIdentity("GITLAB", "https://legacy.example", "8", null));

            assertThatThrownBy(() -> catalog.indexLateRead(
                            running.getId(),
                            workspace.getId(),
                            0,
                            () -> "old support",
                            read -> List.of(historyCopy(
                                    catalog, workspace.getId(), List.of(legacy.getId()), developerIdentity))))
                    .isInstanceOf(CopyUnavailableException.class);
            catalog.indexLateRead(
                    running.getId(),
                    workspace.getId(),
                    0,
                    () -> "support again",
                    read -> List.of(
                            historyCopy(catalog, workspace.getId(), List.of(source.getId()), developerIdentity)));
            assertThat(payload(running).path("dependencies").asString())
                    .as("refused words never entered the attempt's copy")
                    .isEqualTo("COMPLETE");
            assertThat(catalog.traceCustody(running.getId(), workspace.getId(), 0))
                    .isEqualTo(EvidenceFolderPersonDataCatalog.TraceCustody.INDEXED);

            jdbc.update("UPDATE person_evidence_copy SET state='ERASE_REQUESTED' WHERE job_id=?", running.getId());
            String before = payload(running).toString();
            assertThatThrownBy(() -> catalog.indexLateRead(
                            running.getId(),
                            workspace.getId(),
                            0,
                            () -> "after erasure",
                            read -> List.of(historyCopy(
                                    catalog, workspace.getId(), List.of(source.getId()), developerIdentity))))
                    .isInstanceOf(CopyUnavailableException.class);
            assertThat(jdbc.queryForObject(
                            "SELECT state FROM person_evidence_copy WHERE job_id=?", String.class, running.getId()))
                    .isEqualTo("ERASE_REQUESTED");
            assertThat(payload(running).toString()).isEqualTo(before);
            assertThat(catalog.traceCustody(running.getId(), workspace.getId(), 0))
                    .isEqualTo(EvidenceFolderPersonDataCatalog.TraceCustody.NOT_HELD);
        }
    }

    @Test
    void shouldCountWhyACapturesCopiedJobsCannotVouchWithoutNamingAnyone() throws Exception {
        databaseTestUtils.cleanDatabase();
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        var indexed = job("dependency-diagnostics");
        var workspace = workspaces.findById(indexed.getWorkspaceId()).orElseThrow();
        copy(root, catalog, recorder, indexed, new PersonCopyIdentity("GITLAB", "https://indexed.example", "7", null))
                .close();
        var legacy = legacyCopy(workspace, new PersonCopyIdentity("GITLAB", "https://legacy.example", "8", null));
        var missing = jobIn(workspace);
        var erased = jobIn(workspace);
        UUID erasedReceipt = receipt(erased, "ERASED", Map.of());
        var retried = jobIn(workspace);
        receipt(
                retried,
                "READY",
                Map.of("attempt", 0, "identities", List.of(), "repositories", List.of(), "dependencies", "COMPLETE"));
        receipt(retried, "READY", Map.of("attempt", 1, "identities", List.of(), "repositories", List.of()));
        UUID retriedErasedReceipt = receipt(retried, "ERASED", Map.of());
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.systemUTC(), catalog);

        try (var diagnostics = new Diagnostics()) {
            var vouched = captureCopying(files, recorder, workspace, indexed);
            assertThat(payload(vouched).path("dependencies").asString()).isEqualTo("COMPLETE");
            assertThat(diagnostics.drain())
                    .as("a complete assessment stays quiet")
                    .isEmpty();

            var fromLegacy = captureCopying(files, recorder, workspace, legacy);
            assertThat(payload(fromLegacy).path("dependencies").asString()).isEqualTo("UNKNOWN");
            assertThat(diagnostics.drain()).containsExactly(assessed("CAPTURE", 1, 0, 1, 0, 1));

            var fromMissing = captureCopying(files, recorder, workspace, missing);
            assertThat(payload(fromMissing).path("dependencies").asString()).isEqualTo("UNKNOWN");
            assertThat(diagnostics.drain()).containsExactly(assessed("CAPTURE", 1, 1, 0, 0, 0));

            var fromErased = captureCopying(files, recorder, workspace, erased);
            assertThat(payload(fromErased).path("dependencies").asString()).isEqualTo("UNKNOWN");
            assertThat(diagnostics.drain()).containsExactly(assessed("CAPTURE", 1, 0, 1, 1, 0));

            var fromRetried = captureCopying(files, recorder, workspace, retried);
            assertThat(payload(fromRetried).path("dependencies").asString()).isEqualTo("UNKNOWN");
            assertThat(diagnostics.drain())
                    .as("one job, three receipts: the vouching one counts in neither category")
                    .containsExactly(assessed("CAPTURE", 1, 0, 3, 1, 1));
        }
        assertThat(receiptRow(erasedReceipt)).isEqualTo("ERASED {}");
        assertThat(receiptRow(retriedErasedReceipt)).isEqualTo("ERASED {}");
    }

    @Test
    void shouldRefuseUnavailableLateReadCustodyWithoutUpgradingOrRevivingItsReceipt() throws Exception {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://late-diagnostics.example"));
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        var running = job("late-read-diagnostics");
        var workspace = workspaces.findById(running.getWorkspaceId()).orElseThrow();
        var source = jobIn(workspace);
        copy(root, catalog, recorder, source, new PersonCopyIdentity("GITLAB", "https://source.example", "5", null))
                .close();
        var inputs = copy(
                root,
                catalog,
                recorder,
                running,
                new PersonCopyIdentity("GITLAB", "https://late-diagnostics.example", "1", null));
        try (inputs;
                var diagnostics = new Diagnostics()) {
            var developerIdentity = new PersonCopyIdentity("GITLAB", provider.getServerUrl(), "78", null);
            UUID vanished = UUID.randomUUID();
            catalog.indexLateRead(
                    running.getId(),
                    workspace.getId(),
                    0,
                    () -> "read",
                    read -> List.of(
                            historyCopy(catalog, workspace.getId(), List.of(source.getId()), developerIdentity)));
            assertThat(payload(running).path("dependencies").asString()).isEqualTo("COMPLETE");
            assertThat(diagnostics.drain())
                    .as("a complete late read stays quiet")
                    .isEmpty();

            assertThatThrownBy(() -> catalog.indexLateRead(
                            running.getId(),
                            workspace.getId(),
                            0,
                            () -> "read",
                            read -> List.of(historyCopy(
                                    catalog, workspace.getId(), List.of(source.getId(), vanished), developerIdentity))))
                    .isInstanceOf(CopyUnavailableException.class);
            assertThat(diagnostics.drain()).isEmpty();
            assertThat(payload(running).path("dependencies").asString()).isEqualTo("COMPLETE");

            catalog.indexLateRead(
                    running.getId(),
                    workspace.getId(),
                    0,
                    () -> "read",
                    read -> List.of(
                            historyCopy(catalog, workspace.getId(), List.of(source.getId()), developerIdentity)));
            assertThat(diagnostics.drain()).isEmpty();
            assertThat(payload(running).path("dependencies").asString())
                    .as("refused words were never copied")
                    .isEqualTo("COMPLETE");

            jdbc.update("UPDATE person_evidence_copy SET state='ERASE_REQUESTED' WHERE job_id=?", running.getId());
            String before = payload(running).toString();
            assertThatThrownBy(() -> catalog.indexLateRead(
                            running.getId(),
                            workspace.getId(),
                            0,
                            () -> "read",
                            read -> List.of(historyCopy(
                                    catalog, workspace.getId(), List.of(source.getId(), vanished), developerIdentity))))
                    .isInstanceOf(CopyUnavailableException.class);
            assertThat(diagnostics.drain())
                    .as("an unheld current copy refuses the read before disclosure")
                    .isEmpty();
            assertThat(jdbc.queryForObject(
                            "SELECT state FROM person_evidence_copy WHERE job_id=?", String.class, running.getId()))
                    .isEqualTo("ERASE_REQUESTED");
            assertThat(payload(running).toString()).isEqualTo(before);
        }
    }

    private static Map<String, Object> assessed(
            String assessment,
            int sourceJobs,
            int sourceJobsWithoutReceipts,
            int receipts,
            int notReadyReceipts,
            int unvouchedReadyReceipts) {
        return Map.of(
                "evidence.assessment", assessment,
                "evidence.sourceJobs", sourceJobs,
                "evidence.sourceJobsWithoutReceipts", sourceJobsWithoutReceipts,
                "evidence.receipts", receipts,
                "evidence.notReadyReceipts", notReadyReceipts,
                "evidence.unvouchedReadyReceipts", unvouchedReadyReceipts);
    }

    private static final class Diagnostics implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(EvidenceFolderPersonDataCatalog.class);
        private final @Nullable Level level = logger.getLevel();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        Diagnostics() {
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.INFO);
        }

        List<Map<String, Object>> drain() {
            var drained = new ArrayList<Map<String, Object>>();
            for (var event : appender.list) {
                assertThat(event.getFormattedMessage())
                        .as("no ID, path or prose is formatted into the message")
                        .isEqualTo("Copied evidence dependency assessment is incomplete");
                assertThat(event.getArgumentArray()).isNullOrEmpty();
                assertThat(event.getThrowableProxy()).isNull();
                var fields = new HashMap<String, Object>();
                event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
                drained.add(fields);
            }
            appender.list.clear();
            return drained;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(level);
        }
    }

    private ReviewJob captureCopying(
            JobEvidenceFiles files, ExactPersonDataCopyRecorder recorder, Workspace workspace, ReviewJob source)
            throws Exception {
        var job = jobIn(workspace);
        files.beginPersonCapture(job);
        recorder.recordCopiedJob(source.getId());
        files.prepare(
                        job,
                        new PreparedEvidence(Map.of("inputs/history/results.json", new byte[] {'{', '}'}), null),
                        null)
                .close();
        return job;
    }

    private UUID receipt(ReviewJob job, String state, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO person_evidence_copy(id,workspace_id,job_id,store_id,state,payload)"
                        + " VALUES (?,?,?,?,?,CAST(? AS jsonb))",
                id,
                job.getWorkspaceId(),
                job.getId(),
                UUID.randomUUID(),
                state,
                mapper.writeValueAsString(payload));
        return id;
    }

    private @Nullable String receiptRow(UUID id) {
        return jdbc.queryForObject(
                "SELECT state||' '||payload::text FROM person_evidence_copy WHERE id=?", String.class, id);
    }

    private JsonNode payload(ReviewJob job) {
        return mapper.readTree(Objects.requireNonNull(jdbc.queryForObject(
                "SELECT payload::text FROM person_evidence_copy WHERE job_id=?", String.class, job.getId())));
    }

    // jsonb does not preserve key order; compare identities rather than serialized text.
    private List<String> identities(ReviewJob job) {
        var names = new ArrayList<String>();
        payload(job)
                .path("identities")
                .forEach(identity -> names.add(identity.path("providerOrigin").asString()
                        + "#"
                        + identity.path("subject").asString()));
        return names;
    }

    private ReviewJob legacyCopy(Workspace workspace, PersonCopyIdentity identity) {
        var legacy = jobIn(workspace);
        receipt(legacy, "READY", Map.of("attempt", 0, "identities", List.of(identity), "repositories", List.of()));
        return legacy;
    }

    private HistoryCopy historyCopy(
            EvidenceFolderPersonDataCatalog catalog,
            long workspaceId,
            List<UUID> producingJobs,
            PersonCopyIdentity developer) {
        var custody = catalog.custody(workspaceId, producingJobs);
        var identities = new ArrayList<>(custody.copiedIdentities());
        identities.add(developer);
        return new HistoryCopy(custody.complete(), identities, custody.repositoryIds(), custody.sourceJobIds());
    }

    private ReviewJob job(String name) {
        return jobIn(workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(name)));
    }

    private ReviewJob jobIn(Workspace workspace) {
        var work = modelFixtures.work(
                workspace,
                "copy-custody-" + UUID.randomUUID().toString().substring(0, 8),
                "chat.thread",
                sourceIds.incrementAndGet());
        var job = work.job();
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setLeaseId(LEASE);
        job.setLeasedUntil(Instant.now().plus(Duration.ofMinutes(10)));
        return jobs.saveAndFlush(job);
    }

    private PreparedJobInputs copy(
            Path store,
            EvidenceFolderPersonDataCatalog catalog,
            PersonDataCopyRecorder recorder,
            ReviewJob job,
            PersonCopyIdentity identity) {
        var files = new JobEvidenceFiles(new FabricLayout(store.toString()), jobs, Clock.systemUTC(), catalog);
        files.beginPersonCapture(job);
        recorder.recordIdentity(identity);
        return files.prepare(
                job,
                new PreparedEvidence(
                        Map.of("context/people/person.json", "COPIED-PROFILE-CANARY".getBytes(StandardCharsets.UTF_8)),
                        null),
                null);
    }

    @Test
    void erasureWaitsForCaptureWithoutHoldingTheRequestRowAndRejectsTheChangedPreview() throws Exception {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://capture-race.example"));
        var administrator = new Account("Administrator");
        administrator.setAppRole(AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var preview = personData.preview(
                administratorId,
                null,
                List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)));
        UUID requestId = preview.request().getId();
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        var job = job("folder-capture-race");
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.systemUTC(), catalog);
        files.beginPersonCapture(job);
        recorder.recordIdentity(new PersonCopyIdentity("GITLAB", "https://capture-race.example", "42", null));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var erasure = executor.submit(
                    () -> assertThatThrownBy(() -> personData.requestErasure(requestId, administratorId, true))
                            .isInstanceOf(ResponseStatusException.class)
                            .hasMessageContaining("preview scope changed"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND"
                            + " classid=2165 AND objid=1 AND NOT granted)",
                    Boolean.class))) {
                if (System.nanoTime() > deadline)
                    throw new AssertionError("Erasure did not wait for the capture fence");
                Thread.sleep(20);
            }
            var tx = new TransactionTemplate(transactions);
            tx.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout='2s'");
                assertThat(requests.lock(requestId).orElseThrow().getState())
                        .isEqualTo(PersonDataRequest.State.PREVIEW);
            });
            assertThat(erasure.isDone()).isFalse();
            files.abortPersonCapture(job);
            erasure.get(10, TimeUnit.SECONDS);
            assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.PREVIEW);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM identity_restriction WHERE provider_id=?",
                            Long.class,
                            provider.getId()))
                    .isZero();
        } finally {
            files.abortPersonCapture(job);
            executor.shutdownNow();
        }
    }

    @Test
    void aNestedReadOnlyProducerCommitsItsReceiptBeforeWritingAnyBytes() {
        databaseTestUtils.cleanDatabase();
        var provider =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, "https://receipt.example"));
        var identity = new PersonCopyIdentity("GITLAB", "https://receipt.example", "42", null);
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        var job = job("folder-read-only");
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.systemUTC(), catalog);
        files.beginPersonCapture(job);
        try {
            var read = new TransactionTemplate(transactions);
            read.setReadOnly(true);
            read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            read.executeWithoutResult(status -> {
                try (var capture = recorder.begin()) {
                    recorder.recordIdentity(identity);
                    assertThat(capture.identities()).containsExactly(identity);
                    assertThat(jdbc.queryForObject(
                                    "SELECT payload->'identities'->0->>'subject'"
                                            + " FROM person_evidence_copy WHERE"
                                            + " job_id=?",
                                    String.class,
                                    job.getId()))
                            .isEqualTo("42");
                    var person = new PersonScope(
                            null,
                            List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)),
                            List.of());
                    assertThat(catalog.contributors().getFirst().select(person).rows())
                            .hasSize(1);
                    assertThat(root.resolve("jobs")).doesNotExist();
                }
            });
        } finally {
            files.abortPersonCapture(job);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM person_evidence_copy WHERE job_id=?", String.class, job.getId()))
                .isEqualTo("READY");
    }

    @Test
    void waitsForTheRuntimeAndErasesEveryAttemptInTwoWorkspacesWithoutTouchingAnotherPerson() throws Exception {
        databaseTestUtils.cleanDatabase();
        var provider =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, "https://folder.example"));
        var target = new PersonCopyIdentity("GITLAB", "https://folder.example", "42", null);
        var other = new PersonCopyIdentity("GITLAB", "https://folder.example", "84", null);
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        var store = catalog.contributors().getFirst();
        var first = job("folder-first");
        var second = job("folder-second");
        var unrelated = job("folder-other");
        var firstInputs = copy(root, catalog, recorder, first, target);
        var secondInputs = copy(root, catalog, recorder, second, target);
        var otherInputs = copy(root, catalog, recorder, unrelated, other);
        var firstFolder = root.resolve("jobs")
                .resolve(Long.toString(first.getWorkspaceId()))
                .resolve(first.getId().toString());
        var secondFolder = root.resolve("jobs")
                .resolve(Long.toString(second.getWorkspaceId()))
                .resolve(second.getId().toString());
        var otherFolder = root.resolve("jobs")
                .resolve(Long.toString(unrelated.getWorkspaceId()))
                .resolve(unrelated.getId().toString());
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.systemUTC(), catalog);
        files.bind(first.getId(), 0, "synthetic-indexed-image")
                .accept(Map.of(
                        "traces/manifest.json",
                        "{\"sessions\":[],\"sessionScanTruncated\":false}".getBytes(StandardCharsets.UTF_8),
                        "traces/0001.jsonl",
                        "{\"type\":\"session\",\"cwd\":\"SYNTHETIC-TRACE-CANARY\"}\n"
                                .getBytes(StandardCharsets.UTF_8)));
        Path trace;
        try (var retained = Files.list(firstFolder)) {
            var traces = retained.filter(
                            folder -> folder.getFileName().toString().endsWith(".trace"))
                    .toList();
            assertThat(traces).hasSize(1);
            trace = traces.getFirst();
        }
        assertThat(trace.resolve("0001.jsonl")).exists();
        assertThat(mapper.readTree(Files.readString(trace.resolve("record.json")))
                        .path("retention")
                        .asString())
                .isEqualTo("NATIVE_TRANSCRIPT");
        Files.createDirectories(firstFolder.resolve(".failed.preparing-orphan"));
        Files.writeString(firstFolder.resolve(".failed.preparing-orphan/copied.txt"), "ORPHAN-PROFILE-CANARY");
        var person = new PersonScope(
                null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)), List.of());
        var selected = store.select(person);
        assertThat(selected.rows()).hasSize(2);
        assertThat(catalog.jobsContaining(person)).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(mapper.writeValueAsString(store.export(selected, person)))
                .doesNotContain("COPIED-PROFILE", "authorLogin", "authorName");
        var pool = Executors.newSingleThreadExecutor();
        try {
            var removal = pool.submit(() -> store.prepareErasure(selected));
            awaitErasureRequest(first.getId());
            assertThat(removal.isDone()).isFalse();
            assertThat(firstFolder).exists();
            assertThat(secondFolder).exists();
            firstInputs.close();
            secondInputs.close();
            removal.get(10, TimeUnit.SECONDS);
            assertThat(trace)
                    .as("the retained native transcript is removed before erasure acknowledgement")
                    .doesNotExist();
            assertThat(firstFolder).doesNotExist();
            assertThat(secondFolder).doesNotExist();
            assertThat(otherFolder).exists();
            assertThat(store.erase(selected, person)).isEqualTo(2);
            assertThat(store.erase(selected, person)).isZero();
            assertThat(store.select(person).rows()).isEmpty();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM person_evidence_copy WHERE job_id=?",
                            Integer.class,
                            unrelated.getId()))
                    .isEqualTo(1);
        } finally {
            firstInputs.close();
            secondInputs.close();
            otherInputs.close();
            pool.shutdownNow();
        }
    }

    @Autowired
    private WorkspaceLifecycleService workspaceLifecycle;

    @Test
    void shouldEraseDeveloperlessFileProofThroughRetainedCustodyAfterTerminalJobExpiry() {
        var provider = providers.saveAndFlush(new IdentityProvider(
                IdentityProviderType.GITLAB, "https://expired-file-" + UUID.randomUUID() + ".example"));
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(
                "expired-file-" + UUID.randomUUID().toString().substring(0, 8)));
        var work = modelFixtures.work(
                workspace, "file-proof-" + UUID.randomUUID().toString().substring(0, 8), "scm.file", 1L);
        var job = work.job();
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setLeaseId(OTHER_LEASE);
        job.setLeasedUntil(Instant.now().plus(Duration.ofMinutes(10)));
        jobs.saveAndFlush(job);
        var recorder = new ExactPersonDataCopyRecorder();
        var owner = catalog(root, recorder);
        var identity = new PersonCopyIdentity("GITLAB", provider.getServerUrl(), "42", null);
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.systemUTC(), owner);
        files.beginPersonCapture(job);
        recorder.recordIdentity(identity);
        try (var inputs = files.prepare(
                job,
                new PreparedEvidence(
                        Map.of("repository/src/personal.txt", "PERSONAL-FILE-PROOF".getBytes(StandardCharsets.UTF_8)),
                        null),
                null)) {
            assertThat(inputs.filesOnDisk()).containsKey("repository/src/personal.txt");
        }
        var folder = root.resolve("jobs")
                .resolve(Long.toString(job.getWorkspaceId()))
                .resolve(job.getId().toString());
        jdbc.update(
                "UPDATE review_job SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP WHERE" + " id=?", job.getId());
        jdbc.update("DELETE FROM review_job WHERE id=?", job.getId());
        var person = new PersonScope(
                null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)), List.of());
        var store = owner.contributors().getFirst();
        var selected = store.select(person);

        assertThat(jobs.findById(job.getId())).isEmpty();
        assertThat(owner.jobsContaining(person)).containsExactly(job.getId());
        assertThat(selected.rows()).hasSize(1);
        assertThat(folder).exists();
        assertThat(jdbc.queryForObject(
                        "SELECT payload::text FROM person_evidence_copy WHERE job_id=?", String.class, job.getId()))
                .contains("42");

        store.prepareErasure(selected);
        assertThat(folder).doesNotExist();
        assertThat(jdbc.queryForObject(
                        "SELECT payload::text FROM person_evidence_copy WHERE job_id=?", String.class, job.getId()))
                .contains("42");
        assertThat(store.erase(selected, person)).isEqualTo(1);
        assertThat(store.select(person).rows()).isEmpty();
    }

    @Test
    void shouldSelectAndSuppressCopiedNativeKeysAcrossEquivalentOriginSpellings() {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "HTTPS://ORIGIN-COPY.EXAMPLE:443/"));
        var identity = new PersonCopyIdentity("GITLAB", "https://origin-copy.example", "42", null);
        var recorder = new ExactPersonDataCopyRecorder();
        var owner = catalog(root, recorder);
        var first = job("folder-origin-copy");
        copy(root, owner, recorder, first, identity).close();
        var store = owner.contributors().getFirst();
        var scope = new PersonScope(
                null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)), List.of());
        var selected = store.select(scope);
        assertThat(selected.rows()).hasSize(1);
        store.prepareErasure(selected);
        assertThat(store.erase(selected, scope)).isEqualTo(1);
        databaseTestUtils.erase(Objects.requireNonNull(provider.getId()), "42", "");
        var blocked = job("folder-origin-blocked");
        assertThatThrownBy(() -> copy(root, owner, recorder, blocked, identity))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Copied evidence contains an erased native identity");
        assertThat(root.resolve("jobs")
                        .resolve(Long.toString(blocked.getWorkspaceId()))
                        .resolve(blocked.getId().toString()))
                .doesNotExist();
        assertThat(store.select(scope).rows()).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT payload::text FROM person_evidence_copy WHERE job_id=?", String.class, blocked.getId()))
                .isEqualTo("{}");
    }

    @Test
    void shouldRemovePurgedWorkspaceCopiesAfterCommitWithoutLosingAnOfflineOwnersReceipt() throws Exception {
        databaseTestUtils.cleanDatabase();
        var identity = new PersonCopyIdentity("GITLAB", "https://workspace-folder.example", "42", null);
        var recorder = new ExactPersonDataCopyRecorder();
        Path mounted = root.resolve("workspace-worker-volume");
        var owner = catalog(mounted, recorder);
        var erased = job("folder-workspace-purge");
        var other = job("folder-workspace-keep");
        copy(mounted, owner, recorder, erased, identity).close();
        copy(mounted, owner, recorder, other, identity).close();
        jdbc.update("UPDATE review_job SET status='COMPLETED' WHERE id=?", erased.getId());
        Path erasedFolder = mounted.resolve("jobs")
                .resolve(Long.toString(erased.getWorkspaceId()))
                .resolve(erased.getId().toString());
        Path otherFolder = mounted.resolve("jobs")
                .resolve(Long.toString(other.getWorkspaceId()))
                .resolve(other.getId().toString());

        workspaceLifecycle.purgeWorkspace(
                workspaces.findById(erased.getWorkspaceId()).orElseThrow().getWorkspaceSlug());

        assertThat(jobs.findById(erased.getId())).isEmpty();
        assertThat(erasedFolder).exists();
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM person_evidence_copy WHERE job_id=?", String.class, erased.getId()))
                .isEqualTo("PURGE_REQUESTED");
        assertThat(jdbc.queryForObject(
                        "SELECT payload::text FROM person_evidence_copy WHERE job_id=?", String.class, erased.getId()))
                .contains("42");
        var restarted = catalog(mounted, new ExactPersonDataCopyRecorder());
        restarted.removeLocalRequests();
        restarted.removeLocalRequests();
        assertThat(erasedFolder).doesNotExist();
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM person_evidence_copy WHERE job_id=?", String.class, erased.getId()))
                .isEqualTo("ERASED");
        assertThat(jdbc.queryForObject(
                        "SELECT payload::text FROM person_evidence_copy WHERE job_id=?", String.class, erased.getId()))
                .isEqualTo("{}");
        assertThat(otherFolder).exists();
        assertThat(jobs.findById(other.getId())).isPresent();
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM person_evidence_copy WHERE job_id=?", String.class, other.getId()))
                .isEqualTo("READY");
    }

    @Test
    void anOfflineMountedOwnerCannotBeAcknowledgedByAnEmptyServerFolderAndCanResumeAfterRestart() {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://offline-folder.example"));
        var identity = new PersonCopyIdentity("GITLAB", "https://offline-folder.example", "42", null);
        var recorder = new ExactPersonDataCopyRecorder();
        Path mounted = root.resolve("worker-volume");
        Path server = root.resolve("server-empty");
        var owner = catalog(mounted, recorder);
        var job = job("folder-offline");
        copy(mounted, owner, recorder, job, identity).close();
        var serverStore = catalog(server, recorder).contributors().getFirst();
        var selectedScope = new PersonScope(
                null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)), List.of());
        var selected = serverStore.select(selectedScope);
        assertThat(selected.rows()).hasSize(1);
        assertThatThrownBy(() -> serverStore.prepareErasure(selected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not acknowledged");
        assertThatThrownBy(() -> serverStore.erase(selected, selectedScope)).isInstanceOf(IllegalStateException.class);
        jdbc.update("DELETE FROM review_job WHERE id=?", job.getId());
        var restarted = catalog(mounted, new ExactPersonDataCopyRecorder());
        restarted.removeLocalRequests();
        serverStore.prepareErasure(selected);
        assertThat(serverStore.erase(selected, selectedScope)).isEqualTo(1);
        assertThat(mounted.resolve("jobs")
                        .resolve(Long.toString(job.getWorkspaceId()))
                        .resolve(job.getId().toString()))
                .doesNotExist();
    }

    @Test
    void removesARepositoryCacheFolderWithoutRewritingHistoryOrErasingAnUnrelatedJobsRecordedContent() {
        databaseTestUtils.cleanDatabase();
        var provider =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, "https://git-folder.example"));
        var target = new User();
        target.setProvider(provider);
        target.setNativeId(42L);
        target.setLogin("not-a-matching-key");
        target.setType(User.Type.USER);
        target = users.saveAndFlush(target);
        var rows = new SchemaRowSeeder(jdbc);
        rows.insert(
                "repository",
                Map.of("id", 998801L, "native_id", 998801L, "provider_id", Objects.requireNonNull(provider.getId())));
        rows.insert(
                "git_commit",
                Map.of(
                        "id",
                        998802L,
                        "repository_id",
                        998801L,
                        "sha",
                        "a".repeat(40),
                        "author_id",
                        target.getId(),
                        "message",
                        "Source history stays"));
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        var job = job("folder-git-history");
        jdbc.update(
                "UPDATE review_job SET output=CAST(? AS jsonb) WHERE id=?",
                "{\"visible\":\"Another developer's recorded result\"}",
                job.getId());
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.systemUTC(), catalog);
        files.beginPersonCapture(job);
        recorder.recordRepository(998801L);
        files.prepare(
                        job,
                        new PreparedEvidence(
                                Map.of(
                                        "repos/reviewed/.git/source-cache",
                                        "Git objects stay unchanged until the folder is deleted"
                                                .getBytes(StandardCharsets.UTF_8)),
                                null),
                        null)
                .close();
        var person = new PersonScope(
                null,
                List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)),
                List.of(target.getId()));
        var store = catalog.contributors().getFirst();
        var selected = store.select(person);
        assertThat(selected.rows()).hasSize(1);
        assertThat(catalog.jobsContaining(person)).isEmpty();
        store.prepareErasure(selected);
        assertThat(store.erase(selected, person)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT message FROM git_commit WHERE id=998802", String.class))
                .isEqualTo("Source history stays");
        assertThat(jdbc.queryForObject(
                        "SELECT output->>'visible' FROM review_job WHERE id=?", String.class, job.getId()))
                .isEqualTo("Another developer's recorded result");
    }

    @Test
    void shouldRejectChangedNativeOwnershipEvenWhenTheSelectedCopyKeyIsUnchanged() {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://same-copy-key.example"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "not-a-matching-key", provider));
        var administrator = new Account("Administrator");
        administrator.setAppRole(AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var rows = new SchemaRowSeeder(jdbc);
        rows.insert(
                "repository",
                Map.of("id", 997801L, "native_id", 997801L, "provider_id", Objects.requireNonNull(provider.getId())));
        rows.insert(
                "git_commit",
                Map.of(
                        "id",
                        997802L,
                        "repository_id",
                        997801L,
                        "sha",
                        "b".repeat(40),
                        "author_id",
                        target.getId(),
                        "message",
                        "Upstream cache"));
        var recorder = new ExactPersonDataCopyRecorder();
        var catalog = catalog(root, recorder);
        var job = job("folder-same-copy-key");
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.systemUTC(), catalog);
        files.beginPersonCapture(job);
        recorder.recordRepository(997801L);
        var prepared =
                files.prepare(job, new PreparedEvidence(Map.of("repos/reviewed/cache", new byte[] {1}), null), null);
        Path copiedCache = Objects.requireNonNull(prepared.filesOnDisk().get("repos/reviewed/cache"));
        prepared.close();
        var identities = List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null));
        var preview = personData.preview(administratorId, null, identities);
        UUID requestId = preview.request().getId();
        var original = mapper.readValue(Objects.requireNonNull(preview.request().getScopeJson()), PersonScope.class);
        assertThat(original.derivedJobIds()).isEmpty();
        var store = catalog.contributors().getFirst();
        var selected = store.select(original);
        assertThat(selected.rows()).hasSize(1);
        // Capture may add a secondary native subject to an existing repository-only receipt.
        jdbc.update(
                """
                UPDATE person_evidence_copy SET payload=jsonb_set(payload,'{identities}',CAST(? AS jsonb))
                WHERE job_id=?
                """,
                mapper.writeValueAsString(
                        List.of(new PersonCopyIdentity("GITLAB", "https://same-copy-key.example", "42", null))),
                job.getId());
        assertThat(store.select(original)).isEqualTo(selected);
        assertThat(catalog.jobsContaining(original)).containsExactly(job.getId());
        assertThatThrownBy(() -> personData.export(requestId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("preview scope changed");
        assertThatThrownBy(() -> personData.requestErasure(requestId, administratorId, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("preview scope changed");
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.PREVIEW);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(copiedCache).exists();
    }

    private void awaitErasureRequest(UUID jobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM person_evidence_copy WHERE job_id=? AND" + " state='ERASE_REQUESTED')",
                    Boolean.class,
                    jobId))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Erasure request was not persisted");
    }
}
