package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.ReviewAttemptGuardService;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;

class ReviewAttemptGuardServiceTest extends BaseUnitTest {
    private static final long WORKSPACE_ID = 11L;
    private static final int ATTEMPT = 2;
    private static final UUID JOB_ID = UUID.fromString("b4297fa5-dc98-44fb-86df-ccad52b7c4db");

    @Mock
    private ReviewJobRepository jobs;

    private ReviewAttemptGuardService guard;

    @BeforeEach
    void setUp() {
        guard = new ReviewAttemptGuardService(jobs);
    }

    private ReviewJob runningJob() {
        var job = new ReviewJob();
        job.setId(JOB_ID);
        job.setWorkspaceId(WORKSPACE_ID);
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setAttempt(ATTEMPT);
        job.setLeaseId(UUID.randomUUID());
        when(jobs.findByIdAndWorkspaceIdForUpdate(JOB_ID, WORKSPACE_ID)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    void shouldAcceptTheRunningAttemptOwnedByAWorker() {
        runningJob();

        assertThatCode(() -> guard.requireOwner(WORKSPACE_ID, JOB_ID, ATTEMPT)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = ReviewJobStatus.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectAnAttemptOutsideRunningState(ReviewJobStatus status) {
        runningJob().setStatus(status);

        assertThatThrownBy(() -> guard.requireOwner(WORKSPACE_ID, JOB_ID, ATTEMPT))
                .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void shouldRejectAnEarlierOrLaterAttempt(int requestedAttempt) {
        runningJob();

        assertThatThrownBy(() -> guard.requireOwner(WORKSPACE_ID, JOB_ID, requestedAttempt))
                .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
    }

    @Test
    void shouldRejectAnAttemptWithoutAnOwningWorker() {
        runningJob().setLeaseId(null);

        assertThatThrownBy(() -> guard.requireOwner(WORKSPACE_ID, JOB_ID, ATTEMPT))
                .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
    }

    @Test
    void shouldRejectTheSameJobIdInAnotherWorkspace() {
        runningJob();
        assertThatCode(() -> guard.requireOwner(WORKSPACE_ID, JOB_ID, ATTEMPT)).doesNotThrowAnyException();

        assertThatThrownBy(() -> guard.requireOwner(WORKSPACE_ID + 1, JOB_ID, ATTEMPT))
                .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
    }

    @Test
    void shouldRejectAnUnknownJob() {
        when(jobs.findByIdAndWorkspaceIdForUpdate(JOB_ID, WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireOwner(WORKSPACE_ID, JOB_ID, ATTEMPT))
                .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
    }
}
