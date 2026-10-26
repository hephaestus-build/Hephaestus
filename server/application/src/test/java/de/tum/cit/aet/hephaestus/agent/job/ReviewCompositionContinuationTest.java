package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReviewCompositionContinuationTest extends BaseUnitTest {
    @Test
    void shouldRefuseExpiredInputsAsGoneBeforeOpeningAReplacementFolder() {
        var bases = mock(ReviewCompositionBasisRepository.class);
        var files = mock(JobEvidenceFiles.class);
        var job = RuntimeReviewTestData.job();
        job.setAttempt(1);
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("original");
        var basis = new ReviewCompositionBasis();
        basis.setJobId(job.getId());
        basis.setWorkspaceId(1);
        basis.setBasisVersion(ReviewCompositionBasis.VERSION);
        basis.setExpiresAt(Instant.EPOCH);
        basis.setCopyId(UUID.randomUUID());
        when(bases.findByWorkspaceIdAndJobId(1, job.getId())).thenReturn(Optional.of(basis));
        when(files.vouchesForOriginalCopy(basis.getCopyId(), job.getId(), 1, 0)).thenReturn(true);
        var continuation =
                new ReviewCompositionContinuation(bases, files, mock(ReviewJobRepository.class), new JsonMapper());
        assertThatThrownBy(() -> continuation.restore(job))
                .isInstanceOfSatisfying(
                        ReviewCompositionContinuation.InputsUnavailableException.class,
                        unavailable -> assertThat(unavailable.erased()).isFalse());
        verify(files, never()).prepare(any(), any(), any());
    }

    @Test
    void shouldRefuseErasedInputsWithoutStagingThem() {
        var bases = mock(ReviewCompositionBasisRepository.class);
        var files = mock(JobEvidenceFiles.class);
        var job = RuntimeReviewTestData.job();
        job.setAttempt(1);
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("original");
        var basis = new ReviewCompositionBasis();
        basis.setJobId(job.getId());
        basis.setWorkspaceId(1);
        basis.setBasisVersion(ReviewCompositionBasis.VERSION);
        basis.setExpiresAt(Instant.now().plusSeconds(60));
        basis.setAdmissionDigest("original");
        basis.setAdmissionAttempt(0);
        basis.setCopyId(UUID.randomUUID());
        when(bases.findByWorkspaceIdAndJobId(1, job.getId())).thenReturn(Optional.of(basis));
        when(files.vouchesForOriginalCopy(basis.getCopyId(), job.getId(), 1, 0)).thenReturn(false);
        var continuation =
                new ReviewCompositionContinuation(bases, files, mock(ReviewJobRepository.class), new JsonMapper());
        assertThatThrownBy(() -> continuation.restore(job))
                .isInstanceOfSatisfying(
                        ReviewCompositionContinuation.InputsUnavailableException.class,
                        unavailable -> assertThat(unavailable.erased()).isTrue());
        verify(files, never()).prepare(any(), any(), any());
        verify(files, never()).beginPersonCapture(any());
    }
}
