package de.tum.cit.aet.hephaestus.practices.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.core.settings.spi.UsageFigure;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The anonymous reads of the public usage page (ADR 0153 decisions 10 to 16): off means 404, every read checks the
 * withdrawals, a response may be cached for 5 minutes with a strong ETag, and an error is never cached.
 */
class PublicUsageControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String ADMIN = "mock-jwt-token-for-admin-user";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void releaseTwoPeriods() {
        persistUser("admin");
        release(
                "2025-04-01",
                "{\"status\":\"PUBLISHED\",\"figures\":{\"WORKSPACES\":40,\"REVIEWED_PEOPLE\":1200,"
                        + "\"REVIEWED_WORK\":18400,\"PRACTICE_REVIEWS\":96400,\"FEEDBACK_DELIVERED\":null}}");
        release("2025-10-01", "{\"status\":\"NOT_ENOUGH_ACTIVITY\",\"figures\":{}}");
    }

    private void release(String start, String figures) {
        jdbc.update(
                "INSERT INTO usage_release(period_start,period_end,computed_at,schema_version,figures) VALUES"
                        + " (?::date, ?::date + INTERVAL '6 months', ?::date + INTERVAL '6 months 14 days', 1,"
                        + " ?::jsonb)",
                start,
                start,
                start,
                figures);
    }

    private void turn(boolean on) {
        webTestClient
                .put()
                .uri("/admin/settings/public-usage")
                .headers(headers -> headers.setBearerAuth(ADMIN))
                .bodyValue(Map.of("allowed", on))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    @Test
    void shouldAnswerNotFoundWithoutCachingWhileThePageIsOff() {
        for (String uri : new String[] {"/public/usage", "/public/usage/2025-04"}) {
            webTestClient
                    .get()
                    .uri(uri)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectHeader()
                    .value(HttpHeaders.CACHE_CONTROL, value -> assertThat(value).contains("no-store"))
                    .expectHeader()
                    .doesNotExist(HttpHeaders.ETAG)
                    .expectBody(Void.class);
        }
    }

    @Test
    void shouldDateAReadThatStalledAcrossASwitchOffFromBeforeItReadTheSwitch() throws Exception {
        turn(true);
        var reader = Executors.newSingleThreadExecutor();
        try (Connection hold = dataSource.getConnection()) {
            // The read finds the page on, then waits for the released periods behind this lock.
            hold.setAutoCommit(false);
            try (var lock = hold.createStatement()) {
                lock.execute("LOCK TABLE usage_release IN ACCESS EXCLUSIVE MODE");
            }
            var slow = webTestClient
                    .mutate()
                    .responseTimeout(Duration.ofSeconds(60))
                    .build();
            var read = reader.submit(() -> slow.get()
                    .uri("/public/usage")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .returnResult(Void.class)
                    .getResponseHeaders()
                    .getDate());
            await().atMost(Duration.ofSeconds(30))
                    .until(
                            () -> jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                                            + " AND wait_event_type = 'Lock' AND query LIKE '%usage_release%'",
                                    Long.class),
                            waiting -> waiting != null && waiting > 0);
            var stalled = Instant.now();

            // The page goes off while the read waits, and the read is sent a second later.
            turn(false);
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> Instant.now().getEpochSecond() > stalled.getEpochSecond());
            hold.rollback();

            // Its Date is that of the read of the switch, so no cache keeps it 5 minutes past the switch-off.
            assertThat(Instant.ofEpochMilli(read.get(60, TimeUnit.SECONDS)).getEpochSecond())
                    .isLessThanOrEqualTo(stalled.getEpochSecond());
        } finally {
            reader.shutdownNow();
        }
    }

    @Test
    void shouldSayAStoredWithdrawalIsUnreadableAndStillLetTheAdminTurnThePageOff() {
        turn(true);
        try {
            jdbc.update("UPDATE instance_settings SET public_usage_withdrawn = '[\"2025-04/NO_SUCH_FIGURE\"]'::jsonb"
                    + " WHERE id = 1");

            webTestClient
                    .get()
                    .uri("/admin/settings/public-usage")
                    .headers(headers -> headers.setBearerAuth(ADMIN))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.withdrawalsUnreadable")
                    .isEqualTo(true);
            // A half-year with all five figures shown: the page hides every one of them, on both anonymous routes.
            release(
                    "2024-10-01",
                    "{\"status\":\"PUBLISHED\",\"figures\":{\"WORKSPACES\":50,\"REVIEWED_PEOPLE\":1300,"
                            + "\"REVIEWED_WORK\":19500,\"PRACTICE_REVIEWS\":97700,\"FEEDBACK_DELIVERED\":41200}}");
            var period = read("/public/usage/2024-10");
            assertThat(period.status()).isEqualTo(PublicUsageStatus.NOT_PUBLISHED);
            assertThat(period.figures().workspaces()).isNull();
            assertThat(period.figures().reviewedPeople()).isNull();
            assertThat(period.figures().reviewedWork()).isNull();
            assertThat(period.figures().practiceReviews()).isNull();
            assertThat(period.figures().feedbackDelivered()).isNull();
            assertThat(webTestClient
                            .get()
                            .uri("/public/usage")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(String.class)
                            .returnResult()
                            .getResponseBody())
                    .contains("\"2024-10\"")
                    .doesNotContain("1300")
                    .doesNotContain("19500")
                    .doesNotContain("97700")
                    .doesNotContain("41200")
                    .doesNotContain("\"workspaces\":50");
            // The switch still turns the page off.
            turn(false);

            // The database takes only text as a withdrawal.
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE instance_settings SET public_usage_withdrawn = '[null]'::jsonb WHERE id = 1"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("UPDATE instance_settings SET public_usage_withdrawn = '[]'::jsonb WHERE id = 1");
        }
    }

    @Test
    void shouldServeTheReleasedPeriodsNewestFirstWithAStrongEtag() {
        turn(true);

        var response = webTestClient
                .get()
                .uri("/public/usage")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .value(
                        HttpHeaders.CACHE_CONTROL,
                        value -> assertThat(value)
                                .contains("max-age=300")
                                .contains("must-revalidate")
                                .contains("public"))
                .expectBody(PublicUsageDTO.class)
                .returnResult();
        var body = Objects.requireNonNull(response.getResponseBody());
        assertThat(body.periods()).extracting(PublicUsagePeriodDTO::period).containsExactly("2025-10", "2025-04");
        assertThat(body.periods().get(0).status()).isEqualTo(PublicUsageStatus.NOT_ENOUGH_ACTIVITY);
        var summer = body.periods().get(1);
        assertThat(summer.status()).isEqualTo(PublicUsageStatus.PUBLISHED);
        assertThat(summer.lastDay()).hasToString("2025-09-30");
        assertThat(summer.figures().workspaces()).isEqualTo(40L);
        assertThat(Objects.requireNonNull(summer.figures().reviewedPeople()).people())
                .isEqualTo(1200);
        assertThat(summer.figures().feedbackDelivered()).isNull();

        String etag = Objects.requireNonNull(response.getResponseHeaders().getETag());
        assertThat(etag).startsWith("\"");
        webTestClient
                .get()
                .uri("/public/usage")
                .header(HttpHeaders.IF_NONE_MATCH, etag)
                .exchange()
                .expectStatus()
                .isNotModified()
                .expectBody(Void.class);
    }

    /** The newest audit event so far, so that a test reads only the events it caused. */
    private long auditBoundary() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM auth_event", Long.class));
    }

    private long auditEventsSince(long boundary, String... types) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM auth_event WHERE id > ? AND event_type = ANY (?)", Long.class, boundary, types));
    }

    @Test
    void shouldHideAWithdrawnPeriodOrFigureAtTheNextRead() {
        turn(true);
        long boundary = auditBoundary();
        withdraw(Map.of("period", "2025-04", "figure", UsageFigure.REVIEWED_PEOPLE.name()));
        assertThat(jdbc.queryForObject(
                        "SELECT details::text FROM auth_event WHERE id > ? AND event_type = 'PUBLIC_USAGE_WITHDRAWN'",
                        String.class,
                        boundary))
                .contains("\"period\":\"2025-04\"")
                .contains("\"figure\":\"REVIEWED_PEOPLE\"");

        var period = read("/public/usage/2025-04");
        assertThat(period.status()).isEqualTo(PublicUsageStatus.PUBLISHED);
        assertThat(period.figures().reviewedPeople()).isNull();
        assertThat(period.figures().workspaces()).isEqualTo(40L);

        withdraw(Map.of("period", "2025-04", "wholePeriod", true));

        period = read("/public/usage/2025-04");
        assertThat(period.status()).isEqualTo(PublicUsageStatus.NOT_PUBLISHED);
        assertThat(period.figures().workspaces()).isNull();
        assertThat(period.figures().reviewedWork()).isNull();

        withdraw(Map.of("period", "2025-10", "wholePeriod", true));

        assertThat(read("/public/usage/2025-10").status())
                .as("a period below the floors keeps saying so")
                .isEqualTo(PublicUsageStatus.NOT_ENOUGH_ACTIVITY);
    }

    @Test
    void shouldNotConfirmAnOldEtagAfterAWithdrawalOrSwitchOff() {
        turn(true);
        String before = Objects.requireNonNull(webTestClient
                .get()
                .uri("/public/usage")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class)
                .returnResult()
                .getResponseHeaders()
                .getETag());

        withdraw(Map.of("period", "2025-04", "figure", UsageFigure.WORKSPACES.name()));

        String after = Objects.requireNonNull(webTestClient
                .get()
                .uri("/public/usage")
                .header(HttpHeaders.IF_NONE_MATCH, before)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class)
                .returnResult()
                .getResponseHeaders()
                .getETag());
        assertThat(after).isNotEqualTo(before);

        turn(false);

        webTestClient
                .get()
                .uri("/public/usage")
                .header(HttpHeaders.IF_NONE_MATCH, after)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .value(HttpHeaders.CACHE_CONTROL, value -> assertThat(value).contains("no-store"))
                .expectBody(Void.class);
    }

    @Test
    void shouldNeverCacheARefusedMediaTypeAndValidateHeadLikeGet() {
        turn(true);
        webTestClient
                .get()
                .uri("/public/usage")
                .accept(MediaType.APPLICATION_XML)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_ACCEPTABLE)
                .expectHeader()
                .value(HttpHeaders.CACHE_CONTROL, value -> assertThat(value).contains("no-store"))
                .expectBody(Void.class);

        String etag = Objects.requireNonNull(webTestClient
                .get()
                .uri("/public/usage")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class)
                .returnResult()
                .getResponseHeaders()
                .getETag());
        webTestClient
                .head()
                .uri("/public/usage")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ETAG, etag)
                .expectBody(Void.class);
        webTestClient
                .head()
                .uri("/public/usage")
                .header(HttpHeaders.IF_NONE_MATCH, etag)
                .exchange()
                .expectStatus()
                .isNotModified()
                .expectBody(Void.class);
        webTestClient
                .get()
                .uri("/public/usage")
                .header(HttpHeaders.IF_MATCH, "\"stale\"")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.PRECONDITION_FAILED)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(412);
        // A stale If-Match is a failed precondition, even with a matching If-None-Match, and no cache keeps it.
        for (var method : new HttpMethod[] {HttpMethod.GET, HttpMethod.HEAD}) {
            webTestClient
                    .method(method)
                    .uri("/public/usage")
                    .header(HttpHeaders.IF_MATCH, "\"stale\"")
                    .header(HttpHeaders.IF_NONE_MATCH, etag)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(HttpStatus.PRECONDITION_FAILED)
                    .expectHeader()
                    .value(HttpHeaders.CACHE_CONTROL, value -> assertThat(value).contains("no-store"))
                    .expectBody(Void.class);
        }
    }

    @Test
    void shouldSendTheBytesThatItsEtagNamesWhateverCharsetAcceptAsks() throws Exception {
        turn(true);
        var result = webTestClient
                .get()
                .uri("/public/usage")
                .header(HttpHeaders.ACCEPT, "application/json;charset=UTF-16")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(byte[].class)
                .returnResult();
        byte[] bytes = Objects.requireNonNull(result.getResponseBody());
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(result.getResponseHeaders().getETag()).isEqualTo("\"" + digest + "\"");
        assertThat(new String(bytes, StandardCharsets.UTF_8)).startsWith("{\"periods\"");
    }

    @Test
    void shouldWithdrawWhileAReleaseHoldsItsLock() throws Exception {
        turn(true);
        var held = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var release = Executors.newSingleThreadExecutor();
        try {
            // A release that counts for a long time holds the advisory lock in its transaction.
            var holder = release.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject(
                        "SELECT 1 FROM pg_advisory_xact_lock(hashtext('hephaestus.public-usage'))", Integer.class);
                held.countDown();
                try {
                    done.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            withdraw(Map.of("period", "2025-04", "figure", UsageFigure.REVIEWED_WORK.name()));

            assertThat(read("/public/usage/2025-04").figures().reviewedWork()).isNull();
            done.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            done.countDown();
            release.shutdownNow();
        }
    }

    @Test
    void shouldAnswerNotFoundForAPeriodThatIsNotReleased() {
        turn(true);
        for (String period : new String[] {"2024-10", "2025-05", "next"}) {
            webTestClient
                    .get()
                    .uri("/public/usage/{period}", period)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectHeader()
                    .value(HttpHeaders.CACHE_CONTROL, value -> assertThat(value).contains("no-store"))
                    .expectBody(Void.class);
        }
    }

    @Test
    void shouldLetOnlyAnInstanceAdminChangeThePage() {
        long boundary = auditBoundary();
        webTestClient
                .put()
                .uri("/admin/settings/public-usage")
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of("allowed", true))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        webTestClient
                .post()
                .uri("/admin/settings/public-usage/withdrawals")
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of("period", "2025-04", "wholePeriod", true))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        webTestClient
                .post()
                .uri("/admin/settings/public-usage/withdrawals")
                .headers(headers -> headers.setBearerAuth(ADMIN))
                .bodyValue(Map.of("period", "2025-05"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        // A period that is not released cannot be withdrawn, so a typo cannot hide a future release for good.
        webTestClient
                .post()
                .uri("/admin/settings/public-usage/withdrawals")
                .headers(headers -> headers.setBearerAuth(ADMIN))
                .bodyValue(Map.of("period", "2026-04", "wholePeriod", true))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        // A misspelled figure names no scope, so it cannot withdraw the whole period.
        webTestClient
                .post()
                .uri("/admin/settings/public-usage/withdrawals")
                .headers(headers -> headers.setBearerAuth(ADMIN))
                .bodyValue(Map.of("period", "2025-04", "figuer", "REVIEWED_PEOPLE"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM instance_settings WHERE public_usage_withdrawn <> '[]'::jsonb",
                        Long.class))
                .isZero();
        assertThat(auditEventsSince(boundary, "PUBLIC_USAGE_CHANGED", "PUBLIC_USAGE_WITHDRAWN"))
                .isZero();
        turn(true);
        assertThat(auditEventsSince(boundary, "PUBLIC_USAGE_CHANGED")).isOne();
    }

    private void withdraw(Map<String, Object> body) {
        webTestClient
                .post()
                .uri("/admin/settings/public-usage/withdrawals")
                .headers(headers -> headers.setBearerAuth(ADMIN))
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    private PublicUsagePeriodDTO read(String uri) {
        return Objects.requireNonNull(webTestClient
                .get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PublicUsagePeriodDTO.class)
                .returnResult()
                .getResponseBody());
    }
}
