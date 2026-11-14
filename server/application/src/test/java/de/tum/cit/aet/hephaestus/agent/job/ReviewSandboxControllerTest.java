package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The runner reads a {@code 409} or {@code 204} with the result's ETag as confirmed, so only a measured result gets it. */
class ReviewSandboxControllerTest extends BaseUnitTest {
    private static final String ETAG = "\"result-digest\"";

    private final ReviewSandboxRuns reviews = mock(ReviewSandboxRuns.class);
    private final AgentRuns runs = mock(AgentRuns.class);
    private final SandboxGatewaySessions.Session session = mock(SandboxGatewaySessions.Session.class);
    private final SandboxRun run = new SandboxRun(
            new AgentSandboxes.LiveSandbox(UUID.randomUUID(), 1L, "review", AgentSandboxKind.REVIEW),
            new AgentRunFence(UUID.randomUUID(), 1L, 1),
            AgentRunKind.REVIEW,
            1);

    @Test
    void shouldSendNoEtagWhenMeasuringTheResultFails() throws Exception {
        admittedUpload();
        doThrow(new CannotAcquireLockException("canceling statement due to lock timeout"))
                .when(reviews)
                .measured(any(), any());
        var response = new MockHttpServletResponse();

        assertThatThrownBy(() -> upload(response)).isInstanceOf(CannotAcquireLockException.class);
        assertThat(response.getHeader("ETag")).isNull();
    }

    @Test
    void shouldSendTheEtagWithAMeasuredResult() throws Exception {
        admittedUpload();
        var response = new MockHttpServletResponse();

        upload(response);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("ETag")).isEqualTo(ETAG);
    }

    private void admittedUpload() throws Exception {
        when(runs.isCurrent(any(), anyInt())).thenReturn(true);
        when(reviews.preparedSession(run)).thenReturn(session);
        when(session.upload(any(), any())).thenReturn(new SandboxGatewaySessions.Session.UploadResult(true, ETAG));
        when(session.result()).thenReturn(Map.of());
    }

    private void upload(MockHttpServletResponse response) {
        new ReviewSandboxController(reviews, runs)
                .result(run, "sha-256=:AA==:", 0, false, new MockHttpServletRequest(), response);
    }
}
