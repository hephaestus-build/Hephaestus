package de.tum.cit.aet.hephaestus.agent.sandbox;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Any server replica builds a sandbox's input archive from the same inputs, so two builds of the same inputs are byte
 * for byte the same, whenever and wherever they run.
 */
@Tag("unit")
class SandboxWorkspaceArchiveIdentityTest {

    @TempDir
    Path temporary;

    @Test
    void shouldWriteTheSameBytesForTheSameInputsOnAnotherReplica() throws Exception {
        // Replica A: the inputs in one order, its checkout files written first, at one time.
        Path checkoutA = checkout(temporary.resolve("a"), List.of("src/B.java", "src/A.java", "README.md"), 1_000);
        Path archiveA = new SandboxWorkspaceManager()
                .createInputTar(
                        temporary.resolve("spool-a"),
                        ordered("inputs/z.json", "inputs/a.json"),
                        Map.of(),
                        List.of(new EvidenceDirectory("repos/reviewed/", checkoutA)));
        // Replica B: the same inputs in the other order, its checkout written in another order and at another time.
        Path checkoutB = checkout(temporary.resolve("b"), List.of("README.md", "src/A.java", "src/B.java"), 9_000);
        Path archiveB = new SandboxWorkspaceManager()
                .createInputTar(
                        temporary.resolve("spool-b"),
                        ordered("inputs/a.json", "inputs/z.json"),
                        Map.of(),
                        List.of(new EvidenceDirectory("repos/reviewed/", checkoutB)));

        assertThat(Files.readAllBytes(archiveB)).isEqualTo(Files.readAllBytes(archiveA));
    }

    @Test
    void shouldRecordNoTimeHostUserOrOrderOfItsOwn() throws Exception {
        Path checkout = checkout(temporary.resolve("c"), List.of("src/B.java", "src/A.java"), 5_000);
        Path archive = new SandboxWorkspaceManager()
                .createInputTar(
                        temporary.resolve("spool"),
                        ordered("inputs/z.json", "inputs/a.json"),
                        Map.of(),
                        List.of(new EvidenceDirectory("repos/reviewed/", checkout)));

        List<String> names = new ArrayList<>();
        try (var tar = new TarArchiveInputStream(Files.newInputStream(archive))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                names.add(entry.getName());
                assertThat(entry.getModTime().toInstant()).as(entry.getName()).isEqualTo(Instant.EPOCH);
                assertThat(entry.getUserName()).as(entry.getName()).isEmpty();
                assertThat(entry.getGroupName()).as(entry.getName()).isEmpty();
            }
        }
        assertThat(names).isSorted();
    }

    private static Map<String, byte[]> ordered(String... paths) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String path : paths) files.put(path, path.getBytes(UTF_8));
        return files;
    }

    /** A checkout folder with these files, written in this order, each with this modification time. */
    private static Path checkout(Path root, List<String> files, long modifiedMillis) throws Exception {
        for (String file : files) {
            Path path = root.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, file);
            Files.setLastModifiedTime(path, FileTime.fromMillis(modifiedMillis));
        }
        return root;
    }
}
