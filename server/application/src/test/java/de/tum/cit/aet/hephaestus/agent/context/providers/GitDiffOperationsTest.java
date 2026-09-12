package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GitDiffOperationsTest extends BaseUnitTest {
    private static final RepositoryKey REPOSITORY = new RepositoryKey(1, 2);
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);

    @Mock
    private NativeGitExecutor git;

    @Test
    void shouldStageCompleteDiffFilesAndReleaseThemWhenClosed() throws Exception {
        archive(Map.of(
                "diff.patch",
                "[L1] +source\n",
                "diff_stat.txt",
                "stat",
                "diff_summary.md",
                "summary",
                "diff_paths.nul",
                "a.txt\0"));
        var capture = new GitDiffOperations(git).capture(REPOSITORY, BASE, HEAD);
        try (capture) {
            assertThat(capture.files())
                    .containsOnlyKeys("diff.patch", "diff_stat.txt", "diff_summary.md", "diff_paths.nul");
            assertThat(Files.readString(capture.directory().resolve("diff.patch")))
                    .isEqualTo("[L1] +source\n");
            assertThat(capture.isEmpty()).isFalse();
        }
        assertThat(capture.directory()).doesNotExist();
    }

    @Test
    void shouldRejectIncompleteDiffRatherThanReportEmpty() {
        archive(Map.of("diff_stat.txt", "stat"));
        assertThatThrownBy(() -> new GitDiffOperations(git).capture(REPOSITORY, BASE, HEAD))
                .isInstanceOf(JobPreparationException.class)
                .hasRootCauseMessage("Incomplete diff archive");
    }

    @Test
    void shouldRejectUnexpectedArchivePaths() {
        archive(Map.of("../escape", "payload"));
        assertThatThrownBy(() -> new GitDiffOperations(git).capture(REPOSITORY, BASE, HEAD))
                .isInstanceOf(JobPreparationException.class)
                .hasRootCauseMessage("Unexpected diff archive entry");
    }

    private void archive(Map<String, String> files) {
        doAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(3);
                    try (var tar = new TarArchiveOutputStream(output)) {
                        for (var file : files.entrySet()) {
                            byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
                            var entry = new TarArchiveEntry(file.getKey());
                            entry.setSize(bytes.length);
                            tar.putArchiveEntry(entry);
                            tar.write(bytes);
                            tar.closeArchiveEntry();
                        }
                    }
                    return null;
                })
                .when(git)
                .execute(any(), any(), any(), any());
    }
}
