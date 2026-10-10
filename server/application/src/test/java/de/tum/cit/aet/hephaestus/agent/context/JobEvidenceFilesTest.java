package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderLease;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException;
import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.core.runtime.ServerSchedulingConfig;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import tools.jackson.databind.json.JsonMapper;

class JobEvidenceFilesTest extends BaseUnitTest {
    private static EvidenceFolderPersonDataCatalog personCopies() {
        AutoCloseable released = () -> {};
        return Mockito.mock(
                EvidenceFolderPersonDataCatalog.class,
                invocation -> invocation.getMethod().getName().equals("finishCapture")
                        ? released
                        : Answers.RETURNS_DEFAULTS.answer(invocation));
    }

    @TempDir
    Path root;

    private final AgentJobRepository jobs = mock(AgentJobRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);

    private static Optional<byte[]> read(JobEvidenceFiles files, AgentJob job, String path, String sha) {
        return files.inspect(job, path, sha, reader -> {
            var output = new StringWriter();
            reader.transferTo(output);
            return output.toString().getBytes(StandardCharsets.UTF_8);
        });
    }

    @Test
    void refusesAnOversizedDiskFolderBeforeCopyingAndReleasesRenderScratch() throws Exception {
        Path oversized = root.resolve("oversized");
        try (var output = FileChannel.open(oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            output.position(SandboxGatewaySessions.WORKSPACE_BYTE_BUDGET);
            output.write(ByteBuffer.wrap(new byte[] {0}));
        }
        var released = new AtomicBoolean();
        var inputs = new PreparedEvidence(
                Map.of(), Map.of("context/large.jsonl", oversized), List.of(() -> released.set(true)), null);
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock, personCopies());
        assertThatThrownBy(() -> files.prepare(job(), inputs, null))
                .isInstanceOf(WorkspaceBudgetExceededException.class)
                .hasMessageContaining("WORKSPACE_BUDGET_EXCEEDED");
        assertThat(released.get()).isTrue();
        assertThat(root.resolve("jobs")).doesNotExist();
    }

