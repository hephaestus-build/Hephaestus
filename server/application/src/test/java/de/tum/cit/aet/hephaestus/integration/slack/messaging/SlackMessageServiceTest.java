package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.model.ResponseMetadata;
import com.slack.api.model.view.View;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

class SlackMessageServiceTest extends BaseUnitTest {

    @Mock
    private SlackCredentialProvider credentialProvider;

    private SlackMessageService service;

    /** Read by the guard on the test's thread, and set by a local Slack server's thread in one test. */
    private volatile boolean silentModeEngaged;

    @BeforeEach
    void setUp() {
        silentModeEngaged = false;
        service = new SlackMessageService(
                credentialProvider,
                new SlackRateLimitTracker(new SimpleMeterRegistry()),
                new OutboundEgressGuard(() -> silentModeEngaged),
                "https://slack.com");
    }

    @AfterEach
    void closeService() {
        service.close();
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
                Arguments.of("sendEphemeralForWorkspace", (ThrowingSend)
                        s -> s.sendEphemeralForWorkspace(7L, "C1ABCDEFGH", "U123", List.of(), "f")),
                Arguments.of("postMessage", (ThrowingSend)
                        s -> s.postMessage(7L, "D1ABCDEFGH", null, List.of(), "hi", null)));
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
    void postMessage_noToken_throwsSlackSendException() {
        when(credentialProvider.resolve(any(IntegrationRef.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.postMessage(7L, "C1ABCDEFGH", null, List.of(), "fallback", null))
                .isInstanceOf(SlackSendException.class)
                .satisfies(ex -> {
                    SlackSendException sse = (SlackSendException) ex;
                    assertThat(sse.workspaceId()).isEqualTo(7L);
                    assertThat(sse.channelId()).isEqualTo("C1ABCDEFGH");
                    assertThat(sse.slackError()).isEqualTo("no_active_slack_connection");
                });
    }

    @Test
    void shouldRejectAPostWhenSilentModeEngagesDuringCredentialResolution() {
        when(credentialProvider.resolve(any(IntegrationRef.class))).thenAnswer(invocation -> {
            silentModeEngaged = true;
            return Optional.of(new BearerToken("token", null));
        });

        assertThatThrownBy(() -> service.postMessage(7L, "C1ABCDEFGH", null, List.of(), "message", null))
                .isInstanceOf(SlackSendException.class)
                .satisfies(
                        ex -> assertThat(((SlackSendException) ex).slackError()).isEqualTo("silent_mode_engaged"));
    }

    @Test
    void shouldCallSlackAtTheConfiguredApiBase() {
        assertThat(SlackMessageService.nativeConfig("https://slack.com").getMethodsEndpointUrlPrefix())
                .isEqualTo("https://slack.com/api/");
        assertThat(SlackMessageService.nativeConfig("http://slack.fake:8080/").getMethodsEndpointUrlPrefix())
                .isEqualTo("http://slack.fake:8080/api/");
    }

    @Test
    void shouldGiveItsOwnSlackClientACallDeadlineAndLeaveTheSharedClientUnchanged() {
        assertThat(ReflectionTestUtils.getField(service, "slack"))
                .isInstanceOfSatisfying(
                        Slack.class,
                        own -> assertThat(own.getHttpClient().getOkHttpClient().callTimeoutMillis())
                                .isEqualTo(SlackMessageService.NATIVE_CALL_TIMEOUT_MS));
        assertThat(Slack.getInstance().getHttpClient().getOkHttpClient().callTimeoutMillis())
                .isZero();
        assertThat(Slack.getInstance().getConfig().getHttpClientCallTimeoutMillis())
                .isNull();
    }

    @Test
    void shouldFailAWriteOnceAsATransportFailureWhenSlackAnswersAfterTheCallDeadline() throws Exception {
        try (MockWebServer slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(json("{\"ok\":true,\"team_id\":\"T1\"}"));
            slackApi.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body("{\"ok\":true,\"channel\":\"D1ABCDEFGH\",\"ts\":\"171234.5678\"}")
                    .headersDelay(5, TimeUnit.SECONDS)
                    .build());
            SlackMessageService local = localService(slackApi, 300);
            try {
                assertThatThrownBy(() -> local.postMessage(7L, "D1ABCDEFGH", null, List.of(), "hi", null))
                        .isInstanceOfSatisfying(
                                SlackSendException.class,
                                e -> assertThat(e.slackError()).isEqualTo("transport_failure"));
            } finally {
                local.close();
            }

            assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/auth.test");
            assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/chat.postMessage");
            assertThat(slackApi.getRequestCount()).isEqualTo(2);
        }
    }

    // Slack's 429 here asks for no wait (Retry-After: 0), so the budget waits its fallback of about a second.

    @Test
    void shouldPostAThrottledWriteOnceAfterTheWholeWait() throws Exception {
        try (MockWebServer slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(json("{\"ok\":true,\"team_id\":\"T1\"}"));
            slackApi.enqueue(throttled());
            slackApi.enqueue(json("{\"ok\":true,\"channel\":\"D1ABCDEFGH\",\"ts\":\"171234.5678\"}"));
            SlackMessageService local = localService(slackApi, SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
            long started = System.nanoTime();
            SlackMessageService.Posted posted;
            try {
                posted = local.postMessage(7L, "D1ABCDEFGH", null, List.of(), "hi", null);
            } finally {
                local.close();
            }

            assertThat(posted).isEqualTo(new SlackMessageService.Posted("D1ABCDEFGH", "171234.5678"));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isGreaterThanOrEqualTo(1_000);
            assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/auth.test");
            assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/chat.postMessage");
            assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/chat.postMessage");
            assertThat(slackApi.getRequestCount()).isEqualTo(3);
        }
    }

    @Test
    void shouldNotTryAThrottledWriteAgainWhenSilentModeEngagesDuringTheWait() throws Exception {
        try (MockWebServer slackApi = new MockWebServer()) {
            slackApi.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getUrl().encodedPath().equals("/api/auth.test")) {
                        return json("{\"ok\":true,\"team_id\":\"T1\"}");
                    }
                    // An operator engages Silent Mode while Slack throttles the write.
                    silentModeEngaged = true;
                    return throttled();
                }
            });
            slackApi.start();
            SlackMessageService local = localService(slackApi, SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
            try {
                assertThatThrownBy(() -> local.postMessage(7L, "D1ABCDEFGH", null, List.of(), "hi", null))
                        .isInstanceOfSatisfying(
                                SlackSendException.class,
                                e -> assertThat(e.slackError()).isEqualTo("silent_mode_engaged"));
            } finally {
                local.close();
            }

            assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/auth.test");
            assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/chat.postMessage");
            assertThat(slackApi.getRequestCount()).isEqualTo(2);
        }
    }

    private static MockResponse throttled() {
        return new MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "0")
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":false,\"error\":\"ratelimited\"}")
                .build();
    }

    /**
     * A gateway whose own Slack client calls {@code slackApi} with a deadline of {@code callTimeoutMs}. The test closes
     * it.
     */
    @Test
    void shouldRefuseAHistoryPageThatHasMoreButNoCursor() throws Exception {
        try (MockWebServer slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(json("{\"ok\":true,\"team_id\":\"T1\"}"));
            slackApi.enqueue(json("{\"ok\":true,\"messages\":[],\"has_more\":true}"));
            SlackMessageService local = localService(slackApi, SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
            try {
                assertThatThrownBy(() -> local.fetchHistoryPage(7L, "C1", "100.000001", "200.000001", null, 15))
                        .isInstanceOfSatisfying(
                                SlackSendException.class,
                                refused -> assertThat(refused.slackError()).isEqualTo("history_truncated"));
            } finally {
                local.close();
            }

            assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/auth.test");
            assertThat(bodyOf(slackApi.takeRequest()))
                    .as("both ends of the range are read, so a stored message on an end is never missed")
                    .contains("inclusive=1");
        }
    }

    private SlackMessageService localService(MockWebServer slackApi, int callTimeoutMs) {
        SlackConfig config = new SlackConfig();
        config.setHttpClientCallTimeoutMillis(callTimeoutMs);
        config.setMethodsEndpointUrlPrefix(slackApi.url("/api/").toString());
        // A token the SDK has not seen, so its team id cache asks auth.test first, as for a new workspace.
        when(credentialProvider.resolve(any(IntegrationRef.class)))
                .thenReturn(Optional.of(new BearerToken("xoxb-" + UUID.randomUUID(), null)));
        return new SlackMessageService(
                Slack.getInstance(config),
                credentialProvider,
                new SlackRateLimitTracker(new SimpleMeterRegistry()),
                new OutboundEgressGuard(() -> silentModeEngaged));
    }

    private static MockResponse json(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private static String bodyOf(RecordedRequest request) {
        ByteString body = request.getBody();
        assertThat(body).isNotNull();
        return body == null ? "" : body.utf8();
    }

    @Test
    void shouldTellThatAHistoryPageHasMoreWhenSlackSetsHasMoreOrANextCursor() {
        ResponseMetadata next = new ResponseMetadata();
        next.setNextCursor("bmV4dF90czoxNzEyMzQ1Njc4");
        ResponseMetadata last = new ResponseMetadata();
        last.setNextCursor("");

        assertThat(SlackMessageService.Matches.holdsMore(true, null)).isTrue();
        assertThat(SlackMessageService.Matches.holdsMore(false, next)).isTrue();
        assertThat(SlackMessageService.Matches.holdsMore(false, last)).isFalse();
        assertThat(SlackMessageService.Matches.holdsMore(false, null)).isFalse();
    }
}
