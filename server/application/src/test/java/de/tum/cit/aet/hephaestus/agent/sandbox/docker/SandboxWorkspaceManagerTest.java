package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class SandboxWorkspaceManagerTest {
    private final SandboxWorkspaceManager manager = new SandboxWorkspaceManager();

    @TempDir
    Path temporary;

    @Test
    void shouldPreserveMemoryAndDiskInputsInOneArchive() throws Exception {
        var source = Files.writeString(temporary.resolve("App.java"), "class App {}");
        Path archive = manager.createInputTar(
                Map.of("inputs/diff.patch", "diff".getBytes()), Map.of("inputs/repository/App.java", source));
        try {
            var captured = new HashMap<String, byte[]>();
            try (var tar = new TarArchiveInputStream(Files.newInputStream(archive))) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    if (!entry.isDirectory()) captured.put(entry.getName(), tar.readAllBytes());
                }
            }
            assertThat(captured).containsOnlyKeys("inputs/diff.patch", "inputs/repository/App.java");
            assertThat(captured.get("inputs/diff.patch")).isEqualTo("diff".getBytes());
            assertThat(captured.get("inputs/repository/App.java")).isEqualTo(Files.readAllBytes(source));
        } finally {
            Files.delete(archive);
        }
    }

    @Test
    void shouldKeepWritableRegionsWritableAndEvidenceReadOnly() throws Exception {
        Path archive = manager.createInputTar(
                Map.of(
                        "inputs/diff.patch",
                        new byte[0],
                        ".pi/settings.json",
                        new byte[0],
                        ".sessions/thread.jsonl",
                        new byte[0],
                        "out/.gitkeep",
                        new byte[0],
                        "work/analysis/.gitkeep",
                        new byte[0]),
                Map.of());
        try (var tar = new TarArchiveInputStream(Files.newInputStream(archive))) {
            var modes = new HashMap<String, Integer>();
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) modes.put(entry.getName(), entry.getMode());
            assertThat(modes)
                    .containsEntry("inputs/", 0555)
                    .containsEntry("inputs/diff.patch", 0444)
                    .containsEntry(".pi/", 0755)
                    .containsEntry(".pi/settings.json", 0644)
                    .containsEntry(".sessions/", 0755)
                    .containsEntry(".sessions/thread.jsonl", 0644)
                    .containsEntry("out/", 0755)
                    .containsEntry("out/.gitkeep", 0644)
                    .containsEntry("work/analysis/", 0755)
                    .containsEntry("work/analysis/.gitkeep", 0644);
        } finally {
            Files.delete(archive);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../etc/passwd", "/etc/shadow", ""})
    void shouldRejectUnsafeArchivePaths(String path) {
        assertThatThrownBy(() -> manager.createInputTar(Map.of(path, new byte[0]), Map.of()))
                .isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> manager.createInputTar(Map.of(), Map.of(path, temporary.resolve("file"))))
                .isInstanceOf(SandboxException.class);
    }

    @Test
    void shouldRejectSymbolicLinkDiskInputs() throws Exception {
        var source = Files.writeString(temporary.resolve("source"), "private");
        var link = Files.createSymbolicLink(temporary.resolve("link"), source);
        assertThatThrownBy(() -> manager.createInputTar(Map.of(), Map.of("input", link)))
                .isInstanceOf(SandboxException.class);
    }

    @Test
    void shouldStreamAnInputLargerThanTheFormerRepositoryBudget() throws Exception {
        var source = temporary.resolve("large.bin");
        byte[] chunk = new byte[1024 * 1024];
        java.util.Arrays.fill(chunk, (byte) 'x');
        try (var output = Files.newOutputStream(source)) {
            for (int index = 0; index < 64; index++) output.write(chunk);
        }
        Path archive = manager.createInputTar(Map.of(), Map.of("large.bin", source));
        try (var tar = new TarArchiveInputStream(Files.newInputStream(archive))) {
            assertThat(tar.getNextEntry().getSize()).isEqualTo(64L * 1024 * 1024);
            assertThat(tar.transferTo(java.io.OutputStream.nullOutputStream())).isEqualTo(Files.size(source));
            assertThat(tar.getNextEntry()).isNull();
        } finally {
            Files.delete(archive);
        }
    }

    @Test
    void shouldProduceACompleteEmptyArchive() throws Exception {
        Path archive = manager.createInputTar(Map.of(), Map.of());
        try (var tar = new TarArchiveInputStream(Files.newInputStream(archive))) {
            assertThat(tar.getNextEntry()).isNull();
            assertThat(Files.size(archive)).isGreaterThanOrEqualTo(1024);
        } finally {
            Files.delete(archive);
        }
    }

    @Test
    void shouldStreamCapturedDirectoriesWithoutDuplicatingTheirManifestWitnesses() throws Exception {
        var source = Files.createDirectory(temporary.resolve("repository"));
        var git = Files.createDirectory(source.resolve(".git"));
        var head = Files.writeString(git.resolve("HEAD"), "captured-head");
        Files.writeString(source.resolve("App.java"), "source");
        var directory = new de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory("inputs/repository/", source);
        Path archive = manager.createInputTar(
                Map.of(), Map.of("inputs/repository/.git/HEAD", head), java.util.List.of(directory));
        try (var tar = new TarArchiveInputStream(Files.newInputStream(archive))) {
            var names = new java.util.ArrayList<String>();
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) names.add(entry.getName());
            assertThat(names)
                    .doesNotHaveDuplicates()
                    .contains("inputs/repository/.git/HEAD", "inputs/repository/App.java");
        } finally {
            Files.delete(archive);
        }
    }

    @Test
    void shouldRejectDirectoryCollisionsAndMismatchedWitnesses() throws Exception {
        var source = Files.createDirectory(temporary.resolve("repository"));
        var other = Files.writeString(temporary.resolve("other"), "different");
        Files.writeString(source.resolve("file"), "captured");
        var directory = new de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory("repo/", source);
        assertThatThrownBy(() -> manager.createInputTar(
                        Map.of("repo/file", new byte[0]), Map.of(), java.util.List.of(directory)))
                .isInstanceOf(SandboxException.class);
        assertThatThrownBy(() ->
                        manager.createInputTar(Map.of(), Map.of("repo/file", other), java.util.List.of(directory)))
                .isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> manager.createInputTar(Map.of(), Map.of(), java.util.List.of(directory, directory)))
                .isInstanceOf(SandboxException.class);
        assertThatThrownBy(() ->
                        manager.createInputTar(Map.of("repo", new byte[0]), Map.of(), java.util.List.of(directory)))
                .isInstanceOf(SandboxException.class);
    }

    @Test
    void shouldRejectSymlinksAnywhereInACapturedDirectory() throws Exception {
        var source = Files.createDirectory(temporary.resolve("repository"));
        Files.createSymbolicLink(source.resolve("escape"), temporary);
        var directory = new de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory("repo/", source);
        assertThatThrownBy(() -> manager.createInputTar(Map.of(), Map.of(), java.util.List.of(directory)))
                .isInstanceOf(SandboxException.class);
    }
}