    @Test
    void shouldDeleteCommittedAdmissionWhileTheRuntimeIsStillRunning() {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock, personCopies());
        var job = job();
        job.setStatus(AgentJobStatus.RUNNING);
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenReturn(Optional.of(job));
        byte[] bytes = "verified quote".getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var identity = new AdmissionIdentity(job.getId(), 1L, 0, "worker");
        try (var prepared = PreparedJobInputsFixtures.prepare(
                files, job, PreparedJobInputsFixtures.filesOnly(Map.of("context/quote", bytes)))) {
            assertThat(PreparedJobInputsFixtures.files(prepared)).containsKey("context/quote");
            files.discardAdmittedAttempt(identity);
            assertThat(read(files, job, "context/quote", sha)).contains(bytes);
            job.setMetadata(new JsonMapper()
                    .createObjectNode()
                    .put(ObservationAdmissionService.DIGEST_METADATA_KEY, "verified"));
            files.discardAdmittedAttempt(new AdmissionIdentity(job.getId(), 1L, 1, "worker"));
            assertThat(read(files, job, "context/quote", sha)).contains(bytes);
            files.discardAdmittedAttempt(identity);
            assertThat(read(files, job, "context/quote", sha)).isEmpty();
        }
    }

    @Test
    void shouldCleanUnknownAndFinishedFoldersImmediatelyAfterRestartButKeepRunningJobs() {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock, personCopies());
        byte[] bytes = "evidence".getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var running = job();
        running.setStatus(AgentJobStatus.RUNNING);
        var finished = job();
        finished.setStatus(AgentJobStatus.FAILED);
        var unknown = job();
        when(jobs.findByIdAndWorkspaceId(running.getId(), 1L)).thenReturn(Optional.of(running));
        when(jobs.findByIdAndWorkspaceId(finished.getId(), 1L)).thenReturn(Optional.of(finished));
        try (var active = PreparedJobInputsFixtures.prepare(
                        files, running, PreparedJobInputsFixtures.filesOnly(Map.of("context/quote", bytes)));
                var ended = PreparedJobInputsFixtures.prepare(
                        files, finished, PreparedJobInputsFixtures.filesOnly(Map.of("context/quote", bytes)));
                var orphan = PreparedJobInputsFixtures.prepare(
                        files, unknown, PreparedJobInputsFixtures.filesOnly(Map.of("context/quote", bytes)))) {
            files.cleanAfterRestart();
            assertThat(read(files, running, "context/quote", sha)).contains(bytes);
            assertThat(read(files, finished, "context/quote", sha)).isEmpty();
            assertThat(read(files, unknown, "context/quote", sha)).isEmpty();
            assertThat(PreparedJobInputsFixtures.files(active)).containsKey("context/quote");
            assertThat(ended.filesOnDisk().get("context/quote")).doesNotExist();
            assertThat(orphan.filesOnDisk().get("context/quote")).doesNotExist();
        }
    }

    @Test
    void shouldCopyDirectoryInputsWithoutExpandingTheirFileMap() throws Exception {
        Path checkout = Files.createDirectories(root.resolve("checkout"));
        Path git = Files.createDirectories(checkout.resolve(".git"));
        Path head = Files.writeString(git.resolve("HEAD"), "a".repeat(40) + "\n");
        Path script = Files.writeString(checkout.resolve("run.sh"), "echo source\n");
        Files.setPosixFilePermissions(
                script, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        var files = new JobEvidenceFiles(
                new FabricLayout(root.resolve("evidence").toString()), jobs, clock, personCopies());
        var inputs = PreparedJobInputsFixtures.inputs(
                new PreparedEvidence(
                        Map.of(),
                        Map.of("repo/.git/HEAD", head),
                        List.of(),
                        null,
                        List.of(new EvidenceDirectory("repo/", checkout))),
                null);
        try (var captured = PreparedJobInputsFixtures.prepare(files, job(), inputs)) {
            assertThat(captured.filesOnDisk()).hasSize(1);
            assertThat(captured.directories()).hasSize(1);
            Path canonical = captured.directories().getFirst().source();
            assertThat(captured.filesOnDisk().get("repo/.git/HEAD")).isEqualTo(canonical.resolve(".git/HEAD"));
            assertThat(Files.readString(canonical.resolve("run.sh"))).isEqualTo("echo source\n");
            assertThat(Files.getPosixFilePermissions(canonical.resolve("run.sh")))
                    .contains(PosixFilePermission.OWNER_EXECUTE)
                    .doesNotContain(PosixFilePermission.OWNER_WRITE);
            Files.writeString(head, "changed after capture");
            assertThat(Files.readString(canonical.resolve(".git/HEAD"))).isEqualTo("a".repeat(40) + "\n");
        }
    }

    @Test
    void shouldRejectSymlinksInsideDirectoryInputs() throws Exception {
        Path checkout = Files.createDirectories(root.resolve("checkout"));
        Files.createSymbolicLink(checkout.resolve("escape"), root.resolve("outside"));
        var files = new JobEvidenceFiles(
                new FabricLayout(root.resolve("evidence").toString()), jobs, clock, personCopies());
        var inputs = PreparedJobInputsFixtures.inputs(
                new PreparedEvidence(
                        Map.of(), Map.of(), List.of(), null, List.of(new EvidenceDirectory("repo/", checkout))),
                null);
        assertThatThrownBy(() -> PreparedJobInputsFixtures.prepare(files, job(), inputs))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRetainUnadmittedEvidenceForOneHourAfterTheOwningHandleCloses() throws Exception {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock, personCopies());
        var job = job();
        Path source = root.resolve("source.txt");
        String content = "repeated quote\nwrong line\nrepeated quote\n";
        Files.writeString(source, content);
        String sha = ProvenanceDigest.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        var raw = PreparedJobInputsFixtures.inputs(
                new PreparedEvidence(Map.of(), Map.of("context/source.txt", source), List.of(), null), null);
        try (var prepared = PreparedJobInputsFixtures.prepare(files, job, raw)) {
            Files.writeString(source, "upstream changed");
            assertThat(read(files, job, "context/source.txt", sha)).contains(content.getBytes(StandardCharsets.UTF_8));
            assertThat(files.containsUtf8AtLines(job, "context/source.txt", sha, "repeated quote", 2, 2))
                    .contains(false);
            assertThat(files.containsUtf8AtLines(job, "context/source.txt", sha, "repeated quote", 3, 3))
                    .contains(true);
            assertThatThrownBy(() -> PreparedJobInputsFixtures.prepare(files, job, raw))
                    .isInstanceOf(IllegalStateException.class);
            job.setRetryCount(1);
            assertThat(read(files, job, "context/source.txt", sha)).isEmpty();
            job.setRetryCount(0);
            assertThat(prepared.filesOnDisk().get("context/source.txt")).hasContent(content);
        }
        assertThat(read(files, job, "context/source.txt", sha)).isPresent();
        new JobEvidenceFiles(
                        new FabricLayout(root.toString()),
                        jobs,
                        Clock.offset(clock, Duration.ofHours(1)),
                        personCopies())
                .cleanEndedAttempts();
        assertThat(read(files, job, "context/source.txt", sha)).isEmpty();
    }

    @Test
    void shouldRejectEscapingPathsAndSymbolicLinksDuringPreparation() throws Exception {
        var layout = new FabricLayout(root.toString());
        var files = new JobEvidenceFiles(layout, jobs, clock, personCopies());
        assertThatThrownBy(() -> PreparedJobInputsFixtures.prepare(
                        files, job(), PreparedJobInputsFixtures.filesOnly(Map.of("../escape", new byte[0]))))
                .isInstanceOf(IllegalStateException.class);
        Path source = root.resolve("source");
        Files.writeString(source, "private");
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, source);
        var raw = PreparedJobInputsFixtures.inputs(
                new PreparedEvidence(Map.of(), Map.of("inputs/source", link), List.of(), null), null);
        assertThatThrownBy(() -> PreparedJobInputsFixtures.prepare(files, job(), raw))
                .isInstanceOf(IllegalStateException.class);
        try (var paths = Files.walk(layout.jobsRoot())) {
            assertThat(paths.filter(path -> path.getFileName().toString().contains(".preparing-")))
                    .as("a failed preparation leaves no staging directory behind")
                    .isEmpty();
        }
    }

    @Test
    void shouldVerifyDigestAfterMatchingAcrossBuffersAndRejectChangedBytes() throws Exception {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock, personCopies());
        var job = job();
        String quote = "é😀\n" + "quoted line\n".repeat(1000);
        byte[] bytes = ("x".repeat(8191) + quote).getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var prepared = PreparedJobInputsFixtures.prepare(
                files, job, PreparedJobInputsFixtures.filesOnly(Map.of("inputs/source", bytes)));
        try {
            assertThat(files.containsUtf8AtLines(job, "inputs/source", sha, quote, 1, 1001))
                    .contains(true);
            assertThatThrownBy(() -> read(files, job, "inputs/source", "a".repeat(64)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            prepared.close();
        }
    }

    @Test
    void shouldPreserveAnotherActiveWorkersFolderAndCleanLostAttemptsAfterGracePeriod() {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock, personCopies());
        var job = job();
        job.setStatus(AgentJobStatus.RUNNING);
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenReturn(Optional.of(job));
        byte[] bytes = "source".getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var prepared = PreparedJobInputsFixtures.prepare(
                files, job, PreparedJobInputsFixtures.filesOnly(Map.of("inputs/source", bytes)));
        try {
            files.cleanEndedAttempts();
            var later = new JobEvidenceFiles(
                    new FabricLayout(root.toString()), jobs, Clock.offset(clock, Duration.ofHours(2)), personCopies());
            later.cleanEndedAttempts();
            assertThat(read(files, job, "inputs/source", sha)).contains(bytes);
            job.setStatus(AgentJobStatus.CANCELLED);
            later.cleanEndedAttempts();
            assertThat(read(files, job, "inputs/source", sha)).contains(bytes);
            new JobEvidenceFiles(
                            new FabricLayout(root.toString()),
                            jobs,
                            Clock.offset(clock, Duration.ofHours(3)),
                            personCopies())
                    .cleanEndedAttempts();
            assertThat(read(files, job, "inputs/source", sha)).isEmpty();
            verify(jobs)
                    .discardRetiredArtifactInventory(
                            job.getId(), 1L, job.getRetryCount(), Objects.requireNonNull(job.getWorkerId()));
        } finally {
            prepared.close();
        }
    }

    @Test
    void shouldDeleteAdmittedFinalEvidenceImmediatelyWithoutDeletingAReplacementHandle() {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock, personCopies());
        var job = job();
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setMetadata(
                new JsonMapper().createObjectNode().put(ObservationAdmissionService.DIGEST_METADATA_KEY, "admitted"));
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenReturn(Optional.of(job));
        byte[] bytes = "source".getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var first = PreparedJobInputsFixtures.prepare(
                files, job, PreparedJobInputsFixtures.filesOnly(Map.of("inputs/source", bytes)));
        first.close();
        assertThat(read(files, job, "inputs/source", sha)).isEmpty();
        var replacement = PreparedJobInputsFixtures.prepare(
                files, job, PreparedJobInputsFixtures.filesOnly(Map.of("inputs/source", bytes)));
        try {
            first.close();
            assertThat(read(files, job, "inputs/source", sha)).contains(bytes);
        } finally {
            replacement.close();
        }
    }

    @Test
    void shouldCleanOrphanedPreparationAfterTheGracePeriod() throws Exception {
        var layout = new FabricLayout(root.toString());
        var files = new JobEvidenceFiles(layout, jobs, clock, personCopies());
        var job = job();
        Path parent = layout.jobsRoot().resolve("1").resolve(job.getId().toString());
        Files.createDirectories(parent);
        String identity = "0-" + ProvenanceDigest.sha256Hex("worker".getBytes(StandardCharsets.UTF_8));
        Path staging = Files.createTempDirectory(parent, "." + identity + ".preparing-");
        Files.writeString(staging.resolve("partial"), "not published");
        files.cleanEndedAttempts();
        assertThat(staging).exists();
        new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofHours(1)), personCopies())
                .cleanEndedAttempts();
        assertThat(staging).doesNotExist();
    }

    @Test
    void shouldPreserveOwnedGitSnapshotsBeyondTheGracePeriod() throws Exception {
        var layout = new FabricLayout(root.toString());
        var cleaner = new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofHours(2)), personCopies());
        var key = new RepositoryKey(1L, 1L);
        Path source = Files.createDirectories(root.resolve("source"));
        String sha;
        try (Git git = Git.init()
                .setInitialBranch("main")
                .setDirectory(source.toFile())
                .call()) {
            GitTestFixtures.disableSigning(git.getRepository());
            Files.writeString(source.resolve("README.md"), "Repository\n");
            git.add().addFilepattern(".").call();
            sha = git.commit()
                    .setSign(false)
                    .setMessage("Initial commit")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
                    .name();
        }
        var manager = new GitRepositoryManager(
                new GitRepositoryProperties(true, 1, 1L << 20), new GitRepositoryLockManager(), layout);
        manager.ensureRepository(key, source.toUri().toString(), null);
        Path staging;
        try (var snapshot = manager.readTreeSnapshot(key, sha)) {
            staging = snapshot.stagingDir();
            Files.setLastModifiedTime(staging, FileTime.from(clock.instant()));
            cleaner.cleanEndedAttempts();
            assertThat(staging).isDirectory();
        }
        assertThat(staging).doesNotExist();
        try (var entries = Files.list(root)) {
            assertThat(entries.filter(entry ->
                            entry.getFileName().toString().startsWith(GitRepositoryManager.GIT_SNAPSHOT_PREFIX)))
                    .isEmpty();
        }
    }

    @Test
    void shouldSweepGitSnapshotsNobodyReleasedAfterTheGracePeriod() throws Exception {
        var layout = new FabricLayout(root.toString());
        Path snapshot = Files.createDirectories(layout.root().resolve("git-snapshot-abandoned"));
        Files.writeString(snapshot.resolve("README.md"), "left behind");
        Path unrelated = Files.createDirectories(layout.root().resolve("repositories"));
        for (Path entry : List.of(snapshot, unrelated)) {
            Files.setLastModifiedTime(entry, FileTime.from(clock.instant()));
        }

        new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofMinutes(59)), personCopies())
                .cleanEndedAttempts();
        assertThat(snapshot).exists();

        new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofHours(1)), personCopies())
                .cleanEndedAttempts();
        assertThat(snapshot).doesNotExist();
        assertThat(unrelated).as("only the Git spool is swept").exists();
    }

    @Test
    void shouldVerifyDecodableLinesWhenAnotherLineIsNotUtf8() {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock, personCopies());
        var job = job();
        byte[] bytes = concat(
                "caf".getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xe9, '\n'},
                "clean line\n".getBytes(StandardCharsets.UTF_8));
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var prepared = PreparedJobInputsFixtures.prepare(
                files, job, PreparedJobInputsFixtures.filesOnly(Map.of("inputs/source", bytes)));
        try {
            assertThat(files.containsUtf8AtLines(job, "inputs/source", sha, "clean line", 2, 2))
                    .contains(true);
            assertThat(files.containsUtf8AtLines(job, "inputs/source", sha, "caf\uFFFD", 1, 1))
                    .as("a replacement character is not the byte that was there")
                    .contains(false);
            assertThat(read(files, job, "inputs/source", sha))
                    .as("the digest is over the raw bytes, however they decode")
                    .isPresent();
        } finally {
            prepared.close();
        }
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    @Test
    void shouldRemoveExpiredEndedAttemptsOnAWorkerWithoutServerScheduling() {
        var layout = new FabricLayout(root.toString());
        var preparing = new JobEvidenceFiles(layout, jobs, clock, personCopies());
        byte[] bytes = "evidence".getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var ended = endedAttempt(preparing, bytes);
        var running = job();
        running.setStatus(AgentJobStatus.RUNNING);
        when(jobs.findByIdAndWorkspaceId(running.getId(), 1L)).thenReturn(Optional.of(running));
        var later = new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofHours(1)), personCopies());
        try (var active = PreparedJobInputsFixtures.prepare(
                preparing, running, PreparedJobInputsFixtures.filesOnly(Map.of("context/quote", bytes)))) {
            // The runner publishes no ApplicationReadyEvent, so only the periodic sweep can remove a folder.
            maintenance(later, personCopies()).run(context -> {
                assertThat(context).hasNotFailed();
                await().atMost(Duration.ofSeconds(3))
                        .untilAsserted(() -> assertThat(read(preparing, ended, "context/quote", sha))
                                .isEmpty());
                assertThat(read(preparing, running, "context/quote", sha)).contains(bytes);
                assertThat(PreparedJobInputsFixtures.files(active)).containsKey("context/quote");
            });
        }
    }

    @Test
    void shouldScheduleEachMaintenanceTaskOnceWithTheServerRoleAndCancelItOnClose() {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock, personCopies());
        var tasks = new ArrayList<ScheduledTask>();
        maintenance(files, personCopies())
                .withPropertyValues("hephaestus.runtime.server.enabled=true")
                .run(context -> context.getBeansOfType(ScheduledTaskHolder.class)
                        .values()
                        .forEach(holder -> tasks.addAll(holder.getScheduledTasks())));
        assertThat(tasks)
                .hasSize(2)
                .allSatisfy(task -> assertThat(task.nextExecution()).isNull());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "hephaestus.runtime.worker.enabled=false",
                "spring.profiles.active=specs",
                "spring.profiles.active=cds-training"
            })
    void shouldNotMaintainEvidenceWithoutTheWorkerRoleOrInBuildOnlyProfiles(String setting) {
        var layout = new FabricLayout(root.toString());
        byte[] bytes = "evidence".getBytes(StandardCharsets.UTF_8);
        var preparing = new JobEvidenceFiles(layout, jobs, clock, personCopies());
        var ended = endedAttempt(preparing, bytes);
        var personCopies = personCopies();
        var later = new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofHours(1)), personCopies);
        maintenance(later, personCopies).withPropertyValues(setting).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                            .flatMap(holder -> holder.getScheduledTasks().stream()))
                    .isEmpty();
        });
        assertThat(read(preparing, ended, "context/quote", ProvenanceDigest.sha256Hex(bytes)))
                .contains(bytes);
        verify(personCopies, never()).removeLocalRequests();
    }

    private static ApplicationContextRunner maintenance(
            JobEvidenceFiles files, EvidenceFolderPersonDataCatalog personCopies) {
        return new ApplicationContextRunner()
                .withUserConfiguration(JobEvidenceMaintenanceConfiguration.class, ServerSchedulingConfig.class)
                .withBean(JobEvidenceFiles.class, () -> files)
                .withBean(EvidenceFolderPersonDataCatalog.class, () -> personCopies)
                .withPropertyValues(
                        "hephaestus.runtime.worker.enabled=true", "hephaestus.runtime.server.enabled=false");
    }

    private AgentJob endedAttempt(JobEvidenceFiles files, byte[] bytes) {
        var ended = job();
        ended.setStatus(AgentJobStatus.FAILED);
        when(jobs.findByIdAndWorkspaceId(ended.getId(), 1L)).thenReturn(Optional.of(ended));
        PreparedJobInputsFixtures.prepare(
                        files, ended, PreparedJobInputsFixtures.filesOnly(Map.of("context/quote", bytes)))
                .close();
        assertThat(read(files, ended, "context/quote", ProvenanceDigest.sha256Hex(bytes)))
                .contains(bytes);
        return ended;
    }

    @Test
    void shouldLeaveAnAttemptWhoseJobLeaseIsHeldUntilTheHolderReleasesIt() {
        var layout = new FabricLayout(root.toString());
        byte[] bytes = "evidence".getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var files = new JobEvidenceFiles(layout, jobs, clock, personCopies());
        var ended = endedAttempt(files, bytes);
        var later = new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofHours(1)), personCopies());
        var holder =
                EvidenceFolderLease.tryAcquire(layout.root(), 1L, ended.getId()).orElseThrow();
        try (holder) {
            later.cleanAfterRestart();
            later.cleanEndedAttempts();
            assertThat(read(files, ended, "context/quote", sha)).contains(bytes);
            verify(jobs, never()).discardRetiredArtifactInventory(ended.getId(), 1L, 0, "worker");
        }
        later.cleanEndedAttempts();
        assertThat(read(files, ended, "context/quote", sha)).isEmpty();
        verify(jobs).discardRetiredArtifactInventory(ended.getId(), 1L, 0, "worker");
    }

    @Test
    void shouldLeaveAnAttemptToTheSweepThatOwnsItWhenAnotherSweepOverlaps() throws Exception {
        var layout = new FabricLayout(root.toString());
        byte[] bytes = "evidence".getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var files = new JobEvidenceFiles(layout, jobs, clock, personCopies());
        var ended = endedAttempt(files, bytes);
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        // The startup sweep stops in its ownership query, which it makes while it holds the job's lease.
        when(jobs.findByIdAndWorkspaceId(ended.getId(), 1L)).thenAnswer(query -> {
            if (first.getAndSet(false)) {
                entered.countDown();
                assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return Optional.of(ended);
        });
        var periodic = new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofHours(1)), personCopies());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var startup = pool.submit(() -> {
                files.cleanAfterRestart();
                return null;
            });
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            periodic.cleanEndedAttempts();
            assertThat(read(files, ended, "context/quote", sha)).contains(bytes);
            verify(jobs, never()).discardRetiredArtifactInventory(ended.getId(), 1L, 0, "worker");
            proceed.countDown();
            startup.get(10, TimeUnit.SECONDS);
        }
        assertThat(read(files, ended, "context/quote", sha)).isEmpty();
        periodic.cleanEndedAttempts();
        verify(jobs, times(1)).discardRetiredArtifactInventory(ended.getId(), 1L, 0, "worker");
    }

    @Test
    void shouldRemoveAnAdmittedAttemptWhileItsRuntimeStillHoldsTheJobLease() {
        var layout = new FabricLayout(root.toString());
        var files = new JobEvidenceFiles(layout, jobs, clock, personCopies());
        var job = job();
        job.setStatus(AgentJobStatus.RUNNING);
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenReturn(Optional.of(job));
        byte[] bytes = "verified quote".getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var identity = new AdmissionIdentity(job.getId(), 1L, 0, "worker");
        var later = new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofHours(1)), personCopies());
        var prepared = PreparedJobInputsFixtures.prepare(
                files, job, PreparedJobInputsFixtures.filesOnly(Map.of("context/quote", bytes)));
        // The runtime's capture lease, held until its inputs are released after the composition.
        var runtime =
                EvidenceFolderLease.tryAcquire(layout.root(), 1L, job.getId()).orElseThrow();
        try (runtime) {
            job.setMetadata(new JsonMapper()
                    .createObjectNode()
                    .put(ObservationAdmissionService.DIGEST_METADATA_KEY, "verified"));
            // Before the capture shares its lease, admission cannot remove through it.
            files.discardAdmittedAttempt(identity);
            assertThat(read(files, job, "context/quote", sha)).contains(bytes);
            runtime.shareWithRemovals();
            // A sweep or an erasure needs the lease exclusively and does not borrow a shared one.
            later.cleanEndedAttempts();
            assertThat(EvidenceFolderLease.tryAcquire(layout.root(), 1L, job.getId()))
                    .isEmpty();
            assertThat(read(files, job, "context/quote", sha)).contains(bytes);
            files.discardAdmittedAttempt(identity);
            assertThat(read(files, job, "context/quote", sha)).isEmpty();
            assertThat(EvidenceFolderLease.tryAcquire(layout.root(), 1L, job.getId()))
                    .isEmpty();
            prepared.close();
        }
        later.cleanEndedAttempts();
        files.discardAdmittedAttempt(identity);
        assertThat(read(files, job, "context/quote", sha)).isEmpty();
        verify(jobs, never()).discardRetiredArtifactInventory(job.getId(), 1L, 0, "worker");
    }

    @Test
    void shouldRemoveAnAdmittedAttemptOnceWhenAdmissionOverlapsItsRetirement() throws Exception {
        var layout = new FabricLayout(root.toString());
        var files = new JobEvidenceFiles(layout, jobs, clock, personCopies());
        var job = job();
        job.setStatus(AgentJobStatus.RUNNING);
        byte[] bytes = "verified quote".getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var prepared = PreparedJobInputsFixtures.prepare(
                files, job, PreparedJobInputsFixtures.filesOnly(Map.of("context/quote", bytes)));
        job.setMetadata(
                new JsonMapper().createObjectNode().put(ObservationAdmissionService.DIGEST_METADATA_KEY, "verified"));
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        // Retirement stops in its ownership query, which it makes under the runtime lease's monitor.
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenAnswer(query -> {
            if (first.getAndSet(false)) {
                entered.countDown();
                assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return Optional.of(job);
        });
        var runtime =
                EvidenceFolderLease.tryAcquire(layout.root(), 1L, job.getId()).orElseThrow();
        try (runtime) {
            runtime.shareWithRemovals();
            var retiring = new Thread(prepared::close);
            retiring.start();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var identity = new AdmissionIdentity(job.getId(), 1L, 0, "worker");
            var admitting = new Thread(() -> files.discardAdmittedAttempt(identity));
            admitting.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> admitting.getState() == Thread.State.BLOCKED);
            assertThat(read(files, job, "context/quote", sha)).contains(bytes);
            proceed.countDown();
            retiring.join(10_000);
            admitting.join(10_000);
            assertThat(retiring.isAlive()).isFalse();
            assertThat(admitting.isAlive()).isFalse();
        }
        assertThat(read(files, job, "context/quote", sha)).isEmpty();
        assertThat(layout.jobsRoot().resolve("1").resolve(job.getId().toString()))
                .isEmptyDirectory();
    }

    private static final String IMAGE = "agent@sha256:pinned";

    /**
     * A manifest as a sandbox could forge it: names, paths, arguments and error text in every field, unknown keys,
     * unowned enums, and numbers that are fractional, negative or past any bound.
     */
    private static final String FORGED_MANIFEST = """
            {"schemaVersion":1,"budgetBytes":1,"model":"Jane Roe","sessions":[
              {"file":"people/jane-roe.jsonl","bytes":120,"redacted":"Jane Roe","partialLineDropped":false,
               "omitted":"Jane Roe","error":"Jane Roe <jane@example.com>",
               "summary":{"phase":"practice","practiceRevisionId":12,"practiceSlug":"jane-roe","entries":6,
                 "assistantCalls":1e30,"toolErrors":-1,"compactions":1.5,
                 "usage":{"input":100,"output":"Jane Roe","cacheRead":9007199254740993,"cacheWrite":5,"note":"Jane Roe"},
                 "stopReasons":{"toolUse":1,"Jane Roe":2},
                 "toolCalls":{"read":2,"jane_roe_tool":1,"other":1},
                 "modelFailures":{"HTTP_ERROR":1,"Jane Roe":3,"UNKNOWN":-1},
                 "lastModelFailureAt":1760000000000,
                 "finalModelFailure":{"kind":"HTTP_ERROR","phase":"Jane Roe","status":503,"at":1.5,
                   "error":"Jane Roe <jane@example.com>"},
                 "arguments":{"path":"/workspace/inputs/people/7/person.json"}}},
              {"summary":{"phase":"Jane Roe","finalModelFailure":{"kind":"ABORTED","phase":"request","status":503}}},
              {"summary":{"finalModelFailure":{"kind":"Jane Roe","status":503}}},
              {"summary":{"finalModelFailure":{"kind":"FINISH_REASON_ERROR","phase":"response_body","at":1760000000000,
                "status":503,"error":"Jane Roe"}}}
            ]}
            """;

    /** An upload as the gateway runner sends it: the result, a native session copy and its manifest. */
    private static final Map<String, byte[]> TRACED_UPLOAD = Map.of(
            "result.json", "{\"observations\":[]}".getBytes(StandardCharsets.UTF_8),
            "traces/0001.jsonl", "{\"type\":\"session\",\"cwd\":\"Jane Roe\"}\n".getBytes(StandardCharsets.UTF_8),
            "traces/manifest.json", FORGED_MANIFEST.getBytes(StandardCharsets.UTF_8));

    private static Path traceOf(FabricLayout layout, AgentJob job, int attempt) {
        return layout.jobsRoot()
                .resolve("1")
                .resolve(job.getId().toString())
                .resolve(attempt + "-" + ProvenanceDigest.sha256Hex("worker".getBytes(StandardCharsets.UTF_8))
                        + ".trace");
    }

    /** A job as the executor launches it: RUNNING as attempt 0 of this worker; later changes show in both reads. */
    private AgentJob launched() {
        var job = job();
        job.setStatus(AgentJobStatus.RUNNING);
        when(jobs.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenReturn(Optional.of(job));
        return job;
    }

    /** The lease a running attempt holds once its capture published the folder. */
    private static EvidenceFolderLease runtimeLease(FabricLayout layout, AgentJob job) {
        var lease =
                EvidenceFolderLease.tryAcquire(layout.root(), 1L, job.getId()).orElseThrow();
        lease.shareWithRemovals();
        return lease;
    }

    @ParameterizedTest
    @EnumSource(EvidenceFolderPersonDataCatalog.TraceCustody.class)
    void shouldKeepTheNativeTranscriptOnlyWhenTheReceiptIndexesEveryCopiedDependency(
            EvidenceFolderPersonDataCatalog.TraceCustody custody) throws Exception {
        var layout = new FabricLayout(root.toString());
        var copies = personCopies();
        var job = launched();
        when(copies.traceCustody(job.getId(), 1L, 0)).thenReturn(custody);
        var files = new JobEvidenceFiles(layout, jobs, clock, copies);
        var upload = files.bind(job.getId(), 0, IMAGE);
        // Cancelled while it ran: the upload that still arrived is the attempt's.
        job.setStatus(AgentJobStatus.CANCELLED);

        var lease = runtimeLease(layout, job);
        try (lease) {
            upload.accept(TRACED_UPLOAD);
        }

        Path trace = traceOf(layout, job, 0);
        switch (custody) {
            case INDEXED -> {
                assertThat(trace.resolve("0001.jsonl")).hasContent("{\"type\":\"session\",\"cwd\":\"Jane Roe\"}\n");
                var record = new JsonMapper().readTree(Files.readString(trace.resolve("record.json")));
                assertThat(record.path("retention").asString()).isEqualTo("NATIVE_TRANSCRIPT");
                assertThat(record.has("contentUnavailable")).isFalse();
                assertThat(record.path("attempt").asInt()).isZero();
                assertThat(record.path("image").asString()).isEqualTo(IMAGE);
                assertThat(Instant.parse(record.path("expiresAt").asString()))
                        .isEqualTo(clock.instant().plus(Duration.ofHours(24)));
            }
            case UNINDEXED_DEPENDENCY -> {
                try (var kept = Files.list(trace)) {
                    assertThat(kept.map(path -> path.getFileName().toString()))
                            .as("neither a session's words nor the sandbox's own manifest")
                            .containsExactly("record.json");
                }
                String text = Files.readString(trace.resolve("record.json"));
                for (String forged : List.of("Jane", "jane", "/workspace", "people/", "1e30", "note", "arguments")) {
                    assertThat(text).doesNotContain(forged);
                }
                var record = new JsonMapper().readTree(text);
                assertThat(record.path("retention").asString()).isEqualTo("METADATA_ONLY");
                assertThat(record.path("contentUnavailable").asString()).isEqualTo("OWNERSHIP_INCOMPLETE");
                assertThat(record.path("sessions"))
                        .as("rebuilt from owned enums, flags and bounded integers only")
                        .isEqualTo(new JsonMapper().readTree("""
                                [{"copied":true,"bytes":120,"partialLineDropped":false,
                                  "summary":{"phase":"practice","practiceRevisionId":12,"entries":6,
                                    "usage":{"input":100,"cacheWrite":5},
                                    "stopReasons":{"toolUse":1},
                                    "toolCalls":{"read":2,"other":1},
                                    "modelFailures":{"HTTP_ERROR":1},
                                    "lastModelFailureAt":1760000000000,
                                    "finalModelFailure":{"kind":"HTTP_ERROR","status":503}}},
                                 {"copied":false,"summary":{"usage":{},"stopReasons":{},"toolCalls":{},
                                    "modelFailures":{},"finalModelFailure":{"kind":"ABORTED","phase":"request"}}},
                                 {"copied":false,"summary":{"usage":{},"stopReasons":{},"toolCalls":{},
                                    "modelFailures":{}}},
                                 {"copied":false,"summary":{"usage":{},"stopReasons":{},"toolCalls":{},"modelFailures":{},
                                   "finalModelFailure":{"kind":"FINISH_REASON_ERROR","phase":"response_body","at":1760000000000}}}]
                                """));
            }
            case NOT_HELD ->
                assertThat(trace.getParent())
                        .as("an erased or requested receipt gets nothing, not even a record")
                        .doesNotExist();
        }
        assertThat(job.getStatus()).isEqualTo(AgentJobStatus.CANCELLED);
    }

    @Test
    void shouldEnforceTranscriptByteAndMemberLimitsEvenWhenTheSandboxClaimsOtherwise() throws Exception {
        var layout = new FabricLayout(root.toString());
        var copies = personCopies();
        var job = launched();
        when(copies.traceCustody(job.getId(), 1L, 0)).thenReturn(EvidenceFolderPersonDataCatalog.TraceCustody.INDEXED);
        var files = new JobEvidenceFiles(layout, jobs, clock, copies);
        var upload = files.bind(job.getId(), 0, IMAGE);
        var forged = new HashMap<>(TRACED_UPLOAD);
        forged.put("traces/0001.jsonl", new byte[2 * 1024 * 1024 + 1]);
        var lease = runtimeLease(layout, job);
        try (lease) {
            upload.accept(forged);
        }
        Path trace = traceOf(layout, job, 0);
        assertThat(trace.resolve("0001.jsonl")).doesNotExist();
        var record = new JsonMapper().readTree(Files.readString(trace.resolve("record.json")));
        assertThat(record.path("retention").asString()).isEqualTo("METADATA_ONLY");
        assertThat(record.path("contentUnavailable").asString()).isEqualTo("TRACE_OUTPUT_INVALID");
    }

    @Test
    void shouldNotAttributeADelayedUploadOfAnEarlierAttemptToItsSuccessor() throws Exception {
        var layout = new FabricLayout(root.toString());
        var copies = personCopies();
        var job = launched();
        when(copies.traceCustody(eq(job.getId()), eq(1L), anyInt()))
                .thenReturn(EvidenceFolderPersonDataCatalog.TraceCustody.INDEXED);
        var files = new JobEvidenceFiles(layout, jobs, clock, copies);
        var earlier = files.bind(job.getId(), 0, IMAGE);
        // Requeued: the successor attempt runs on this worker and holds the job's lease.
        job.setRetryCount(1);
        var successor = files.bind(job.getId(), 1, IMAGE);
        var notLaunched = files.bind(job.getId(), 5, IMAGE);

        var lease = runtimeLease(layout, job);
        try (lease) {
            earlier.accept(TRACED_UPLOAD);
            notLaunched.accept(TRACED_UPLOAD);
            assertThat(layout.jobsRoot())
                    .as("the late upload lands in neither attempt's folder")
                    .doesNotExist();

            successor.accept(TRACED_UPLOAD);
        }
        assertThat(traceOf(layout, job, 1).resolve("0001.jsonl")).exists();
        assertThat(traceOf(layout, job, 0)).doesNotExist();
    }

    @Test
    void shouldWriteNoTranscriptOutsideTheRuntimeLeaseAndNeverFailTheUpload() throws Exception {
        var layout = new FabricLayout(root.toString());
        var copies = personCopies();
        var job = launched();
        when(copies.traceCustody(job.getId(), 1L, 0)).thenReturn(EvidenceFolderPersonDataCatalog.TraceCustody.INDEXED);
        var files = new JobEvidenceFiles(layout, jobs, clock, copies);
        var upload = files.bind(job.getId(), 0, IMAGE);

        upload.accept(TRACED_UPLOAD);
        assertThat(traceOf(layout, job, 0))
                .as("the runtime no longer holds the attempt")
                .doesNotExist();

        when(copies.traceCustody(job.getId(), 1L, 0)).thenThrow(new IllegalStateException("receipts unavailable"));
        var lease = runtimeLease(layout, job);
        try (lease) {
            upload.accept(TRACED_UPLOAD);
        }
        assertThat(traceOf(layout, job, 0)).doesNotExist();
    }

    @Test
    void shouldKeepATranscriptThroughARestartForADayWhateverTheJobBecame() throws Exception {
        var layout = new FabricLayout(root.toString());
        var copies = personCopies();
        var job = launched();
        when(copies.traceCustody(job.getId(), 1L, 0)).thenReturn(EvidenceFolderPersonDataCatalog.TraceCustody.INDEXED);
        var lease = runtimeLease(layout, job);
        try (lease) {
            new JobEvidenceFiles(layout, jobs, clock, copies)
                    .bind(job.getId(), 0, IMAGE)
                    .accept(TRACED_UPLOAD);
        }
        job.setStatus(AgentJobStatus.COMPLETED);
        Path trace = traceOf(layout, job, 0);

        new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofHours(23)), copies).cleanAfterRestart();
        assertThat(trace.resolve("0001.jsonl")).exists();

        new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofHours(24)), copies).cleanEndedAttempts();
        assertThat(trace).doesNotExist();
    }

    private static AgentJob job() {
        var workspace = new Workspace();
        workspace.setId(1L);
        var job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setWorkspace(workspace);
        job.setWorkerId("worker");
        return job;
    }
}
