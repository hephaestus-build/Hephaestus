package de.tum.cit.aet.hephaestus.integration.scm.github.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.web.client.RestClient;

class GitHubWebhookRedeliveryTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:10:00Z");

    @Mock
    private GitHubAppTokenService tokens;

    private MockWebServer github;
    private SimpleMeterRegistry meters;
    private GitHubWebhookRedelivery redelivery;
    private final List<Duration> waits = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        github = new MockWebServer();
        github.start();
        meters = new SimpleMeterRegistry();
        redelivery = new GitHubWebhookRedelivery(
                tokens,
                Clock.fixed(NOW, ZoneOffset.UTC),
                meters,
                RestClient.builder(),
                github.url("/").toString().replaceAll("/$", ""),
                waits::add);
    }

    @AfterEach
    void tearDown() throws IOException {
        github.close();
    }

    @Test
    void shouldRedeliverTheLatestAttemptOfEachEventThatNoAttemptDeliveredWhenTheReceiverFailed() throws Exception {
        configured();
        respond(
                deliveries(
                        redelivery(11, "timed-out", 5, 0),
                        delivery(12, "timed-out", 15, 0),
                        redelivery(13, "recovered", 6, 202),
                        delivery(14, "refused", 7, 400),
                        redelivery(15, "exhausted", 8, 503),
                        redelivery(16, "exhausted", 18, 503),
                        redelivery(17, "exhausted", 28, 503),
                        redelivery(24, "exhausted", 38, 503),
                        redelivery(25, "exhausted", 48, 503),
                        delivery(18, "exhausted", 88, 503),
                        delivery(19, "unavailable", 12, 503),
                        delivery(20, "recovered", 25, 503),
                        redelivery(21, "original-gone", 30, 503)),
                "<" + github.url("/app/hook/deliveries?per_page=100&cursor=v1_page2") + ">; rel=\"next\"");
        respond(
                deliveries(delivery(22, "last-page", 55, 502), delivery(23, "original-gone", 125, 503)),
                "<" + github.url("/app/hook/deliveries?per_page=100&cursor=v1_page3") + ">; rel=\"next\"");
        for (int i = 0; i < 3; i++)
            github.enqueue(new MockResponse.Builder().code(202).build());

        redelivery.redeliverFailed();

        List<RecordedRequest> requests = new ArrayList<>();
        for (int i = 0; i < 5; i++) requests.add(github.takeRequest());
        assertThat(requests)
                .extracting(request -> request.getMethod() + " " + request.getTarget())
                .containsExactly(
                        "GET /app/hook/deliveries?per_page=100",
                        "GET /app/hook/deliveries?per_page=100&cursor=v1_page2",
                        // Oldest original delivery first: 55, 15 and 12 minutes ago.
                        "POST /app/hook/deliveries/22/attempts",
                        "POST /app/hook/deliveries/11/attempts",
                        "POST /app/hook/deliveries/19/attempts");
        assertThat(requests)
                .allSatisfy(request ->
                        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer app-jwt"));
        assertThat(github.getRequestCount()).isEqualTo(5);
        assertThat(outcome("requested")).isEqualTo(3);
        assertThat(meters.get("github.webhook.unrecovered").gauge().value()).isEqualTo(1);
    }

    @Test
    void shouldCountARefusedRedeliveryAndKeepTryingTheOthersWhenGitHubRefusesOne() throws Exception {
        configured();
        respond(deliveries(delivery(31, "first", 1, 503), delivery(32, "second", 2, 0)), null);
        github.enqueue(new MockResponse.Builder().code(422).build());
        github.enqueue(new MockResponse.Builder().code(202).build());

        redelivery.redeliverFailed();

        github.takeRequest();
        assertThat(github.takeRequest().getTarget()).isEqualTo("/app/hook/deliveries/32/attempts");
        assertThat(github.takeRequest().getTarget()).isEqualTo("/app/hook/deliveries/31/attempts");
        assertThat(outcome("refused")).isEqualTo(1);
        assertThat(outcome("requested")).isEqualTo(1);
    }

    @Test
    void shouldCountTheRunAsUnavailableWhenGitHubDoesNotListTheDeliveries() {
        configured();
        github.enqueue(new MockResponse.Builder().code(401).build());

        redelivery.redeliverFailed();

        assertThat(github.getRequestCount()).isEqualTo(1);
        assertThat(outcome("unavailable")).isEqualTo(1);
        assertThat(outcome("requested")).isZero();
    }

    @Test
    void shouldRedeliverAnEventThatTheReceiverAnsweredWithARedirect() throws Exception {
        configured();
        respond(deliveries(delivery(41, "redirected", 3, 302)), null);
        github.enqueue(new MockResponse.Builder().code(202).build());

        redelivery.redeliverFailed();

        assertThat(github.getRequestCount()).isEqualTo(2);
        github.takeRequest();
        assertThat(github.takeRequest().getTarget()).isEqualTo("/app/hook/deliveries/41/attempts");
        assertThat(outcome("requested")).isEqualTo(1);
    }

    @Test
    void shouldAskGitHubNothingWhenTheAppIsNotConfigured() {
        when(tokens.isConfigured()).thenReturn(false);

        redelivery.redeliverFailed();

        assertThat(github.getRequestCount()).isZero();
    }

    @Test
    void shouldWaitForTheNextDueTimeInsteadOfRedeliveringOnEveryRun() {
        configured();
        respond(
                deliveries(
                        redelivery(51, "waiting", 4, 503),
                        redelivery(52, "waiting", 14, 503),
                        delivery(53, "waiting", 15, 503)),
                null);

        redelivery.redeliverFailed();

        assertThat(github.getRequestCount())
                .as("only the listing: the third redelivery is due at 20 minutes")
                .isOne();
        assertThat(meters.get("github.webhook.unrecovered").gauge().value()).isZero();
    }

    @Test
    void shouldOutlastABrokerStallOfAnHourAtEveryPhaseOfTheRuns() throws IOException {
        configured();
        for (Duration stall = Duration.ofMinutes(5);
                stall.compareTo(Duration.ofHours(1)) <= 0;
                stall = stall.plusMinutes(5)) {
            for (Duration phase : RUN_PHASES) {
                assertThat(attemptsThrough(stall, phase))
                        .as("an event that fails through a %s stall, first run %s after it", stall, phase)
                        .isPositive();
            }
        }
    }

    @Test
    void shouldKeepAnAttemptInReserveThroughTheSlowestRecoveryMeasured() throws IOException {
        configured();
        for (Duration phase : RUN_PHASES) {
            assertThat(attemptsThrough(SLOWEST_RECOVERY_MEASURED, phase))
                    .as("attempts used, first run %s after the failure", phase)
                    .isPositive()
                    .isLessThan(GitHubWebhookRedelivery.MAX_ATTEMPTS);
        }
    }

    @Test
    void shouldCountAnEventAsUnrecoveredWhenTheStallOutlastsEveryAttempt() throws IOException {
        configured();
        for (Duration phase : RUN_PHASES) {
            assertThat(attemptsThrough(GitHubWebhookRedelivery.WINDOW, phase)).isEqualTo(EXHAUSTED);
            assertThat(lastRunMeters.get("github.webhook.unrecovered").gauge().value())
                    .isOne();
        }
    }

    @Test
    void shouldStopRequestingAtTheRunBudgetAndRedeliverTheOldestEventsFirst() throws Exception {
        configured();
        // Each reading of the time moves it on by a minute, so the budget ends after the first redelivery.
        GitHubWebhookRedelivery budgeted = new GitHubWebhookRedelivery(
                tokens,
                new MinutePerReading(NOW),
                meters,
                RestClient.builder(),
                github.url("/").toString().replaceAll("/$", ""),
                waits::add);
        respond(
                deliveries(
                        delivery(61, "newer", 10, 503),
                        delivery(62, "oldest", 50, 503),
                        delivery(63, "older", 30, 503)),
                null);
        github.enqueue(new MockResponse.Builder().code(202).build());

        budgeted.redeliverFailed();

        github.takeRequest();
        assertThat(github.takeRequest().getTarget()).isEqualTo("/app/hook/deliveries/62/attempts");
        assertThat(github.getRequestCount())
                .as("the other two wait for the next run")
                .isEqualTo(2);
        assertThat(outcome("requested")).isEqualTo(1);
    }

    @Test
    void shouldOnlyProbeTheReceiverWhenItFailedTheLastRedeliveriesInARow() throws Exception {
        configured();
        respond(deliveries(failedRedeliveries(GitHubWebhookRedelivery.FAILURES_IN_A_ROW + 2, null)), null);
        for (int i = 0; i < GitHubWebhookRedelivery.PROBES; i++)
            github.enqueue(new MockResponse.Builder().code(202).build());

        redelivery.redeliverFailed();

        assertThat(github.getRequestCount())
                .as("the listing and the probes, not the %d due events", GitHubWebhookRedelivery.FAILURES_IN_A_ROW + 2)
                .isEqualTo(1 + GitHubWebhookRedelivery.PROBES);
        github.takeRequest();
        assertThat(github.takeRequest().getTarget())
                .as("the oldest event probes first, with its latest attempt")
                .isEqualTo("/app/hook/deliveries/2011/attempts");
        assertThat(outcome("paused")).isEqualTo(1);
    }

    @Test
    void shouldRedeliverEveryDueEventAgainOnceARedeliverySucceeded() {
        configured();
        int due = GitHubWebhookRedelivery.FAILURES_IN_A_ROW + 2;
        respond(deliveries(failedRedeliveries(due, redelivery(99, "probe", 0, 202))), null);
        for (int i = 0; i < due; i++)
            github.enqueue(new MockResponse.Builder().code(202).build());

        redelivery.redeliverFailed();

        assertThat(github.getRequestCount()).isEqualTo(1 + due);
        assertThat(outcome("paused")).isZero();
        assertThat(waits)
                .as("one pause between two requests")
                .hasSize(due - 1)
                .containsOnly(GitHubWebhookRedelivery.PACE);
    }

    @Test
    void shouldStopTheRunWhenGitHubLimitsTheRateOfRequests() {
        configured();
        respond(deliveries(delivery(71, "first", 30, 503), delivery(72, "second", 20, 503)), null);
        github.enqueue(new MockResponse.Builder().code(429).build());

        redelivery.redeliverFailed();

        assertThat(github.getRequestCount())
                .as("the listing and the limited request")
                .isEqualTo(2);
        assertThat(outcome("refused")).isEqualTo(1);
    }

    /**
     * Events whose original failed and whose one redelivery failed with a 504 from an unready receiver, the
     * newest redelivery first. Originals are 20 minutes old and older, so each event's next redelivery is due.
     */
    private static String[] failedRedeliveries(int events, @Nullable String newest) {
        List<String> attempts = new ArrayList<>();
        if (newest != null) attempts.add(newest);
        for (int i = 0; i < events; i++) {
            attempts.add(redelivery(2000 + i, "event-" + i, 1 + i, 504));
        }
        for (int i = 0; i < events; i++) {
            attempts.add(delivery(1000 + i, "event-" + i, 20 + i, 503));
        }
        return attempts.toArray(String[]::new);
    }

    @Test
    void shouldStopTheRunAfterGitHubRefusedRequestsInARowAndLeaveTheRestDue() {
        configured();
        int due = GitHubWebhookRedelivery.REFUSALS_IN_A_ROW + 3;
        List<String> attempts = new ArrayList<>();
        for (int i = 0; i < due; i++) attempts.add(delivery(80 + i, "refused-" + i, 30 + i, 503));
        respond(deliveries(attempts.toArray(String[]::new)), null);
        for (int i = 0; i < due; i++)
            github.enqueue(new MockResponse.Builder().code(422).build());

        redelivery.redeliverFailed();

        assertThat(github.getRequestCount())
                .as(
                        "the listing and %d refused requests; the other events keep their attempts",
                        GitHubWebhookRedelivery.REFUSALS_IN_A_ROW)
                .isEqualTo(1 + GitHubWebhookRedelivery.REFUSALS_IN_A_ROW);
        assertThat(outcome("refused")).isEqualTo(GitHubWebhookRedelivery.REFUSALS_IN_A_ROW);
    }

    /** A clock that moves on by a minute each time the job reads it. */
    private static final class MinutePerReading extends Clock {

        private Instant next;

        MinutePerReading(Instant start) {
            this.next = start;
        }

        @Override
        public Instant instant() {
            Instant now = next;
            next = next.plus(Duration.ofMinutes(1));
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final int EXHAUSTED = -1;

    /** The slowest recovery on staging, from an event's first failure to its 202, under the earlier schedule. */
    private static final Duration SLOWEST_RECOVERY_MEASURED = Duration.ofSeconds(2208);

    /** When the first run comes after a failed delivery: every minute of one interval. */
    private static final List<Duration> RUN_PHASES = phases();

    private SimpleMeterRegistry lastRunMeters = new SimpleMeterRegistry();

    private static List<Duration> phases() {
        List<Duration> phases = new ArrayList<>();
        for (Duration phase = Duration.ofMinutes(1);
                phase.compareTo(GitHubWebhookRedelivery.INTERVAL) <= 0;
                phase = phase.plusMinutes(1)) {
            phases.add(phase);
        }
        return phases;
    }

    /**
     * Runs the job every interval, from {@code firstRun} after one event's failed delivery, against a GitHub whose
     * delivery log fails every delivery made before {@code stall} ends.
     *
     * @return the attempts the event took, or {@link #EXHAUSTED}
     */
    private int attemptsThrough(Duration stall, Duration firstRun) throws IOException {
        SettableClock clock = new SettableClock(NOW);
        DeliveryLog log = new DeliveryLog(NOW, NOW.plus(stall), clock);
        try (MockWebServer stalled = new MockWebServer()) {
            stalled.setDispatcher(log);
            stalled.start();
            lastRunMeters = new SimpleMeterRegistry();
            GitHubWebhookRedelivery job = new GitHubWebhookRedelivery(
                    tokens,
                    clock,
                    lastRunMeters,
                    RestClient.builder(),
                    stalled.url("/").toString().replaceAll("/$", ""),
                    duration -> {});
            for (Instant run = NOW.plus(firstRun);
                    run.isBefore(NOW.plus(GitHubWebhookRedelivery.WINDOW));
                    run = run.plus(GitHubWebhookRedelivery.INTERVAL)) {
                clock.set(run);
                job.redeliverFailed();
                if (log.delivered()) return log.attempts();
            }
        }
        return EXHAUSTED;
    }

    /** GitHub's delivery log of one event: a redelivery request adds an attempt that fails until the stall ends. */
    private static final class DeliveryLog extends Dispatcher {

        private final Instant stallEnds;
        private final SettableClock clock;
        private final List<String> attempts = new ArrayList<>();
        private boolean delivered;

        DeliveryLog(Instant failedAt, Instant stallEnds, SettableClock clock) {
            this.stallEnds = stallEnds;
            this.clock = clock;
            attempts.add(attemptAt(1, failedAt, false, 503));
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            if ("GET".equals(request.getMethod())) {
                return new MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(deliveries(attempts.toArray(String[]::new)))
                        .build();
            }
            Instant now = clock.instant();
            delivered = !now.isBefore(stallEnds);
            attempts.addFirst(attemptAt(attempts.size() + 1L, now, true, delivered ? 202 : 503));
            return new MockResponse.Builder().code(202).build();
        }

        boolean delivered() {
            return delivered;
        }

        int attempts() {
            return attempts.size();
        }
    }

    /** A clock the scenario moves from run to run; within a run it stands still. */
    private static final class SettableClock extends Clock {

        private Instant now;

        SettableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private void configured() {
        when(tokens.isConfigured()).thenReturn(true);
        when(tokens.generateAppJWT()).thenReturn("app-jwt");
    }

    private double outcome(String outcome) {
        return meters.get("github.webhook.redelivery")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private void respond(String body, @Nullable String link) {
        var response = new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json");
        if (link != null) response.addHeader("Link", link);
        github.enqueue(response.body(body).build());
    }

    private static String deliveries(String... deliveries) {
        return "[" + String.join(",", deliveries) + "]";
    }

    private static String delivery(long id, String guid, int minutesAgo, int statusCode) {
        return attempt(id, guid, minutesAgo, statusCode, false);
    }

    private static String redelivery(long id, String guid, int minutesAgo, int statusCode) {
        return attempt(id, guid, minutesAgo, statusCode, true);
    }

    private static String attemptAt(long id, Instant at, boolean redelivery, int statusCode) {
        return """
                {"id":%d,"guid":"event","delivered_at":"%s","redelivery":%b,"status":"","status_code":%d,
                 "event":"pull_request","action":"opened"}""".formatted(id, at, redelivery, statusCode);
    }

    private static String attempt(long id, String guid, int minutesAgo, int statusCode, boolean redelivery) {
        return """
                {"id":%d,"guid":"%s","delivered_at":"%s","redelivery":%b,"status":"","status_code":%d,
                 "event":"pull_request","action":"opened"}""".formatted(id, guid, NOW.minus(Duration.ofMinutes(minutesAgo)), redelivery, statusCode);
    }
}
