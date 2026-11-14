package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentGatewayErrors;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.AttemptCaptureFailures;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A write that waited past the pool's lock bound answers a sandbox {@code 503}, which the runner retries: never a
 * {@code 409}, which it reads as a refusal, and never a capture failure.
 */
class SandboxBusyAnswerTest extends BaseUnitTest {
    private static final CannotAcquireLockException LOCK_TIMEOUT =
            new CannotAcquireLockException("canceling statement due to lock timeout");

    @Test
    void shouldAnswerAModelCallBusyWhenItsAdmissionWaitedPastTheLockBound() throws Exception {
        var proxy = mock(LlmProxyService.class);
        when(proxy.proxy(any(), any(), any(), any())).thenThrow(LOCK_TIMEOUT);

        var response = gateway(new LlmProxyController(proxy))
                .perform(post("/internal/llm/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void shouldAnswerAResultAdmissionBusyWhenItWaitedPastTheLockBound() throws Exception {
        var admission = mock(ResultAdmissionService.class);
        var failures = mock(AttemptCaptureFailures.class);
        when(admission.admit(any(), any())).thenThrow(LOCK_TIMEOUT);
        var now = Instant.now();
        var jwt = new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                UUID.randomUUID(),
                1L,
                1,
                UUID.randomUUID(),
                Set.of("llm_proxy"),
                "test",
                now,
                now.plusSeconds(60),
                now.plusSeconds(30));

        var response = gateway(new ResultAdmissionController(admission, failures))
                .perform(post("/internal/llm/admit-results")
                        .principal(new JobTokenAuthentication(ProxyIdentity.of(jwt), jwt.scopes()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractVersion":3,"reviewId":"%s","jobId":"%s","attempt":0}
                                """.formatted(UUID.randomUUID(), jwt.id())))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(503);
        verifyNoInteractions(failures);
    }

    private static MockMvc gateway(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentGatewayErrors(), new GlobalControllerAdvice())
                .build();
    }
}
