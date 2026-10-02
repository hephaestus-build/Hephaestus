package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.*;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.*;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.integration.core.connection.*;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.testconfig.*;
import de.tum.cit.aet.hephaestus.workspace.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
    private de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository users;

    @Autowired
    private PersonDataCopyFence fence;

    @Autowired
    private ObjectProvider<AgentJobLifecycleService> lifecycles;

    @Autowired
    private PersonDataService personData;

    @Autowired
    private PersonDataRequestRepository requests;

    @Autowired
    private de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository accounts;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;

    private EvidenceFolderPersonDataCatalog catalog(Path store, PersonDataCopyRecorder recorder) {
        ObjectProvider<AgentJobExecutor> executor =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(AgentJobExecutor.class);
        return new EvidenceFolderPersonDataCatalog(
                new FabricLayout(store.toString()), jdbc, namedJdbc, mapper, recorder, fence, executor, lifecycles);
    }

    private AgentJob job(String name) {
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(name));
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
        var administrator = new de.tum.cit.aet.hephaestus.core.auth.domain.Account("Administrator");
        administrator.setAppRole(de.tum.cit.aet.hephaestus.core.auth.domain.Account.AppRole.APP_ADMIN);
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
                            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                            .hasMessageContaining("preview scope changed"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND classid=2165 AND objid=1 AND NOT granted)",
                    Boolean.class))) {
                if (System.nanoTime() > deadline)
                    throw new AssertionError("Erasure did not wait for the capture fence");
                Thread.sleep(20);
            }
            var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
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
            var read = new org.springframework.transaction.support.TransactionTemplate(transactions);
            read.setReadOnly(true);
            read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            read.executeWithoutResult(status -> recorder.capture(() -> {
                recorder.recordIdentity(identity);
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
                return "no bytes exist before this receipt";
            }));
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
        var target = new de.tum.cit.aet.hephaestus.integration.scm.domain.user.User();
        target.setProvider(provider);
        target.setNativeId(42L);
        target.setLogin("not-a-matching-key");
        target.setType(de.tum.cit.aet.hephaestus.integration.scm.domain.user.User.Type.USER);
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
