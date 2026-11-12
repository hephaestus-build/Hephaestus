package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderLease;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** A server rebuilds an attempt's folder that another server captured, and never turns its own state into a refusal. */
@Tag("unit")
class JobEvidenceRebuildTest {
    private static final String RECORD = "{\"title\":\"recorded\"}";
    private static final String SHA = ProvenanceDigest.sha256Hex(RECORD.getBytes(StandardCharsets.UTF_8));

    @TempDir
    Path root;

    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final EvidenceFolderPersonDataCatalog copies = mock(EvidenceFolderPersonDataCatalog.class);
    private final AttemptInputs inputs = mock(AttemptInputs.class);

    private JobEvidenceFiles files() {
        return new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.systemUTC(), copies, inputs);
    }

    @Test
    void shouldRebuildTheFolderFromTheRecordedFiles() {
        var job = runningJob();
        when(copies.vouchesForLiveAttempt(job.getId(), job.getWorkspaceId(), job.getAttempt()))
                .thenReturn(true);
        recorded(job);

        assertThat(files().readText(job, "context/item.json", SHA)).contains(RECORD);
    }

    @Test
    void shouldReportARebuildDiskFailureAsIoRatherThanAnInvalidCapture() throws Exception {
        var job = runningJob();
        recorded(job);
        when(copies.vouchesForLiveAttempt(job.getId(), job.getWorkspaceId(), job.getAttempt()))
                .thenReturn(true);
        Files.writeString(root.resolve("jobs"), "obstructed volume path");
        assertThatThrownBy(() -> files().ensureAttemptFolder(job)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void shouldRebuildOnceWhenTwoThreadsOfThisServerNeedTheFolder() throws Exception {
        var job = runningJob();
        when(copies.vouchesForLiveAttempt(job.getId(), job.getWorkspaceId(), job.getAttempt()))
                .thenReturn(true);
        var listed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(inputs.list(job)).thenAnswer(invocation -> {
            listed.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of(new AttemptInputs.RecordedFile("context/item.json", SHA, "r--------"));
        });
        when(inputs.content(job, "context/item.json")).thenReturn(RECORD.getBytes(StandardCharsets.UTF_8));
        var files = files();

        var first = CompletableFuture.supplyAsync(() -> files.readText(job, "context/item.json", SHA));
        assertThat(listed.await(5, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.supplyAsync(() -> files.readText(job, "context/item.json", SHA));
        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).contains(RECORD);
        assertThat(second.get(10, TimeUnit.SECONDS))
                .as("the second waits, then finds the folder")
                .contains(RECORD);
        verify(inputs, times(1)).content(job, "context/item.json");
    }

    @Test
    void shouldWaitForAFolderLeaseThatAnotherHolderReleasesInsteadOfRefusing() throws Exception {
        var job = runningJob();
        when(copies.vouchesForLiveAttempt(job.getId(), job.getWorkspaceId(), job.getAttempt()))
                .thenReturn(true);
        recorded(job);
        var held = EvidenceFolderLease.tryAcquire(root, job.getWorkspaceId(), job.getId())
                .orElseThrow();
        var released = CompletableFuture.runAsync(
                () -> held.close(), CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));

        assertThat(files().readText(job, "context/item.json", SHA)).contains(RECORD);
        released.get(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldNeverFailARebuildWithAnExceptionThatACoverageCheckReadsAsARefusal() {
        // ReviewOutputService reads IllegalArgumentException and IllegalStateException as inconsistent coverage.
        for (Class<?> failure : List.of(AttemptFolderBusyException.class, CheckoutUnverifiedException.class)) {
            assertThat(IllegalArgumentException.class.isAssignableFrom(failure)).isFalse();
            assertThat(IllegalStateException.class.isAssignableFrom(failure)).isFalse();
        }
    }

    @Test
    void shouldReportARecordedFileThatDiffersAsACaptureFailure() {
        var job = runningJob();
        when(copies.vouchesForLiveAttempt(job.getId(), job.getWorkspaceId(), job.getAttempt()))
                .thenReturn(true);
        when(inputs.list(job))
                .thenReturn(List.of(new AttemptInputs.RecordedFile("context/item.json", SHA, "r--------")));
        when(inputs.content(job, "context/item.json")).thenReturn("changed".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> files().readText(job, "context/item.json", SHA))
                .isInstanceOfSatisfying(
                        CheckoutUnverifiedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(CheckoutUnverifiedException.Reason.MISMATCH));
    }

    @Test
    void shouldReportMissingRecordedFilesOfALiveAttemptAsACaptureFailure() {
        var job = runningJob();
        when(inputs.list(job)).thenReturn(List.of());
        when(copies.vouchesForLiveAttempt(job.getId(), job.getWorkspaceId(), job.getAttempt()))
                .thenReturn(true);

        assertThatThrownBy(() -> files().readText(job, "context/item.json", SHA))
                .isInstanceOfSatisfying(
                        CheckoutUnverifiedException.class,
                        failure ->
                                assertThat(failure.reason()).isEqualTo(CheckoutUnverifiedException.Reason.UNAVAILABLE));
    }

    @Test
    void shouldBuildNothingForAnErasedCopyOrAnEndedAttempt() {
        var erased = runningJob();
        recorded(erased);
        when(copies.vouchesForLiveAttempt(any(), anyLong(), anyInt())).thenReturn(false);
        assertThat(files().readText(erased, "context/item.json", SHA)).isEmpty();

        var ended = runningJob();
        ended.setStatus(ReviewJobStatus.FAILED);
        recorded(ended);
        assertThat(files().readText(ended, "context/item.json", SHA)).isEmpty();
    }

    private void recorded(ReviewJob job) {
        when(inputs.list(job))
                .thenReturn(List.of(new AttemptInputs.RecordedFile("context/item.json", SHA, "r--------")));
        when(inputs.content(job, "context/item.json")).thenReturn(RECORD.getBytes(StandardCharsets.UTF_8));
    }

    private static ReviewJob runningJob() {
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        RuntimeJobTestData.initialize(job, 1L);
        job.setRunId(UUID.randomUUID());
        job.setStatus(ReviewJobStatus.RUNNING);
        var capture = JsonMapper.builder().build().createObjectNode();
        capture.putArray("directories");
        job.setCapture(capture);
        return job;
    }
}
