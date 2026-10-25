package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * An attempt that was replaced after the gateway authenticated its call gets the same answer as one the filter refuses:
 * {@code 409} with {@link JobTokenAuthenticationFilter#ATTEMPT_ENDED} in the body that the runner reads.
 */
class AttemptEndedRefusalTest extends BaseUnitTest {

    @Test
    void shouldAnswerAModelCallOfAnAttemptLostAfterAuthenticationWithTheAttemptEndedText() throws Exception {
        var proxy = mock(LlmProxyService.class);
        when(proxy.proxy(any(), any(), any(), any())).thenThrow(ProxyRoutingService.attemptEnded());
        var gateway = MockMvcBuilders.standaloneSetup(new LlmProxyController(proxy))
                .setControllerAdvice(new GlobalControllerAdvice())
                .build();

        var response = gateway.perform(post("/internal/llm/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains(JobTokenAuthenticationFilter.ATTEMPT_ENDED);
    }
}
