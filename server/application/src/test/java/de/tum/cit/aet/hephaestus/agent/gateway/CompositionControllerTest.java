package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.composer.CompositionSessions;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.composer.ReviewComposeSandboxKind;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.node.JsonNodeFactory;

/** The composer's routes act on the run's own job, fenced on its claim, and answer a stale claim or gone inputs. */
@Tag("unit")
class CompositionControllerTest {
    private static final Instant RUN_ENDS = Instant.now().plus(Duration.ofMinutes(20));

    private final ReviewComposeSandboxKind kind = mock(ReviewComposeSandboxKind.class);
    private final CompositionSessions sessions = mock(CompositionSessions.class);
    private final CompositionController controller = new CompositionController(kind, sessions);
    private final AgentRunFence fence = new AgentRunFence(UUID.randomUUID(), 7L, 2);
    private final SandboxRun run = new SandboxRun(
            new AgentSandboxes.LiveSandbox(UUID.randomUUID(), 7L, "review:1", AgentSandboxKind.REVIEW),
            fence,
            AgentRunKind.REVIEW_COMPOSE,
            1);
    private final ResultAdmissionService.Composing composing = new ResultAdmissionService.Composing(
            RuntimeReviewTestData.job(),
            new ResultAdmissionService.AdmissionIdentity(UUID.randomUUID(), 7L, 1, fence.runId()),
            RUN_ENDS);

    @Test
    void shouldAnswerTheStaleClaimWhenTheRunNoLongerHoldsItsLease() {
        when(kind.prepare(run)).thenThrow(new StaleRunException(fence));

        assertThatThrownBy(() -> controller.next(run, new CompositionController.NextRequest(List.of())))
                .isInstanceOf(StaleRunException.class);
        verifyNoInteractions(sessions);
    }

    @Test
    void shouldAnswerTheStaleClaimWhenAWriteFindsTheAttemptMovedOn() {
        when(kind.prepare(run)).thenReturn(composing);
        when(sessions.tool(eq(composing.job()), eq(composing.identity()), any()))
                .thenThrow(new ResultAdmissionService.StaleAttemptException());

        assertThatThrownBy(() -> controller.tool(run, "publish_package", request(RUN_ENDS)))
                .isInstanceOf(StaleRunException.class);
    }

    @Test
    void shouldAnswerNotFoundWhenARunOfAnotherKindCallsTheComposer() {
        var review = new SandboxRun(run.sandbox(), fence, AgentRunKind.REVIEW, 1);

        assertThatThrownBy(() -> controller.next(review, new CompositionController.NextRequest(List.of())))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(kind, sessions);
    }

    @Test
    void shouldAnswerGoneWithTheStopWhenTheInputsWereErased() {
        when(kind.prepare(run)).thenReturn(composing);
        when(sessions.tool(eq(composing.job()), eq(composing.identity()), any()))
                .thenThrow(new ReviewCompositionContinuation.InputsUnavailableException("erased", true));

        assertThatThrownBy(() -> controller.tool(run, "check_package", request(RUN_ENDS)))
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.GONE);
                    assertThat(refused.getReason()).isEqualTo("INPUTS_ERASED");
                });
    }

    @Test
    void shouldNeverLetAToolWaitPastTheRunsDeadline() {
        when(kind.prepare(run)).thenReturn(composing);
        var calls = ArgumentCaptor.forClass(CompositionSessions.ToolCall.class);
        when(sessions.tool(eq(composing.job()), eq(composing.identity()), calls.capture()))
                .thenReturn(new CompositionSessions.ToolResult("ok", false, null, null));

        controller.tool(run, "publish_package", request(RUN_ENDS.plus(Duration.ofHours(1))));

        assertThat(calls.getValue().deadline()).isEqualTo(RUN_ENDS);
        assertThat(calls.getValue().name()).isEqualTo("publish_package");
    }

    @Test
    void shouldRefuseAStopThatOnlyTheServerObserves() {
        assertThatThrownBy(() -> new CompositionController.StopRequest("RETRIES_EXHAUSTED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompositionController.CloseRequest(
                        Map.of(FeedbackDestination.ON_WORK, CompositionStop.ROUTING_UNAVAILABLE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CompositionController.StopRequest("COMPLETE").reason()).isEqualTo("COMPLETE");
    }

    private static CompositionController.ToolRequest request(Instant deadline) {
        return new CompositionController.ToolRequest(
                FeedbackDestination.ON_WORK, "call-1", JsonNodeFactory.instance.objectNode(), null, deadline);
    }
}
