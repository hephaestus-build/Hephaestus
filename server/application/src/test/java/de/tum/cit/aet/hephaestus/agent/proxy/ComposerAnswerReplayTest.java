package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.tum.cit.aet.hephaestus.agent.composer.ComposerTranscript;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * A composer that lost the answer to its conversation on the way sends the same conversation again. The gateway answers
 * it with the answer it recorded for that conversation, and asks no model.
 */
class ComposerAnswerReplayTest extends BaseUnitTest {

    private static final String ANSWER =
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Done.\"}}]}";

    @AfterEach
    void clearClaim() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAnswerAResentConversationWithItsRecordedAnswerAndAskNoModel() throws Exception {
        var attempt = new ProxyAttempt(ProxyAttempt.Caller.REVIEW, UUID.randomUUID(), 7L, 3, UUID.randomUUID(), null);
        SecurityContextHolder.getContext()
                .setAuthentication(new JobTokenAuthentication(ProxyIdentity.of(attempt), Set.of("llm_proxy")));
        var transcript = mock(ComposerTranscript.class);
        when(transcript.composes(7L, attempt.runId())).thenReturn(true);
        when(transcript.check(eq(7L), eq(attempt.runId()), eq(3), any(), any()))
                .thenReturn(new ComposerTranscript.Replay(ANSWER.getBytes(StandardCharsets.UTF_8)));
        var proxy = mock(LlmProxyService.class);
        var gateway = MockMvcBuilders.standaloneSetup(
                        new LlmProxyController(proxy, transcript, mock(ProxyAccounting.class), new ObjectMapper()))
                .setControllerAdvice(new GlobalControllerAdvice())
                .build();

        var response = gateway.perform(post("/internal/llm/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stream\":true}"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsByteArray()).isEqualTo(ANSWER.getBytes(StandardCharsets.UTF_8));
        verify(proxy, never()).proxy(any(), any(), any(), any(), any());
        verify(transcript, never()).served(anyLong(), any(), anyInt(), any(), any(), any());
    }
}
