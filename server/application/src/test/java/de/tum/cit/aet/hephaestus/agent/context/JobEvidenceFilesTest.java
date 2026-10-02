package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobEvidenceFilesTest extends BaseUnitTest {
    private static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolderPersonDataCatalog personCopies() {
        AutoCloseable released = () -> {};
        return org.mockito.Mockito.mock(
                de.tum.cit.aet.hephaestus.agent.context.EvidenceFolderPersonDataCatalog.class,
                invocation -> invocation.getMethod().getName().equals("finishCapture")
                        ? released
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
    }

    @TempDir
    Path root;

    private final AgentJobRepository jobs = mock(AgentJobRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);

    private static java.util.Optional<byte[]> read(JobEvidenceFiles files, AgentJob job, String path, String sha) {
        return files.inspect(job, path, sha, reader -> {
            var output = new java.io.StringWriter();
            reader.transferTo(output);
            return output.toString().getBytes(StandardCharsets.UTF_8);
        });
    }

    @Test
    void refusesAnOversizedDiskFolderBeforeCopyingAndReleasesRenderScratch() throws Exception {
        Path oversized = root.resolve("oversized");
        try (var output = java.nio.channels.FileChannel.open(
                oversized, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            output.position(de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions.WORKSPACE_BYTE_BUDGET);
            output.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        var inputs = new PreparedEvidence(
                Map.of(), Map.of("context/large.jsonl", oversized), List.of(() -> released.set(true)), null);
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock, personCopies());
        assertThatThrownBy(() -> files.prepare(job(), inputs, null))
                .isInstanceOf(de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException.class)
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
        var identity = new de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService.AdmissionIdentity(
                job.getId(), 1L, 0, "worker");
        try (var prepared = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(
                files,
                job,
                de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.filesOnly(
                        Map.of("context/quote", bytes)))) {
            assertThat(de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.files(prepared))
                    .containsKey("context/quote");
            files.discardAdmittedAttempt(identity);
            assertThat(read(files, job, "context/quote", sha)).contains(bytes);
            job.setMetadata(new tools.jackson.databind.json.JsonMapper()
                    .createObjectNode()
                    .put(
                            de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService.DIGEST_METADATA_KEY,
                            "verified"));
            files.discardAdmittedAttempt(
                    new de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService.AdmissionIdentity(
                            job.getId(), 1L, 1, "worker"));
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
        try (var active = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(
                        files,
                        running,
                        de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.filesOnly(
                                Map.of("context/quote", bytes)));
                var ended = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(
                        files,
                        finished,
                        de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.filesOnly(
                                Map.of("context/quote", bytes)));
                var orphan = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(
                        files,
                        unknown,
                        de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.filesOnly(
                                Map.of("context/quote", bytes)))) {
            files.cleanAfterRestart();
            assertThat(read(files, running, "context/quote", sha)).contains(bytes);
            assertThat(read(files, finished, "context/quote", sha)).isEmpty();
            assertThat(read(files, unknown, "context/quote", sha)).isEmpty();
            assertThat(de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.files(active))
                    .containsKey("context/quote");
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
                script,
                java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        var files = new JobEvidenceFiles(
                new FabricLayout(root.resolve("evidence").toString()), jobs, clock, personCopies());
        var inputs = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.inputs(
                new PreparedEvidence(
                        Map.of(),
                        Map.of("repo/.git/HEAD", head),
                        List.of(),
                        null,
                        List.of(new EvidenceDirectory("repo/", checkout))),
                null);
        try (var captured =
                de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(files, job(), inputs)) {
            assertThat(captured.filesOnDisk()).hasSize(1);
            assertThat(captured.directories()).hasSize(1);
            Path canonical = captured.directories().getFirst().source();
            assertThat(captured.filesOnDisk().get("repo/.git/HEAD")).isEqualTo(canonical.resolve(".git/HEAD"));
            assertThat(Files.readString(canonical.resolve("run.sh"))).isEqualTo("echo source\n");
            assertThat(Files.getPosixFilePermissions(canonical.resolve("run.sh")))
                    .contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
                    .doesNotContain(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
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
        var inputs = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.inputs(
                new PreparedEvidence(
                        Map.of(), Map.of(), List.of(), null, List.of(new EvidenceDirectory("repo/", checkout))),
                null);
        assertThatThrownBy(() ->
                        de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(files, job(), inputs))
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
        var raw = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.inputs(
                new PreparedEvidence(Map.of(), Map.of("context/source.txt", source), List.of(), null), null);
        try (var prepared =
                de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(files, job, raw)) {
            Files.writeString(source, "upstream changed");
            assertThat(read(files, job, "context/source.txt", sha)).contains(content.getBytes(StandardCharsets.UTF_8));
            assertThat(files.containsUtf8AtLines(job, "context/source.txt", sha, "repeated quote", 2, 2))
                    .contains(false);
            assertThat(files.containsUtf8AtLines(job, "context/source.txt", sha, "repeated quote", 3, 3))
                    .contains(true);
            assertThatThrownBy(() ->
                            de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(files, job, raw))
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
        assertThatThrownBy(() -> de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(
                        files,
                        job(),
                        de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.filesOnly(
                                Map.of("../escape", new byte[0]))))
                .isInstanceOf(IllegalStateException.class);
        Path source = root.resolve("source");
        Files.writeString(source, "private");
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, source);
        var raw = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.inputs(
                new PreparedEvidence(Map.of(), Map.of("inputs/source", link), List.of(), null), null);
        assertThatThrownBy(() ->
                        de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(files, job(), raw))
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
        var prepared = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(
                files,
                job,
                de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.filesOnly(
                        Map.of("inputs/source", bytes)));
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
        var prepared = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(
                files,
                job,
                de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.filesOnly(
                        Map.of("inputs/source", bytes)));
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
            org.mockito.Mockito.verify(jobs)
                    .discardRetiredArtifactInventory(
                            job.getId(), 1L, job.getRetryCount(), java.util.Objects.requireNonNull(job.getWorkerId()));
        } finally {
            prepared.close();
        }
    }

    @Test
    void shouldDeleteAdmittedFinalEvidenceImmediatelyWithoutDeletingAReplacementHandle() {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock, personCopies());
        var job = job();
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setMetadata(new tools.jackson.databind.json.JsonMapper()
                .createObjectNode()
                .put(
                        de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService.DIGEST_METADATA_KEY,
                        "admitted"));
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenReturn(Optional.of(job));
        byte[] bytes = "source".getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        var first = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(
                files,
                job,
                de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.filesOnly(
                        Map.of("inputs/source", bytes)));
        first.close();
        assertThat(read(files, job, "inputs/source", sha)).isEmpty();
        var replacement = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(
                files,
                job,
                de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.filesOnly(
                        Map.of("inputs/source", bytes)));
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
            Files.setLastModifiedTime(staging, java.nio.file.attribute.FileTime.from(clock.instant()));
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
            Files.setLastModifiedTime(entry, java.nio.file.attribute.FileTime.from(clock.instant()));
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
        var prepared = de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.prepare(
                files,
                job,
                de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures.filesOnly(
                        Map.of("inputs/source", bytes)));
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
        var out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
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
