package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class HistoryCustodyQueryAdapterTest extends BaseUnitTest {
    private static final Instant CAPTURED_AT = Instant.parse(EvidenceSnapshotFixtures.CAPTURED_AT);
    private final JsonMapper mapper = new JsonMapper();
    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final HistoryCustodyQueryAdapter adapter =
            new HistoryCustodyQueryAdapter(mock(EvidenceFolderPersonDataCatalog.class), jobs, mapper);
    private final ReviewJob job = RuntimeReviewTestData.job();

    @BeforeEach
    void recordedCapture() {
        var capture = EvidenceSnapshotFixtures.snapshot(mapper);
        var source = EvidenceSnapshotFixtures.availableSource(capture, "scm.pull-request.diff", null);
        EvidenceSnapshotFixtures.file(capture, source, "inputs/sources/scm/diff.patch", "a".repeat(64));
        job.setCapture(capture);
    }

    @Test
    void shouldReadTheCaptureTimeOfTheReviewsOwnJobFromItsFullOrRetiredManifest() {
        stored(1, job);
        assertThat(capturedAt(1, RuntimeReviewTestData.REVIEW))
                .containsOnlyKeys(receipt(RuntimeReviewTestData.REVIEW))
                .containsValue(CAPTURED_AT);

        var capture = (ObjectNode) Objects.requireNonNull(job.getCapture());
        var full = mapper.treeToValue(capture.path("manifest"), JobFolderIndex.class);
        capture.set("manifest", mapper.valueToTree(full.retained()));
        assertThat(capture.path("manifest").has("files")).isFalse();
        stored(1, job);
        assertThat(capturedAt(1, RuntimeReviewTestData.REVIEW)).containsValue(CAPTURED_AT);
    }

    @Test
    void shouldNotReadAJobOfAnotherReviewOrAnotherWorkspace() {
        stored(1, job);
        when(jobs.findCaptureManifests(2L, List.of(job.getId()))).thenReturn(List.of());

        assertThat(capturedAt(1, UUID.randomUUID())).isEmpty();
        assertThat(capturedAt(2, RuntimeReviewTestData.REVIEW)).isEmpty();
    }

    @Test
    void shouldDateEachReceiptByItsOwnJobInOneRead() {
        var undatedJob = RuntimeReviewTestData.job();
        var dated = receipt(RuntimeReviewTestData.REVIEW);
        var undated = new HistoryCustodyQuery.Receipt(RuntimeReviewTestData.REVIEW, undatedJob.getId());
        var manifest = mapper.writeValueAsString(
                Objects.requireNonNull(job.getCapture()).path("manifest"));
        when(jobs.findCaptureManifests(eq(1L), any()))
                .thenReturn(List.of(manifest(job, manifest), manifest(undatedJob, null)));

        assertThat(adapter.capturedAt(1, List.of(dated, undated))).isEqualTo(Map.of(dated, CAPTURED_AT));
        verify(jobs).findCaptureManifests(eq(1L), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"no capture", "no manifest", "text manifest", "no time", "invalid time", "invalid digest"})
    void shouldLeaveTheCaptureTimeUnknownWhenTheManifestCannotShowIt(String defect) {
        var capture = (ObjectNode) Objects.requireNonNull(job.getCapture());
        var manifest = (ObjectNode) capture.path("manifest");
        switch (defect) {
            case "no capture" -> job.setCapture(null);
            case "no manifest" -> capture.remove("manifest");
            case "text manifest" -> capture.put("manifest", EvidenceSnapshotFixtures.CAPTURED_AT);
            case "no time" -> manifest.remove("capturedAt");
            case "invalid time" -> manifest.put("capturedAt", "yesterday");
            case "invalid digest" -> manifest.put("catalogDigest", "not-a-digest");
            default -> throw new IllegalArgumentException(defect);
        }
        stored(1, job);

        assertThat(capturedAt(1, RuntimeReviewTestData.REVIEW)).isEmpty();
    }

    @Test
    void shouldPropagateAFailedJobRead() {
        var failure = new DataAccessResourceFailureException("The database is unavailable");
        when(jobs.findCaptureManifests(1L, List.of(job.getId()))).thenThrow(failure);

        assertThatThrownBy(() -> capturedAt(1, RuntimeReviewTestData.REVIEW)).isSameAs(failure);
    }

    private HistoryCustodyQuery.Receipt receipt(UUID reviewId) {
        return new HistoryCustodyQuery.Receipt(reviewId, job.getId());
    }

    private Map<HistoryCustodyQuery.Receipt, Instant> capturedAt(long workspaceId, UUID reviewId) {
        return adapter.capturedAt(workspaceId, List.of(receipt(reviewId)));
    }

    /** The repository projects only the manifest out of the stored capture, as SQL's {@code capture->'manifest'}. */
    private void stored(long workspaceId, ReviewJob row) {
        var capture = row.getCapture();
        var manifest = capture == null || capture.path("manifest").isMissingNode()
                ? null
                : mapper.writeValueAsString(capture.path("manifest"));
        when(jobs.findCaptureManifests(workspaceId, List.of(row.getId()))).thenReturn(List.of(manifest(row, manifest)));
    }

    private static ReviewJobRepository.CaptureManifest manifest(ReviewJob row, @Nullable String manifest) {
        return new ReviewJobRepository.CaptureManifest() {
            @Override
            public UUID getId() {
                return row.getId();
            }

            @Override
            public UUID getReviewId() {
                return row.getReviewId();
            }

            @Override
            public @Nullable String getManifest() {
                return manifest;
            }
        };
    }
}
