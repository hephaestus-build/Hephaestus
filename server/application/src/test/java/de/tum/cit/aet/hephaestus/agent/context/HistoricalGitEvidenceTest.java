package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalGitEvidenceTest extends BaseUnitTest {
    private final JobEvidenceFiles files = mock(JobEvidenceFiles.class);
    private final NativeGitExecutor git = mock(NativeGitExecutor.class);
    private final HistoricalGitEvidence verifier = new HistoricalGitEvidence(files, git);
    private final AgentJob job = new AgentJob();

    @TempDir
    private Path repository;

    @BeforeEach
    void setUp() {
        when(files.repositoryForVerification(job, "head-digest", "refs-digest")).thenReturn(repository);
    }

    @Test
    void shouldVerifyAllSubmittedLocationsWithOneNativeRepositoryTransfer() {
        var first = new HistoricalGitEvidence.Citation("a".repeat(40), "one.java", "same quote", 2, 2);
        var second = new HistoricalGitEvidence.Citation("b".repeat(40), "two.java", "same quote", 1, 1);
        doAnswer(invocation -> {
                    NativeGitExecutor.Request request = invocation.getArgument(1);
                    assertThat(request.operation()).isEqualTo(NativeGitExecutor.Operation.CITED_BLOBS);
                    assertThat(request.revisions())
                            .containsExactly(
                                    "c".repeat(40), first.revision(), first.path(), second.revision(), second.path());
                    try (var tar = new TarArchiveOutputStream((OutputStream) invocation.getArgument(3))) {
                        entry(tar, "0", "other line\nsame quote\n");
                        entry(tar, "1", "other line\nsame quote\n");
                    }
                    return null;
                })
                .when(git)
                .executeInSnapshot(eq(repository), any(), any(), any());
        var result =
                verifier.verifyAll(job, "head-digest", "refs-digest", "c".repeat(40), List.of(first, second, first));
        assertThat(java.util.Objects.requireNonNull(result.get(first)).matches())
                .isTrue();
        assertThat(java.util.Objects.requireNonNull(result.get(second)).matches())
                .isFalse();
        assertThat(java.util.Objects.requireNonNull(result.get(first)).artifactSha256())
                .hasSize(64);
        verify(git).executeInSnapshot(eq(repository), any(), any(), any());
    }

    @Test
    void shouldAnswerACitationOfAMissingPathAsAbsentWhileVerifyingTheRest() {
        var present = new HistoricalGitEvidence.Citation("a".repeat(40), "one.java", "same quote", 2, 2);
        var missing = new HistoricalGitEvidence.Citation("a".repeat(40), "gone.java", "same quote", 1, 1);
        doAnswer(invocation -> {
                    try (var tar = new TarArchiveOutputStream((OutputStream) invocation.getArgument(3))) {
                        entry(tar, "0", "other line\nsame quote\n");
                    }
                    return null;
                })
                .when(git)
                .executeInSnapshot(eq(repository), any(), any(), any());
        var result = verifier.verifyAll(job, "head-digest", "refs-digest", "c".repeat(40), List.of(present, missing));
        assertThat(java.util.Objects.requireNonNull(result.get(present)).matches())
                .isTrue();
        assertThat(result.get(missing)).isEqualTo(JobEvidenceFiles.QuoteMatch.absent());
    }

    @Test
    void shouldRejectUnexpectedArchiveEntries() {
        doAnswer(invocation -> {
                    try (var tar = new TarArchiveOutputStream((OutputStream) invocation.getArgument(3))) {
                        entry(tar, "../escape", "bytes");
                    }
                    return null;
                })
                .when(git)
                .executeInSnapshot(eq(repository), any(), any(), any());
        var citation = new HistoricalGitEvidence.Citation("a".repeat(40), "one.java", "quote", 1, 1);
        assertThatThrownBy(
                        () -> verifier.verifyAll(job, "head-digest", "refs-digest", "b".repeat(40), List.of(citation)))
                .isInstanceOf(JobDeliveryException.class);
    }

    @Test
    void shouldRejectArchiveWhenAnIndexArrivesTwice() {
        doAnswer(invocation -> {
                    try (var tar = new TarArchiveOutputStream((OutputStream) invocation.getArgument(3))) {
                        entry(tar, "0", "quote\n");
                        entry(tar, "0", "quote\n");
                    }
                    return null;
                })
                .when(git)
                .executeInSnapshot(eq(repository), any(), any(), any());
        var citation = new HistoricalGitEvidence.Citation("a".repeat(40), "one.java", "quote", 1, 1);
        assertThatThrownBy(
                        () -> verifier.verifyAll(job, "head-digest", "refs-digest", "b".repeat(40), List.of(citation)))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessage("Unexpected or duplicate native citation blob");
    }

    @Test
    void shouldRejectArchiveWhenAnIndexNamesNoRequestedBlob() {
        doAnswer(invocation -> {
                    try (var tar = new TarArchiveOutputStream((OutputStream) invocation.getArgument(3))) {
                        entry(tar, "1", "quote\n");
                    }
                    return null;
                })
                .when(git)
                .executeInSnapshot(eq(repository), any(), any(), any());
        var citation = new HistoricalGitEvidence.Citation("a".repeat(40), "one.java", "quote", 1, 1);
        assertThatThrownBy(
                        () -> verifier.verifyAll(job, "head-digest", "refs-digest", "b".repeat(40), List.of(citation)))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessage("Unexpected or duplicate native citation blob");
    }

    private static void entry(TarArchiveOutputStream tar, String name, String text) throws java.io.IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        var entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }
}
