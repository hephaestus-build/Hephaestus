package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slack.api.methods.SlackApiException;
import com.slack.api.model.view.View;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

class SlackMessageServiceTest extends BaseUnitTest {

    @Mock
    private SlackCredentialProvider credentialProvider;

    private SlackMessageService service;

    private boolean silentModeEngaged;

    @BeforeEach
    void setUp() {
        silentModeEngaged = false;
        service = new SlackMessageService(
                credentialProvider,
                new SlackRateLimitTracker(new SimpleMeterRegistry()),
                new OutboundEgressGuard(() -> silentModeEngaged));
    }

    /** One guard fronts every content send; parameterizing pins each entry point without triplicating. */
    @ParameterizedTest(name = "{0} is refused while silent mode is engaged")
    @MethodSource("outboundSends")
    void silentModeRefusesEveryOutboundSend(String name, ThrowingSend send) {
        silentModeEngaged = true;

        assertThatThrownBy(() -> send.run(service))
                .isInstanceOf(SlackSendException.class)
                .satisfies(
                        ex -> assertThat(((SlackSendException) ex).slackError()).isEqualTo("silent_mode_engaged"));
        verifyNoInteractions(credentialProvider);
    }

    @FunctionalInterface
    interface ThrowingSend {
        void run(SlackMessageService service);
    }

    private static Stream<Arguments> outboundSends() {
        return Stream.of(
                Arguments.of(
                        "sendForWorkspace", (ThrowingSend) s -> s.sendForWorkspace(7L, "C1ABCDEFGH", List.of(), "f")),
                Arguments.of("sendEphemeralForWorkspace", (ThrowingSend)
                        s -> s.sendEphemeralForWorkspace(7L, "C1ABCDEFGH", "U123", List.of(), "f")),
                Arguments.of("startStream", (ThrowingSend) s -> s.startStream(7L, "C1ABCDEFGH", "171234.5678", "hi")),
                Arguments.of("appendStream", (ThrowingSend) s -> s.appendStream(7L, "C1ABCDEFGH", "171234.5678", "hi")),
                Arguments.of("stopStream", (ThrowingSend) s -> s.stopStream(
                        7L, "C1ABCDEFGH", "171234.5678", new SlackMessageService.StreamStop(null, List.of(), null))),
                Arguments.of("postMessage", (ThrowingSend)
                        s -> s.postMessage(7L, "D1ABCDEFGH", null, List.of(), "hi", null)),
                Arguments.of("setReaction", (ThrowingSend)
                        s -> s.setReaction(7L, "D1ABCDEFGH", "171234.5678", "eyes", true)));
    }

    /** These ride inbound events whose handlers do not catch, so a throw would NAK into the poison alarm. */
    @ParameterizedTest(name = "{0} is skipped, not refused, while silent mode is engaged")
    @MethodSource("decorationCalls")
    void silentModeSkipsDecorationCallsWithoutThrowing(String name, ThrowingSend call) {
        silentModeEngaged = true;

        assertThatCode(() -> call.run(service)).doesNotThrowAnyException();
        verifyNoInteractions(credentialProvider);
    }

    private static Stream<Arguments> decorationCalls() {
        return Stream.of(
                Arguments.of("publishHomeView", (ThrowingSend)
                        s -> s.publishHomeView(7L, "U123", View.builder().build())),
                Arguments.of("joinPublicChannel", (ThrowingSend) s -> s.joinPublicChannel(7L, "C1ABCDEFGH")));
    }

    @Test
    void sendForWorkspace_noToken_throwsSlackSendException() {
        when(credentialProvider.resolve(any(IntegrationRef.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendForWorkspace(7L, "C1ABCDEFGH", List.of(), "fallback"))
                .isInstanceOf(SlackSendException.class)
                .satisfies(ex -> {
                    SlackSendException sse = (SlackSendException) ex;
                    assertThat(sse.workspaceId()).isEqualTo(7L);
                    assertThat(sse.channelId()).isEqualTo("C1ABCDEFGH");
                    assertThat(sse.slackError()).isEqualTo("no_active_slack_connection");
                });
    }

    @Test
    void shouldRejectStreamWriteWhenSilentModeEngagesDuringCredentialResolution() {
        when(credentialProvider.resolve(any(IntegrationRef.class))).thenAnswer(invocation -> {
            silentModeEngaged = true;
            return Optional.of(new BearerToken("token", null));
        });

        assertThatThrownBy(() -> service.appendStream(7L, "C1ABCDEFGH", "171234.5678", "delta"))
                .isInstanceOf(SlackSendException.class)
                .satisfies(
                        ex -> assertThat(((SlackSendException) ex).slackError()).isEqualTo("silent_mode_engaged"));
    }

    private static SlackApiException apiException(int code, String retryAfterHeader) {
        Request request = new Request.Builder()
                .url("https://slack.com/api/chat.postMessage")
                .build();
        Response.Builder builder = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test");
        if (retryAfterHeader != null) {
            builder.header("Retry-After", retryAfterHeader);
        }
        return new SlackApiException(builder.build(), "");
    }

    private static Stream<Arguments> rateLimitCases() {
        return Stream.of(
                // A 429 with Retry-After: 3 → the caller must back off 3000 ms (honoring Slack, not a fixed tick).
                Arguments.of(429, "3", 3000L),
                Arguments.of(429, null, 1000L),
                Arguments.of(500, "7", SlackSendException.NOT_RATE_LIMITED));
    }

    @ParameterizedTest(name = "code={0} retryAfterHeader={1} -> {2}ms")
    @MethodSource("rateLimitCases")
    void rateLimitRetryAfterMillis(int code, String retryAfterHeader, long expected) {
        assertThat(SlackMessageService.rateLimitRetryAfterMillis(apiException(code, retryAfterHeader)))
                .isEqualTo(expected);
    }
}
