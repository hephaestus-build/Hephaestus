package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.ReviewJobService;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWriteGuard;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReviewJobServiceTest extends BaseUnitTest {
    @Test
    void shouldReturnAnExistingActiveInvocationForAnIdenticalAssignmentSet() {
        var jobs = mock(ReviewJobRepository.class);
        var assignments = mock(ReviewJobAssignments.class);
        var store = mock(ReviewAssignmentStore.class);
        var policy = mock(ReviewMemberAiPolicy.class);
        var models = mock(LlmModelResolver.class);
        var assigned = RuntimeReviewTestData.assignment(5L, null);
        var review = RuntimeReviewTestData.review(ReviewKind.ITEM, assigned);
        var job = RuntimeReviewTestData.job();
        when(jobs.findByReviewIdAndWorkspaceIdAndStatusIn(any(), any(Long.class), any()))
                .thenReturn(Optional.of(job));
        var service = new ReviewJobService(
                jobs,
                mock(ReviewWriteGuard.class),
                assignments,
                store,
                policy,
                models,
                new JsonMapper(),
                mock(LlmAdmissionService.class),
                mock(ReviewMethodVerifier.class),
                mock(AgentRuns.class));
        assertThat(service.enqueue(review, 2, List.of(assigned.id()))).isEqualTo(job.getId());
        verifyNoInteractions(policy, models, assignments);
    }

    @Test
    void shouldRejectASecondItemWhileTheReviewHasAnActiveInvocation() {
        var jobs = mock(ReviewJobRepository.class);
        var assigned = RuntimeReviewTestData.assignment(5L, null);
        var review = RuntimeReviewTestData.review(ReviewKind.ITEM, assigned);
        when(jobs.findByReviewIdAndWorkspaceIdAndStatusIn(any(), any(Long.class), any()))
                .thenReturn(Optional.of(RuntimeReviewTestData.job()));
        var service = new ReviewJobService(
                jobs,
                mock(ReviewWriteGuard.class),
                mock(ReviewJobAssignments.class),
                mock(ReviewAssignmentStore.class),
                mock(ReviewMemberAiPolicy.class),
                mock(LlmModelResolver.class),
                new JsonMapper(),
                mock(LlmAdmissionService.class),
                mock(ReviewMethodVerifier.class),
                mock(AgentRuns.class));
        var other = new ReviewAssignmentId(review.id(), 31, 7);
        assertThatThrownBy(() -> service.enqueue(review, 7, List.of(other)))
                .isInstanceOf(ReviewJobStateConflictException.class);
    }

    @Test
    void shouldRejectAnEmptyInvocationBeforeTouchingTheQueue() {
        var jobs = mock(ReviewJobRepository.class);
        var service = new ReviewJobService(
                jobs,
                mock(ReviewWriteGuard.class),
                mock(ReviewJobAssignments.class),
                mock(ReviewAssignmentStore.class),
                mock(ReviewMemberAiPolicy.class),
                mock(LlmModelResolver.class),
                new JsonMapper(),
                mock(LlmAdmissionService.class),
                mock(ReviewMethodVerifier.class),
                mock(AgentRuns.class));
        var review = RuntimeReviewTestData.review(ReviewKind.ITEM, RuntimeReviewTestData.assignment(null, null));
        assertThatThrownBy(() -> service.enqueue(review, 2, List.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jobs);
    }
}
