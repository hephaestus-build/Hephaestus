package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.FileSystemUtils;

class GitSnapshotCleanupTest extends BaseUnitTest {
    @TempDir
    Path root;

    @Test
    void shouldRetainFailedCleanupUntilTheSnapshotCanActuallyBeRemoved() throws IOException {
        var layout = new FabricLayout(root.toString());
        var admission = mock(PersonDataCopyFence.Lease.class);
        Path directory;
        var capture = layout.spool().capture(1L, () -> admission);
        try (capture) {
            directory = layout.spool().directory(1L).path();
        }
        Path copy = Files.writeString(directory.resolve("person.txt"), "Captured personal data");
        var snapshot = new GitRepositoryManager.GitTreeSnapshot(directory, "commit", "tree", 1, 1, true, Set.of());
        try (var filesystem = mockStatic(FileSystemUtils.class)) {
            filesystem
                    .when(() -> FileSystemUtils.deleteRecursively(directory))
                    .thenThrow(new IOException("The filesystem refused deletion"));
            assertThatThrownBy(snapshot::close)
                    .isInstanceOf(GitRepositoryManager.GitOperationException.class)
                    .hasCauseInstanceOf(IOException.class);
            assertThat(copy).hasContent("Captured personal data");
            assertThat(directory).isDirectory();
            verify(admission, never()).close();
        }
        snapshot.close();
        assertThat(copy).doesNotExist();
        assertThat(directory).doesNotExist();
        verify(admission).close();
    }
}
