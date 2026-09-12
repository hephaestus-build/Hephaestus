package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobEvidenceFilesTest extends BaseUnitTest {
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
        var files =
                new JobEvidenceFiles(new FabricLayout(root.resolve("evidence").toString()), jobs, clock);
        var inputs = new PreparedJobInputs(
                java.util.Map.of(),
                java.util.Map.of("repo/.git/HEAD", head),
                java.util.List.of(new EvidenceDirectory("repo/", checkout)),
                java.util.List.of(),
                null,
                null);
        try (var captured = files.prepare(job(), inputs)) {
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
        var files =
                new JobEvidenceFiles(new FabricLayout(root.resolve("evidence").toString()), jobs, clock);
        var inputs = new PreparedJobInputs(
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.List.of(new EvidenceDirectory("repo/", checkout)),
                java.util.List.of(),
                null,
                null);
        assertThatThrownBy(() -> files.prepare(job(), inputs)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRetainUnadmittedEvidenceForOneHourAfterTheOwningHandleCloses() throws Exception {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock);
        var job = job();
        Path source = root.resolve("source.txt");
        String content = "repeated quote\nwrong line\nrepeated quote\n";
        Files.writeString(source, content);
        String sha = ProvenanceDigest.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        var raw = new PreparedJobInputs(
                Map.of(), Map.of("inputs/context/source.txt", source), List.of(), List.of(), null, null);
        try (var prepared = files.prepare(job, raw)) {
            Files.writeString(source, "upstream changed");
            assertThat(read(files, job, "inputs/context/source.txt", sha))
                    .contains(content.getBytes(StandardCharsets.UTF_8));
            assertThat(files.containsUtf8AtLines(job, "inputs/context/source.txt", sha, "repeated quote", 2, 2))
                    .contains(false);
            assertThat(files.containsUtf8AtLines(job, "inputs/context/source.txt", sha, "repeated quote", 3, 3))
                    .contains(true);
            assertThatThrownBy(() -> files.prepare(job, raw)).isInstanceOf(IllegalStateException.class);
            job.setRetryCount(1);
            assertThat(read(files, job, "inputs/context/source.txt", sha)).isEmpty();
            job.setRetryCount(0);
            assertThat(prepared.filesOnDisk().get("inputs/context/source.txt")).hasContent(content);
        }
        assertThat(read(files, job, "inputs/context/source.txt", sha)).isPresent();
        new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.offset(clock, Duration.ofHours(1)))
                .cleanEndedAttempts();
        assertThat(read(files, job, "inputs/context/source.txt", sha)).isEmpty();
    }

    @Test
    void shouldRejectEscapingPathsAndSymbolicLinksDuringPreparation() throws Exception {
        var layout = new FabricLayout(root.toString());
        var files = new JobEvidenceFiles(layout, jobs, clock);
        assertThatThrownBy(() -> files.prepare(job(), PreparedJobInputs.filesOnly(Map.of("../escape", new byte[0]))))
                .isInstanceOf(IllegalStateException.class);
        Path source = root.resolve("source");
        Files.writeString(source, "private");
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, source);
        var raw = new PreparedJobInputs(Map.of(), Map.of("inputs/source", link), List.of(), List.of(), null, null);
        assertThatThrownBy(() -> files.prepare(job(), raw)).isInstanceOf(IllegalStateException.class);
        try (var paths = Files.walk(layout.jobsRoot())) {
            assertThat(paths.filter(path -> path.getFileName().toString().contains(".preparing-")))
                    .as("a failed preparation leaves no staging directory behind")
                    .isEmpty();
        }
    }

    @Test
    void shouldVerifyDigestAfterMatchingAcrossBuffersAndRejectChangedBytes() throws Exception {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock);
        var job = job();
        String quote = "é😀\n" + "quoted line\n".repeat(1000);
        byte[] bytes = ("x".repeat(8191) + quote).getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        try (var prepared = files.prepare(job, PreparedJobInputs.filesOnly(Map.of("inputs/source", bytes)))) {
            assertThat(files.containsUtf8AtLines(job, "inputs/source", sha, quote, 1, 1001))
                    .contains(true);
            assertThatThrownBy(() -> read(files, job, "inputs/source", "a".repeat(64)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(prepared.files()).containsKey("inputs/source");
        }
    }

    @Test
    void shouldPreserveAnotherActiveWorkersFolderAndCleanLostAttemptsAfterGracePeriod() {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock);
        var job = job();
        job.setStatus(AgentJobStatus.RUNNING);
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenReturn(Optional.of(job));
        byte[] bytes = "source".getBytes(StandardCharsets.UTF_8);
        String sha = ProvenanceDigest.sha256Hex(bytes);
        try (var prepared = files.prepare(job, PreparedJobInputs.filesOnly(Map.of("inputs/source", bytes)))) {
            files.cleanEndedAttempts();
            var later = new JobEvidenceFiles(
                    new FabricLayout(root.toString()), jobs, Clock.offset(clock, Duration.ofHours(2)));
            later.cleanEndedAttempts();
            assertThat(read(files, job, "inputs/source", sha)).contains(bytes);
            job.setStatus(AgentJobStatus.CANCELLED);
            later.cleanEndedAttempts();
            assertThat(read(files, job, "inputs/source", sha)).contains(bytes);
            new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.offset(clock, Duration.ofHours(3)))
                    .cleanEndedAttempts();
            assertThat(read(files, job, "inputs/source", sha)).isEmpty();
            assertThat(prepared.files()).containsKey("inputs/source");
        }
    }

    @Test
    void shouldDeleteAdmittedFinalEvidenceImmediatelyWithoutDeletingAReplacementHandle() {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock);
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
        var first = files.prepare(job, PreparedJobInputs.filesOnly(Map.of("inputs/source", bytes)));
        first.close();
        assertThat(read(files, job, "inputs/source", sha)).isEmpty();
        try (var replacement = files.prepare(job, PreparedJobInputs.filesOnly(Map.of("inputs/source", bytes)))) {
            first.close();
            assertThat(read(files, job, "inputs/source", sha)).contains(bytes);
            assertThat(replacement.files()).containsKey("inputs/source");
        }
    }

    @Test
    void shouldCleanOrphanedPreparationAfterTheGracePeriod() throws Exception {
        var layout = new FabricLayout(root.toString());
        var files = new JobEvidenceFiles(layout, jobs, clock);
        var job = job();
        Path parent = layout.jobsRoot().resolve("1").resolve(job.getId().toString());
        Files.createDirectories(parent);
        String identity = "0-" + ProvenanceDigest.sha256Hex("worker".getBytes(StandardCharsets.UTF_8));
        Path staging = Files.createTempDirectory(parent, "." + identity + ".preparing-");
        Files.writeString(staging.resolve("partial"), "not published");
        files.cleanEndedAttempts();
        assertThat(staging).exists();
        new JobEvidenceFiles(layout, jobs, Clock.offset(clock, Duration.ofHours(1))).cleanEndedAttempts();
        assertThat(staging).doesNotExist();
    }

    @Test
    void shouldVerifyDecodableLinesWhenAnotherLineIsNotUtf8() {
        var files = new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, clock);
        var job = job();
        byte[] bytes = concat(
                "caf".getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xe9, '\n'},
                "clean line\n".getBytes(StandardCharsets.UTF_8));
        String sha = ProvenanceDigest.sha256Hex(bytes);
        try (var prepared = files.prepare(job, PreparedJobInputs.filesOnly(Map.of("inputs/source", bytes)))) {
            assertThat(files.containsUtf8AtLines(job, "inputs/source", sha, "clean line", 2, 2))
                    .contains(true);
            assertThat(files.containsUtf8AtLines(job, "inputs/source", sha, "caf\uFFFD", 1, 1))
                    .as("a replacement character is not the byte that was there")
                    .contains(false);
            assertThat(read(files, job, "inputs/source", sha))
                    .as("the digest is over the raw bytes, however they decode")
                    .isPresent();
            assertThat(prepared.files()).containsKey("inputs/source");
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
