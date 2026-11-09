package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.agent.proxy.JobTokenAuthenticationFilter;
import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class AgentRunControllerTest extends BaseUnitTest {

    private static final long WORKSPACE = 7;
    private static final String SUBJECT = "review:42";
    private static final UUID RUN = UUID.fromString("0b1d4b71-5f1e-4a52-9a43-7e0c4b0d8a11");
    private static final AgentSandboxes.LiveSandbox SANDBOX =
            new AgentSandboxes.LiveSandbox(UUID.randomUUID(), WORKSPACE, SUBJECT, AgentSandboxKind.REVIEW);
    private static final AgentRunFence FENCE = new AgentRunFence(RUN, WORKSPACE, 3);

    private final AgentRuns runs = mock(AgentRuns.class);
    private final SandboxRunKind review = mock(SandboxRunKind.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var transactions = mock(TransactionTemplate.class);
        lenient()
                .when(transactions.execute(any()))
                .thenAnswer(invocation -> invocation
                        .<TransactionCallback<?>>getArgument(0)
                        .doInTransaction(mock(TransactionStatus.class)));
        when(review.kind()).thenReturn(AgentRunKind.REVIEW);
        var run = mock(AgentRun.class);
        // Shared by the cases that reach the run; a refused header never does.
        lenient().when(run.getConcurrencyKey()).thenReturn(SUBJECT);
        lenient().when(run.getKind()).thenReturn(AgentRunKind.REVIEW);
        lenient().when(run.getAttempt()).thenReturn(1);
        lenient().when(runs.find(new AgentRunRef(RUN, WORKSPACE))).thenReturn(Optional.of(run));
        mvc = MockMvcBuilders.standaloneSetup(new AgentRunController(runs, transactions, List.of(review)))
                .setControllerAdvice(new AgentGatewayErrors())
                .setCustomArgumentResolvers(new SandboxRunArgumentResolver(runs))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(SANDBOX, null, List.of()));
    }

    @AfterEach
    void clearSandbox() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequestBuilder claimed(MockHttpServletRequestBuilder request) {
        return request.header(SandboxRunArgumentResolver.RUN_HEADER, RUN.toString())
                .header(SandboxRunArgumentResolver.EPOCH_HEADER, "3");
    }

    private static SandboxRun run() {
        return new SandboxRun(SANDBOX, FENCE, AgentRunKind.REVIEW, 1);
    }

    @Test
    void shouldServeTheRunsContextFromItsKindWhileTheClaimHoldsIt() throws Exception {
        when(runs.isCurrent(FENCE.run(), 3)).thenReturn(true);
        when(review.context(run()))
                .thenReturn(new JsonMapper().createObjectNode().put("practice", "names"));

        mvc.perform(claimed(get("/internal/agent/runs/{runId}/context", RUN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.practice").value("names"));
    }

    @Test
    void shouldAnswerAStaleClaimWithTheLeaseLostConflict() throws Exception {
        when(runs.isCurrent(FENCE.run(), 3)).thenReturn(false);

        mvc.perform(claimed(get("/internal/agent/runs/{runId}/context", RUN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(JobTokenAuthenticationFilter.ATTEMPT_ENDED));
        verify(review, never()).context(any());
    }

    @Test
    void shouldRenewTheLeaseAndAnswerItsNewEnd() throws Exception {
        Instant until = Instant.parse("2026-11-09T12:05:00Z");
        when(runs.renew(FENCE)).thenReturn(Optional.of(until));

        mvc.perform(claimed(post("/internal/agent/runs/{runId}/lease", RUN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leasedUntil").exists());

        when(runs.renew(FENCE)).thenReturn(Optional.empty());
        mvc.perform(claimed(post("/internal/agent/runs/{runId}/lease", RUN))).andExpect(status().isConflict());
    }

    @Test
    void shouldEndTheRunThroughItsKindAndAnswerAStaleEndAsLeaseLost() throws Exception {
        when(review.end(run(), SandboxRunKind.Outcome.HANDED_ON)).thenReturn(true);
        mvc.perform(claimed(post("/internal/agent/runs/{runId}/end", RUN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"handed_on\"}"))
                .andExpect(status().isNoContent());

        when(review.end(run(), SandboxRunKind.Outcome.COMPLETED)).thenReturn(false);
        mvc.perform(claimed(post("/internal/agent/runs/{runId}/end", RUN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"completed\"}"))
                .andExpect(status().isConflict());

        mvc.perform(claimed(post("/internal/agent/runs/{runId}/end", RUN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"done\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldAnswerARunOfAnotherSubjectOrClaimAsNoRoute() throws Exception {
        // The headers must name the route's run, and an epoch.
        mvc.perform(get("/internal/agent/runs/{runId}/context", RUN)
                        .header(
                                SandboxRunArgumentResolver.RUN_HEADER,
                                UUID.randomUUID().toString())
                        .header(SandboxRunArgumentResolver.EPOCH_HEADER, "3"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/agent/runs/{runId}/context", RUN)
                        .header(SandboxRunArgumentResolver.RUN_HEADER, RUN.toString()))
                .andExpect(status().isNotFound());

        // A run of the workspace whose subject is another sandbox's.
        var other = mock(AgentRun.class);
        when(other.getConcurrencyKey()).thenReturn("review:43");
        when(runs.find(new AgentRunRef(RUN, WORKSPACE))).thenReturn(Optional.of(other));
        mvc.perform(claimed(get("/internal/agent/runs/{runId}/context", RUN))).andExpect(status().isNotFound());

        // A run of another workspace is no run of this one.
        when(runs.find(new AgentRunRef(RUN, WORKSPACE))).thenReturn(Optional.empty());
        mvc.perform(claimed(post("/internal/agent/runs/{runId}/lease", RUN))).andExpect(status().isNotFound());
        verify(runs, never()).renew(any());
    }

    @Test
    void shouldAnswerARunOfAKindThatNoSandboxDrivesAsNoRoute() throws Exception {
        when(runs.isCurrent(FENCE.run(), 3)).thenReturn(true);
        var unserved = MockMvcBuilders.standaloneSetup(
                        new AgentRunController(runs, mock(TransactionTemplate.class), List.of()))
                .setControllerAdvice(new AgentGatewayErrors())
                .setCustomArgumentResolvers(new SandboxRunArgumentResolver(runs))
                .build();

        unserved.perform(claimed(get("/internal/agent/runs/{runId}/context", RUN)))
                .andExpect(status().isNotFound());
        unserved.perform(claimed(post("/internal/agent/runs/{runId}/end", RUN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"completed\"}"))
                .andExpect(status().isNotFound());
    }
}
