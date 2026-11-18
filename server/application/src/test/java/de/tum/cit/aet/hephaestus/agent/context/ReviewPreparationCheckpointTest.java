package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.ReviewJobFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ReviewPreparationCheckpointTest extends BaseUnitTest {
    private static final byte[] FROZEN = "Frozen inputs".getBytes(StandardCharsets.UTF_8);
    private final JsonMapper mapper = new JsonMapper();
    private final AttemptInputs inputs = mock(AttemptInputs.class);
    private final EvidenceFolderPersonDataCatalog copies = mock(EvidenceFolderPersonDataCatalog.class);
    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);

    @TempDir
    Path root;

    /** A claimed job: the claim records it as running this run and attempt before its preparation starts. */
    private ReviewJob job() {
        var job = RuntimeReviewTestData.job();
        job.setRunId(UUID.randomUUID());
        ReviewJobFixtures.withStatus(job, ReviewJobStatus.RUNNING);
        when(jobs.findByIdAndWorkspaceId(job.getId(), job.getWorkspaceId())).thenReturn(Optional.of(job));
        return job;
    }

    private JobEvidenceFiles files() {
        return new JobEvidenceFiles(new FabricLayout(root.toString()), jobs, Clock.systemUTC(), copies, inputs);
    }

    private void publish(ReviewJob job) {
        var manifest = new JobFolderIndexBuilder(
                        new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC()), mapper, Clock.systemUTC())
                .build(
                        new ItemKind("scm.pull_request"),
                        Map.of(new SourceKind("scm.repository.tree"), CaptureContribution.empty()));
        var capture = mapper.createObjectNode()
                .put("attempt", job.getAttempt())
                .put("runId", job.getRunId().toString());
        capture.set("manifest", mapper.valueToTree(Objects.requireNonNull(manifest.manifest())));
        capture.putArray("directories");
        job.setCapture(capture);
        when(inputs.list(job))
                .thenReturn(List.of(
                        new AttemptInputs.RecordedFile("INDEX.json", ProvenanceDigest.sha256Hex(FROZEN), "r--------")));
    }

    private void restorable(ReviewJob job) {
        publish(job);
        when(inputs.content(job, "INDEX.json")).thenReturn(FROZEN);
        when(copies.withLiveAttempt(eq(job), any())).thenAnswer(invocation -> {
            Supplier<PreparedJobInputs> restore = invocation.getArgument(1);
            return restore.get();
        });
    }

    @Test
    void shouldDistinguishInterruptedPublicationFromPublishedAndErasedInputs() {
        var job = job();
        var files = files();
        assertThat(files.preparation(job)).isEqualTo(JobEvidenceFiles.Preparation.NEW);
        when(copies.hasAttemptCapture(job)).thenReturn(true);
        assertThat(files.preparation(job)).isEqualTo(JobEvidenceFiles.Preparation.INTERRUPTED);
        publish(job);
        when(copies.vouchesForLiveAttempt(job.getId(), job.getWorkspaceId(), job.getAttempt()))
                .thenReturn(true);
        assertThat(files.preparation(job)).isEqualTo(JobEvidenceFiles.Preparation.PUBLISHED);
        when(copies.vouchesForLiveAttempt(job.getId(), job.getWorkspaceId(), job.getAttempt()))
                .thenReturn(false);
        assertThat(files.preparation(job)).isEqualTo(JobEvidenceFiles.Preparation.UNAVAILABLE);
    }

    @Test
    void shouldRestoreTheFrozenBytesOnAnotherServer() throws Exception {
        var job = job();
        restorable(job);
        try (var restored = files().resume(job)) {
            assertThat(Files.readAllBytes(
                            Objects.requireNonNull(restored.filesOnDisk().get("INDEX.json"))))
                    .isEqualTo(FROZEN);
        }
    }

    @Test
    void shouldRejectRecordedBytesThatDifferFromTheirDigest() {
        var job = job();
        restorable(job);
        when(inputs.content(job, "INDEX.json")).thenReturn("Changed".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> files().resume(job))
                .isInstanceOf(CheckoutUnverifiedException.class)
                .hasMessageContaining("differs from its digest");
    }

    @Test
    void shouldRejectChangedPermissionsWithoutOverwritingTheFrozenFile() throws Exception {
        var job = job();
        restorable(job);
        Path target;
        try (var restored = files().resume(job)) {
            target = Objects.requireNonNull(restored.filesOnDisk().get("INDEX.json"));
        }
        Files.setPosixFilePermissions(target, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        assertThatThrownBy(() -> files().resume(job))
                .isInstanceOf(CheckoutUnverifiedException.class)
                .hasMessageContaining("recorded permissions");
        assertThat(Files.readAllBytes(target)).isEqualTo(FROZEN);
        assertThat(Files.getPosixFilePermissions(target)).contains(PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void shouldVerifyAnExistingLocalFolderBeforeReusingIt() throws Exception {
        var job = job();
        restorable(job);
        Path target;
        try (var restored = files().resume(job)) {
            target = Objects.requireNonNull(restored.filesOnDisk().get("INDEX.json"));
        }
        Files.createDirectories(target.getParent());
        Files.setPosixFilePermissions(target, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Files.writeString(target, "Changed local bytes");
        assertThatThrownBy(() -> files().resume(job))
                .isInstanceOf(CheckoutUnverifiedException.class)
                .hasMessageContaining("differs from its recorded digest");
    }

    @Test
    void shouldKeepTheFolderOfAResumedAttemptThroughEverySweepWhileItRuns() throws Exception {
        var job = job();
        restorable(job);
        var files = files();
        Path index;
        try (var restored = files.resume(job)) {
            index = Objects.requireNonNull(restored.filesOnDisk().get("INDEX.json"));

            assertThat(files.cleanEndedAttempts()).isZero();
            assertThat(index).exists();
        }
        // The attempt still runs after its preparation hands the folder on.
        assertThat(files.cleanEndedAttempts()).isZero();
        assertThat(index).exists();
    }

    @Test
    void shouldRemoveTheStagingRemainderOfASupersededRunWhileTheRunningAttemptHoldsTheLease() throws Exception {
        var job = job();
        restorable(job);
        var files = files();
        // A preparation of an earlier run died while it staged: only its staging copy is left, beside no folder.
        Path staging = new FabricLayout(root.toString())
                .jobsRoot()
                .resolve(Long.toString(job.getWorkspaceId()))
                .resolve(job.getId().toString())
                .resolve("." + job.getAttempt() + "-"
                        + ProvenanceDigest.sha256Hex(
                                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                        + ".preparing-1");
        try (var restored = files.resume(job)) {
            Path index = Objects.requireNonNull(restored.filesOnDisk().get("INDEX.json"));
            Files.createDirectories(staging);
            Files.write(staging.resolve("checkout.pack"), new byte[1024 * 1024]);

            assertThat(files.cleanEndedAttempts()).isZero();

            assertThat(staging).doesNotExist();
            assertThat(index).exists();
        }
    }

    @Test
    void shouldRemoveTheFolderOfAResumedAttemptOnceItsJobStopsRunningIt() throws Exception {
        var job = job();
        restorable(job);
        var files = files();
        try (var restored = files.resume(job)) {
            Path index = Objects.requireNonNull(restored.filesOnDisk().get("INDEX.json"));
            ReviewJobFixtures.withStatus(job, ReviewJobStatus.FAILED);

            assertThat(files.cleanEndedAttempts()).isZero();

            assertThat(index).doesNotExist();
        }
    }
}
