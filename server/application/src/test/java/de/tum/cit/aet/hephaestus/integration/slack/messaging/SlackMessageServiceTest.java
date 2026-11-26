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
import de.tum.cit.aet.hephaestus.integration.slack.SlackProperties;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.EphemeralMessage;
import de.tum.cit.aet.hephaestus.integration.slack.metrics.SlackMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
    private SlackRateLimitTracker rateLimits;

    /** Read by the guard on the test's thread, and set by a local Slack server's thread in one test. */
    private volatile boolean silentModeEngaged;

    /** The time of the local gateways' throttle budgets, so no throttle wait takes real time. */
    private VirtualSlackTime time;

    @BeforeEach
    void setUp() {
        silentModeEngaged = false;
        time = new VirtualSlackTime();
        rateLimits = new SlackRateLimitTracker(new SimpleMeterRegistry());
        service = new SlackMessageService(
                credentialProvider,
                rateLimits,
                new OutboundEgressGuard(() -> silentModeEngaged),
                new SlackMetrics(new SimpleMeterRegistry()),
                new SlackProperties(SlackProperties.SLACK));
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
                Arguments.of("postAdmitted", (ThrowingSend) s -> s.postAdmitted(
                        7L, new SlackMessageService.Outgoing("D1ABCDEFGH", null, List.of(), "hi", null), () -> true)),
                Arguments.of("joinPublicChannel", (ThrowingSend) s -> s.joinPublicChannel(7L, "C1ABCDEFGH")));
    }

    /** App Home is a direct interaction reply; Silent Mode skips it without failing the inbound event. */
    @Test
    void shouldSkipTheDirectHomeReplyWhileSilentModeIsEngaged() {
        silentModeEngaged = true;
        assertThatCode(() -> service.publishHomeView(
                        7L, "T123", "U123", View.builder().build(), null, () -> true))
                .doesNotThrowAnyException();
        verifyNoInteractions(credentialProvider);
    }

    @Test
    void shouldSendNeitherReplyWhenAReinstalledTeamDoesNotHaveTheRecordedDecision() throws Exception {
        try (MockWebServer slackApi = new MockWebServer()) {
            slackApi.start();
            var local = new SlackMessageService(
                    SlackSendFence.client(localConfig(slackApi, 1000)),
                    credentialProvider,
                    new SlackRateLimitTracker(new SimpleMeterRegistry()),
                    new OutboundEgressGuard(() -> false),
                    new SlackMetrics(new SimpleMeterRegistry()),
                    time);
            try {
                BooleanSupplier eligible = () -> false;
                when(credentialProvider.resolveActiveTeam(7L, "TOLD", eligible)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> local.sendEphemeralForTeam(
                                7L, "TOLD", "C123", "U123", new EphemeralMessage(List.of(), "saved"), eligible))
                        .isInstanceOf(SlackSendException.class);
                assertThatThrownBy(() -> local.publishHomeView(
                                7L, "TOLD", "U123", View.builder().build(), "original-view", eligible))
                        .isInstanceOf(SlackSendException.class);
                assertThat(slackApi.getRequestCount()).isZero();
            } finally {
                local.close();
            }
        }
    }

    @Test
    void shouldKeepTheNewerHomeViewWhenSlackRejectsTheDeferredWithdrawalView() throws Exception {
        try (MockWebServer slackApi = new MockWebServer()) {
            var acceptedViews = new CopyOnWriteArrayList<String>();
            slackApi.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    String body = bodyOf(request);
                    if (body.contains("hash=original-view") && !acceptedViews.isEmpty()) {
                        return json("{\"ok\":false,\"error\":\"hash_conflict\"}");
                    }
                    acceptedViews.add(body);
                    return json("{\"ok\":true}");
                }
            });
            slackApi.start();
            SlackConfig config = localConfig(slackApi, 1000);
            config.getMethodsConfig().setStatsEnabled(false);
            BooleanSupplier eligible = () -> true;
            when(credentialProvider.resolveActiveTeam(7L, "T123", eligible))
                    .thenReturn(Optional.of(new BearerToken("xoxb-test", null)));
            var local = new SlackMessageService(
                    SlackSendFence.client(config),
                    credentialProvider,
                    new SlackRateLimitTracker(new SimpleMeterRegistry()),
                    new OutboundEgressGuard(() -> false),
                    new SlackMetrics(new SimpleMeterRegistry()),
                    time);
            try {
                var allowed =
                        View.builder().type("home").callbackId("allowed-view").build();
                var withdrawn =
                        View.builder().type("home").callbackId("withdrawn-view").build();
                local.publishHomeView(7L, "T123", "U123", allowed, null, eligible);
                assertThatThrownBy(
                                () -> local.publishHomeView(7L, "T123", "U123", withdrawn, "original-view", eligible))
                        .isInstanceOfSatisfying(
                                SlackSendException.class,
                                refused -> assertThat(refused.slackError()).isEqualTo("hash_conflict"));
                assertThat(acceptedViews)
                        .singleElement()
                        .asString()
                        .contains("allowed-view")
                        .doesNotContain("hash=");
                assertThat(slackApi.getRequestCount()).isEqualTo(2);
            } finally {
                local.close();
            }
        }
    }

    @Test
    void postMessage_noToken_throwsSlackSendException() {
        when(credentialProvider.resolve(any(IntegrationRef.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.postAdmitted(
                        7L,
                        new SlackMessageService.Outgoing("C1ABCDEFGH", null, List.of(), "fallback", null),
                        () -> true))
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

        assertThatThrownBy(() -> service.postAdmitted(
                        7L,
                        new SlackMessageService.Outgoing("C1ABCDEFGH", null, List.of(), "message", null),
                        () -> true))
                .isInstanceOf(SlackSendException.class)
                .satisfies(
                        ex -> assertThat(((SlackSendException) ex).slackError()).isEqualTo("silent_mode_engaged"));
    }

    @Test
    void shouldCallSlackAtTheConfiguredApiBase() {
        assertThat(SlackMessageService.nativeConfig(new SlackProperties(SlackProperties.SLACK))
                        .getMethodsEndpointUrlPrefix())
                .isEqualTo("https://slack.com/api/");
        assertThat(SlackMessageService.nativeConfig(new SlackProperties("http://slack.fake:8080/"))
                        .getMethodsEndpointUrlPrefix())
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
        CountDownLatch released = new CountDownLatch(1);
        try (MockWebServer slackApi = new MockWebServer()) {
            // Slack answers the write only after the test ends, so only the call deadline can end the call: no answer
            // races the deadline, however slowly the test runs.
            slackApi.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    released.await();
                    return json("{\"ok\":true,\"channel\":\"D1ABCDEFGH\",\"ts\":\"171234.5678\"}");
                }
            });
            slackApi.start();
            SlackConfig config = localConfig(slackApi, 300);
            // Without the SDK's stats, no auth.test comes before the write; the deadline would bound that call too.
            config.getMethodsConfig().setStatsEnabled(false);
            SlackMessageService local = localService(config);
            try {
                assertThatThrownBy(() -> local.postAdmitted(
                                7L,
                                new SlackMessageService.Outgoing("D1ABCDEFGH", null, List.of(), "hi", null),
                                () -> true))
                        .isInstanceOfSatisfying(
                                SlackSendException.class,
                                e -> assertThat(e.slackError()).isEqualTo("transport_failure"));
            } finally {
                released.countDown();
                local.close();
            }

            // The deadline may end the call before the request reaches the server, so the server holds at most the one
            // write: a failed transport is not tried again.
            assertThat(slackApi.getRequestCount()).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void shouldPostWithoutReadingSilentModeAgainAfterAdmission() throws Exception {
        try (MockWebServer slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(json("{\"ok\":true,\"channel\":\"D1ABCDEFGH\",\"ts\":\"171234.5678\"}"));
            SlackConfig config = localConfig(slackApi, SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
            config.getMethodsConfig().setStatsEnabled(false);
            try (SlackMessageService local = localService(config)) {
                assertThat(local.postAdmitted(
                                7L, new SlackMessageService.Outgoing("D1ABCDEFGH", null, List.of(), "hi", null), () -> {
                                    silentModeEngaged = true;
                                    return true;
                                }))
                        .isEqualTo(new SlackMessageService.Posted("D1ABCDEFGH", "171234.5678"));
            }
            assertThat(slackApi.getRequestCount()).isOne();
        }
    }

    @Test
    void shouldDeferAPostBeforeAdmissionWhenTheWorkspaceIsThrottled() throws Exception {
        try (MockWebServer slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(json("{\"ok\":true,\"channel\":\"D1ABCDEFGH\",\"ts\":\"171234.5678\"}"));
            SlackConfig config = localConfig(slackApi, SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
            config.getMethodsConfig().setStatsEnabled(false);
            rateLimits.recordThrottle(7L, 120_750L);
            var admissions = new AtomicInteger();

            try (SlackMessageService local = localService(config)) {
                assertThatThrownBy(() -> local.postAdmitted(
                                7L,
                                new SlackMessageService.Outgoing("D1ABCDEFGH", null, List.of(), "hi", null),
                                () -> admissions.incrementAndGet() == 1))
                        .isInstanceOfSatisfying(SlackSendException.class, failure -> {
                            assertThat(failure.isRateLimited()).isTrue();
                            assertThat(failure.retryAfterMillis()).isPositive();
                            assertThat(failure.retryAfterMillis() % 1000).isZero();
                        });
            }
            assertThat(admissions).hasValue(0);
            assertThat(slackApi.getRequestCount()).isZero();
            assertThat(time.waits()).isEmpty();
        }
    }

    @Test
    void shouldAdmitAPostWhenOnlyAnotherWorkspaceHasAnActiveThrottle() throws Exception {
        try (MockWebServer slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(json("{\"ok\":true,\"channel\":\"D1ABCDEFGH\",\"ts\":\"171234.5678\"}"));
            SlackConfig config = localConfig(slackApi, SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
            config.getMethodsConfig().setStatsEnabled(false);
            rateLimits.recordThrottle(7L, 0L);
            rateLimits.recordThrottle(8L, 120_750L);
            var admissions = new AtomicInteger();

            try (SlackMessageService local = localService(config)) {
                assertThat(local.postAdmitted(
                                7L,
                                new SlackMessageService.Outgoing("D1ABCDEFGH", null, List.of(), "hi", null),
                                () -> admissions.incrementAndGet() == 1))
                        .isEqualTo(new SlackMessageService.Posted("D1ABCDEFGH", "171234.5678"));
            }
            assertThat(admissions).hasValue(1);
            assertThat(slackApi.getRequestCount()).isOne();
        }
    }

    @Test
    void shouldReturnTheFirstPostThrottleToTheOutbox() throws Exception {
        try (MockWebServer slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(throttled());
            SlackConfig config = localConfig(slackApi, SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
            config.getMethodsConfig().setStatsEnabled(false);
            try (SlackMessageService local = localService(config)) {
                assertThatThrownBy(() -> local.postAdmitted(
                                7L,
                                new SlackMessageService.Outgoing("D1ABCDEFGH", null, List.of(), "hi", null),
                                () -> true))
                        .isInstanceOfSatisfying(
                                SlackSendException.class,
                                e -> assertThat(e.isRateLimited()).isTrue());
            }

            assertThat(time.waits()).isEmpty();
            assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/chat.postMessage");
            assertThat(slackApi.getRequestCount()).isOne();
        }
    }

    @Test
    void shouldNotTryAThrottledWriteAgainWhenSilentModeEngagesDuringTheWait() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        try (MockWebServer slackApi = new MockWebServer()) {
            slackApi.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    paths.add(request.getUrl().encodedPath());
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
                assertThatThrownBy(() -> local.sendEphemeralForWorkspace(7L, "D1ABCDEFGH", "U1", List.of(), "hi"))
                        .isInstanceOfSatisfying(
                                SlackSendException.class,
                                e -> assertThat(e.slackError()).isEqualTo("silent_mode_engaged"));
            } finally {
                local.close();
            }

            assertThat(paths).containsExactly("/api/auth.test", "/api/chat.postEphemeral");
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

    /**
     * A gateway whose own Slack client calls {@code slackApi} with a deadline of {@code callTimeoutMs}. The test closes
     * it.
     */
    private SlackMessageService localService(MockWebServer slackApi, int callTimeoutMs) {
        return localService(localConfig(slackApi, callTimeoutMs));
    }

    private static SlackConfig localConfig(MockWebServer slackApi, int callTimeoutMs) {
        SlackConfig config = new SlackConfig();
        config.setHttpClientCallTimeoutMillis(callTimeoutMs);
        config.setMethodsEndpointUrlPrefix(slackApi.url("/api/").toString());
        return config;
    }

    private SlackMessageService localService(SlackConfig config) {
        // A token the SDK has not seen, so its team id cache asks auth.test first, as for a new workspace.
        when(credentialProvider.resolve(any(IntegrationRef.class)))
                .thenReturn(Optional.of(new BearerToken("xoxb-" + UUID.randomUUID(), null)));
        return new SlackMessageService(
                SlackSendFence.client(config),
                credentialProvider,
                rateLimits,
                new OutboundEgressGuard(() -> silentModeEngaged),
                new SlackMetrics(new SimpleMeterRegistry()),
                time);
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

    @Test
    void consentReadbackFindsAnAnnouncementAfterTheFirstPage() throws Exception {
        try (MockWebServer api = new MockWebServer()) {
            var pages = new AtomicInteger();
            api.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getUrl().encodedPath().equals("/api/auth.test"))
                        return json("{\"ok\":true,\"team_id\":\"T1\"}");
                    pages.incrementAndGet();
                    return bodyOf(request).contains("cursor=older")
                            ? json(
                                    "{\"ok\":true,\"messages\":[{\"ts\":\"100.000001\",\"text\":\"announcement\"}],\"has_more\":false}")
                            : json(
                                    "{\"ok\":true,\"messages\":[{\"ts\":\"200.0\",\"text\":\"later chat\"}],\"has_more\":true,\"response_metadata\":{\"next_cursor\":\"older\"}}");
                }
            });
            api.start();
            var local = localService(api, SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
            try {
                var found =
                        local.readWorkspaceMessages(7L, "C1", null, "1.0", m -> "announcement".equals(m.getText()), 20);
                assertThat(found.ts()).containsExactly("100.000001");
                assertThat(pages).hasValue(2);
            } finally {
                local.close();
            }
        }
    }

    @Test
    void completePagedAbsenceCanBeDistinguishedFromABoundedSearch() throws Exception {
        try (MockWebServer api = new MockWebServer()) {
            api.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getUrl().encodedPath().equals("/api/auth.test"))
                        return json("{\"ok\":true,\"team_id\":\"T1\"}");
                    return bodyOf(request).contains("cursor=older")
                            ? json("{\"ok\":true,\"messages\":[],\"has_more\":false}")
                            : json(
                                    "{\"ok\":true,\"messages\":[],\"has_more\":true,\"response_metadata\":{\"next_cursor\":\"older\"}}");
                }
            });
            api.start();
            var local = localService(api, SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
            try {
                assertThat(local.readWorkspaceMessages(7L, "C1", null, "1.0", m -> false, 20))
                        .isEqualTo(new SlackMessageService.Matches(List.of(), false));
                assertThat(local.readWorkspaceMessages(7L, "C1", null, "1.0", m -> false, 1))
                        .isEqualTo(new SlackMessageService.Matches(List.of(), true));
            } finally {
                local.close();
            }
        }
    }

    @Test
    void aThrottledLaterReadbackPageCannotSpendTheWriteFenceBudget() throws Exception {
        try (MockWebServer api = new MockWebServer()) {
            var pages = new AtomicInteger();
            api.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getUrl().encodedPath().equals("/api/auth.test"))
                        return json("{\"ok\":true,\"team_id\":\"T1\"}");
                    pages.incrementAndGet();
                    return bodyOf(request).contains("cursor=older")
                            ? new MockResponse.Builder()
                                    .code(429)
                                    .addHeader("Retry-After", "60")
                                    .addHeader("Content-Type", "application/json")
                                    .body("{\"ok\":false,\"error\":\"ratelimited\"}")
                                    .build()
                            : json(
                                    "{\"ok\":true,\"messages\":[],\"has_more\":true,\"response_metadata\":{\"next_cursor\":\"older\"}}");
                }
            });
            api.start();
            var local = localService(api, SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
            try {
                assertThatThrownBy(() -> local.readWorkspaceMessages(7L, "C1", null, "1.0", m -> false, 20))
                        .isInstanceOf(SlackSendException.class);
                assertThat(pages).hasValue(2);
            } finally {
                local.close();
            }
        }
    }

    @Test
    void aFailureOnALaterReadbackPageCannotProveAbsence() throws Exception {
        try (MockWebServer api = new MockWebServer()) {
            api.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getUrl().encodedPath().equals("/api/auth.test"))
                        return json("{\"ok\":true,\"team_id\":\"T1\"}");
                    return bodyOf(request).contains("cursor=older")
                            ? json("{\"ok\":false,\"error\":\"missing_scope\"}")
                            : json(
                                    "{\"ok\":true,\"messages\":[],\"has_more\":true,\"response_metadata\":{\"next_cursor\":\"older\"}}");
                }
            });
            api.start();
            var local = localService(api, SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
            try {
                assertThatThrownBy(() -> local.readWorkspaceMessages(7L, "C1", null, "1.0", m -> false, 20))
                        .isInstanceOfSatisfying(
                                SlackSendException.class,
                                failure -> assertThat(failure.slackError()).isEqualTo("missing_scope"));
            } finally {
                local.close();
            }
        }
    }
}
