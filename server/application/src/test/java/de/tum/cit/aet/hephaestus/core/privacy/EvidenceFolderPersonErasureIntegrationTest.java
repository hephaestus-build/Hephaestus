package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderLease;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobExecutor;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
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
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SchemaRowSeeder;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

class EvidenceFolderPersonErasureIntegrationTest extends BaseIntegrationTest {
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
    private AgentJobRepository jobs;

    @Autowired
    private UserRepository users;

    @Autowired
    private PersonDataCopyFence fence;

    @Autowired
    private ObjectProvider<AgentJobLifecycleService> lifecycles;

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
        ObjectProvider<AgentJobExecutor> executor =
                new DefaultListableBeanFactory().getBeanProvider(AgentJobExecutor.class);
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
        var nativeRecorder = new ExactPersonDataCopyRecorder(jdbc);
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
        try (var released = EvidenceFolderLease.tryAcquire(
                        root, job.getWorkspace().getId(), job.getId())
                .orElseThrow()) {
            assertThat(released).isNotNull();
        }
        var next = job("same-thread-after-close-failure");
        catalog.beginCapture(next);
        try (var transferred = catalog.finishCapture(next)) {
            assertThat(transferred).isNotNull();
            assertThat(EvidenceFolderLease.tryAcquire(root, next.getWorkspace().getId(), next.getId()))
                    .isEmpty();
        }
        try (var released = EvidenceFolderLease.tryAcquire(
                        root, next.getWorkspace().getId(), next.getId())
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
            jdbc.update("UPDATE agent_job SET status='QUEUED' WHERE id=?", job.getId());
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
        try (var released = EvidenceFolderLease.tryAcquire(
                        root, job.getWorkspace().getId(), job.getId())
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
    void shouldKeepTheRightfulCaptureWhenAnotherJobTriesToAbort() throws Exception {
        databaseTestUtils.cleanDatabase();
        var catalog = catalog(root, new ExactPersonDataCopyRecorder(jdbc));
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
        var recorder = new ExactPersonDataCopyRecorder(jdbc);
        var catalog = catalog(root, recorder);
        var indexed = job("copied-history");
        var workspace = indexed.getWorkspace();
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
                        new PreparedEvidence(Map.of("inputs/history/observations.json", new byte[] {'{', '}'}), null),
                        null)
                .close();
        assertThat(payload(vouched).path("dependencies").asString()).isEqualTo("COMPLETE");
        assertThat(identities(vouched)).contains("https://quoted.example#7");

        var unvouched = jobIn(workspace);
        files.beginPersonCapture(unvouched);
        recorder.recordCopiedJob(legacy.getId());
        files.prepare(
                        unvouched,
                        new PreparedEvidence(Map.of("inputs/history/observations.json", new byte[] {'{', '}'}), null),
                        null)
                .close();
        assertThat(payload(unvouched).path("dependencies").asString())
                .as("a receipt from before dependencies were recorded cannot vouch for the rows it copied")
                .isEqualTo("UNKNOWN");
        assertThat(identities(unvouched))
                .as("what it does name still indexes erasure")
                .contains("https://legacy.example#8");
        assertThat(catalog.traceCustody(unvouched.getId(), workspace.getId(), 0))
                .isEqualTo(EvidenceFolderPersonDataCatalog.TraceCustody.UNINDEXED_DEPENDENCY);
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
        var recorder = new ExactPersonDataCopyRecorder(jdbc);
        var catalog = catalog(root, recorder);
        var running = job("late-read");
        var workspace = running.getWorkspace();
        var source = jobIn(workspace);
        copy(root, catalog, recorder, source, new PersonCopyIdentity("GITLAB", "https://source.example", "5", null))
                .close();
        var inputs = copy(
                root, catalog, recorder, running, new PersonCopyIdentity("GITLAB", "https://late.example", "1", null));
        try (inputs) {
            UUID supported = observation(source, developerId);

            String answered = catalog.indexLateRead(
                    running.getId(),
                    workspace.getId(),
                    0,
                    () -> {
                        assertThat(jdbc.queryForObject("SELECT pg_try_advisory_lock(2165,1)", Boolean.class))
                                .as("an erasure cannot start between the read and its indexing")
                                .isFalse();
                        return "support read";
                    },
                    read -> Set.of(supported),
                    read -> Set.of());

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
            UUID legacySupport = observation(legacy, developerId);
            catalog.indexLateRead(
                    running.getId(),
                    workspace.getId(),
                    0,
                    () -> "old support",
                    read -> Set.of(legacySupport),
                    read -> Set.of());
            catalog.indexLateRead(
                    running.getId(),
                    workspace.getId(),
                    0,
                    () -> "support again",
                    read -> Set.of(supported),
                    read -> Set.of());
            assertThat(payload(running).path("dependencies").asString())
                    .as("never upgraded once a dependency could not vouch")
                    .isEqualTo("UNKNOWN");
            assertThat(catalog.traceCustody(running.getId(), workspace.getId(), 0))
                    .isEqualTo(EvidenceFolderPersonDataCatalog.TraceCustody.UNINDEXED_DEPENDENCY);

            jdbc.update("UPDATE person_evidence_copy SET state='ERASE_REQUESTED' WHERE job_id=?", running.getId());
            String before = payload(running).toString();
            assertThat(catalog.indexLateRead(
                            running.getId(),
                            workspace.getId(),
                            0,
                            () -> "after erasure",
                            read -> Set.of(supported),
                            read -> Set.of()))
                    .isEqualTo("after erasure");
            assertThat(jdbc.queryForObject(
                            "SELECT state FROM person_evidence_copy WHERE job_id=?", String.class, running.getId()))
                    .isEqualTo("ERASE_REQUESTED");
            assertThat(payload(running).toString()).isEqualTo(before);
            assertThat(catalog.traceCustody(running.getId(), workspace.getId(), 0))
                    .isEqualTo(EvidenceFolderPersonDataCatalog.TraceCustody.NOT_HELD);
        }
    }

    private JsonNode payload(AgentJob job) {
        return mapper.readTree(Objects.requireNonNull(jdbc.queryForObject(
                "SELECT payload::text FROM person_evidence_copy WHERE job_id=?", String.class, job.getId())));
    }

    /** The receipt's people as origin#subject; jsonb keeps no key order to compare text against. */
    private List<String> identities(AgentJob job) {
        var names = new ArrayList<String>();
        payload(job)
                .path("identities")
                .forEach(identity -> names.add(identity.path("providerOrigin").asString() + "#"
                        + identity.path("subject").asString()));
        return names;
    }

    /** A receipt written before receipts recorded the jobs whose rows they copied. */
    private AgentJob legacyCopy(Workspace workspace, PersonCopyIdentity identity) {
        var legacy = jobIn(workspace);
        jdbc.update(
                "INSERT INTO person_evidence_copy(id,workspace_id,job_id,store_id,state,payload) VALUES (?,?,?,?,'READY',CAST(? AS jsonb))",
                UUID.randomUUID(),
                workspace.getId(),
                legacy.getId(),
                UUID.randomUUID(),
                mapper.writeValueAsString(
                        Map.of("attempt", 0, "identities", List.of(identity), "repositories", List.of())));
        return legacy;
    }

    /**
     * An observation another review recorded. Seeded as SchemaRowSeeder documents, with foreign keys off: the late
     * index reads only its workspace, source job and developer, and nothing here depends on the rows it would reference.
     */
    private UUID observation(AgentJob source, long aboutUserId) {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            new SchemaRowSeeder(jdbc)
                    .insert(
                            "observation",
                            Map.of(
                                    "id",
                                    id,
                                    "workspace_id",
                                    source.getWorkspace().getId(),
                                    "agent_job_id",
                                    source.getId(),
                                    "about_user_id",
                                    aboutUserId,
                                    "outcome",
                                    "NOT_MET",
                                    "severity",
                                    "MINOR"));
        });
        return id;
    }

    private AgentJob job(String name) {
        return jobIn(workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(name)));
    }

    private AgentJob jobIn(Workspace workspace) {
        var job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.CONVERSATION_REVIEW);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("worker-native-id");
        job.setConfigSnapshot(mapper.createObjectNode());
        return jobs.saveAndFlush(job);
    }

    private PreparedJobInputs copy(
            Path store,
            EvidenceFolderPersonDataCatalog catalog,
            PersonDataCopyRecorder recorder,
            AgentJob job,
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
        var recorder = new ExactPersonDataCopyRecorder(jdbc);
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
                    "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND classid=2165 AND objid=1 AND NOT granted)",
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
                            "SELECT count(*) FROM person_suppression WHERE provider_id=?",
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
        var recorder = new ExactPersonDataCopyRecorder(jdbc);
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
                                    "SELECT payload->'identities'->0->>'subject' FROM person_evidence_copy WHERE job_id=?",
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
        var recorder = new ExactPersonDataCopyRecorder(jdbc);
        var catalog = catalog(root, recorder);
        var store = catalog.contributors().getFirst();
        var first = job("folder-first");
        var second = job("folder-second");
        var unrelated = job("folder-other");
        var firstInputs = copy(root, catalog, recorder, first, target);
        var secondInputs = copy(root, catalog, recorder, second, target);
        var otherInputs = copy(root, catalog, recorder, unrelated, other);
        var firstFolder = root.resolve("jobs")
                .resolve(first.getWorkspace().getId().toString())
                .resolve(first.getId().toString());
        var secondFolder = root.resolve("jobs")
                .resolve(second.getWorkspace().getId().toString())
                .resolve(second.getId().toString());
        var otherFolder = root.resolve("jobs")
                .resolve(unrelated.getWorkspace().getId().toString())
                .resolve(unrelated.getId().toString());
        Files.createDirectories(firstFolder.resolve(".failed.preparing-orphan"));
        Files.writeString(firstFolder.resolve(".failed.preparing-orphan/copied.txt"), "ORPHAN-PROFILE-CANARY");
        var person = new PersonScope(
                null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)), List.of());
        var selected = store.select(person);
        assertThat(selected.rows()).hasSize(2);
        assertThat(catalog.jobsContaining(person)).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(mapper.writeValueAsString(store.export(selected)))
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
            assertThat(firstFolder).doesNotExist();
            assertThat(secondFolder).doesNotExist();
            assertThat(otherFolder).exists();
            assertThat(store.erase(selected)).isEqualTo(2);
            assertThat(store.erase(selected)).isZero();
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
    void shouldSelectAndSuppressCopiedNativeKeysAcrossEquivalentOriginSpellings() {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "HTTPS://ORIGIN-COPY.EXAMPLE:443/"));
        var identity = new PersonCopyIdentity("GITLAB", "https://origin-copy.example", "42", null);
        var recorder = new ExactPersonDataCopyRecorder(jdbc);
        var owner = catalog(root, recorder);
        var first = job("folder-origin-copy");
        copy(root, owner, recorder, first, identity).close();
        var store = owner.contributors().getFirst();
        var scope = new PersonScope(
                null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)), List.of());
        var selected = store.select(scope);
        assertThat(selected.rows()).hasSize(1);
        store.prepareErasure(selected);
        assertThat(store.erase(selected)).isEqualTo(1);
        jdbc.update(
                "INSERT INTO person_suppression(id,provider_id,subject,team_key) VALUES (?,?,?,'')",
                UUID.randomUUID(),
                provider.getId(),
                "42");
        var blocked = job("folder-origin-blocked");
        assertThatThrownBy(() -> copy(root, owner, recorder, blocked, identity))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Copied evidence contains an erased native identity");
        assertThat(root.resolve("jobs")
                        .resolve(blocked.getWorkspace().getId().toString())
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
        var recorder = new ExactPersonDataCopyRecorder(jdbc);
        Path mounted = root.resolve("workspace-worker-volume");
        var owner = catalog(mounted, recorder);
        var erased = job("folder-workspace-purge");
        var other = job("folder-workspace-keep");
        copy(mounted, owner, recorder, erased, identity).close();
        copy(mounted, owner, recorder, other, identity).close();
        jdbc.update("UPDATE agent_job SET status='COMPLETED' WHERE id=?", erased.getId());
        Path erasedFolder = mounted.resolve("jobs")
                .resolve(erased.getWorkspace().getId().toString())
                .resolve(erased.getId().toString());
        Path otherFolder = mounted.resolve("jobs")
                .resolve(other.getWorkspace().getId().toString())
                .resolve(other.getId().toString());

        workspaceLifecycle.purgeWorkspace(erased.getWorkspace().getWorkspaceSlug());

        assertThat(jobs.findById(erased.getId())).isEmpty();
        assertThat(erasedFolder).exists();
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM person_evidence_copy WHERE job_id=?", String.class, erased.getId()))
                .isEqualTo("PURGE_REQUESTED");
        assertThat(jdbc.queryForObject(
                        "SELECT payload::text FROM person_evidence_copy WHERE job_id=?", String.class, erased.getId()))
                .contains("42");
        var restarted = catalog(mounted, new ExactPersonDataCopyRecorder(jdbc));
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
        var recorder = new ExactPersonDataCopyRecorder(jdbc);
        Path mounted = root.resolve("worker-volume");
        Path server = root.resolve("server-empty");
        var owner = catalog(mounted, recorder);
        var job = job("folder-offline");
        copy(mounted, owner, recorder, job, identity).close();
        var serverStore = catalog(server, recorder).contributors().getFirst();
        var selected = serverStore.select(new PersonScope(
                null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)), List.of()));
        assertThat(selected.rows()).hasSize(1);
        assertThatThrownBy(() -> serverStore.prepareErasure(selected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not acknowledged");
        assertThatThrownBy(() -> serverStore.erase(selected)).isInstanceOf(IllegalStateException.class);
        jdbc.update("DELETE FROM agent_job WHERE id=?", job.getId());
        var restarted = catalog(mounted, new ExactPersonDataCopyRecorder(jdbc));
        restarted.removeLocalRequests();
        serverStore.prepareErasure(selected);
        assertThat(serverStore.erase(selected)).isEqualTo(1);
        assertThat(mounted.resolve("jobs")
                        .resolve(job.getWorkspace().getId().toString())
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
        var recorder = new ExactPersonDataCopyRecorder(jdbc);
        var catalog = catalog(root, recorder);
        var job = job("folder-git-history");
        jdbc.update(
                "UPDATE agent_job SET output=CAST(? AS jsonb) WHERE id=?",
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
        assertThat(store.erase(selected)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT message FROM git_commit WHERE id=998802", String.class))
                .isEqualTo("Source history stays");
        assertThat(jdbc.queryForObject(
                        "SELECT output->>'visible' FROM agent_job WHERE id=?", String.class, job.getId()))
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
        var recorder = new ExactPersonDataCopyRecorder(jdbc);
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
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(copiedCache).exists();
    }

    private void awaitErasureRequest(UUID jobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM person_evidence_copy WHERE job_id=? AND state='ERASE_REQUESTED')",
                    Boolean.class,
                    jobId))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Erasure request was not persisted");
    }
}
