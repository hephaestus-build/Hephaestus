package de.tum.cit.aet.hephaestus.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.consent.ConsentService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.nativesession.NativeSessionService;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.NativeSessionQuery;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The push outbox against PostgreSQL and a stand-in Expo push service: window coalescing, the checks made
 * at send time, tickets, receipts and retries. Proves nothing about Expo, APNs or FCM themselves.
 */
class PushDispatcherIntegrationTest extends RealAuthIntegrationTest {

    private static final long WORKSPACE = 7001L;
    private static final long RECIPIENT = 9001L;

    @Autowired
    private PushNotificationRepository notificationRepository;

    @Autowired
    private PushDeviceRepository deviceRepository;

    @Autowired
    private PushDeviceService deviceService;

    @Autowired
    private NativeSessionService nativeSessionService;

    @Autowired
    private NativeSessionQuery nativeSessionQuery;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockWebServer expo;
    private AccountWorkspaceMembershipQuery memberships;
    private boolean member;
    private PushDispatcher dispatcher;

    private record SignedIn(long accountId, UUID sessionId, String refreshToken, String accessToken) {}

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void startExpo() throws Exception {
        expo = new MockWebServer();
        expo.start();
        memberships = mock(AccountWorkspaceMembershipQuery.class);
        member = true;
        PushProperties properties =
                new PushProperties("test-expo-token", URI.create(expo.url("/").toString()));
        WorkspaceSummaryQuery workspaces = mock(WorkspaceSummaryQuery.class);
        when(workspaces.findById(anyLong()))
                .thenAnswer(call -> Optional.of(
                        new WorkspaceSummaryQuery.WorkspaceSummary(call.getArgument(0), "team-ws", "Team")));
        dispatcher = new PushDispatcher(
                notificationRepository,
                deviceRepository,
                new ExpoPushClient(properties),
                properties,
                nativeSessionQuery,
                memberships,
                (workspaceId, userId) -> member,
                workspaces,
                Clock.systemUTC());
    }

    @AfterEach
    void stopExpo() {
        expo.close();
    }

    @Test
    void shouldCoalesceABurstIntoOneNotificationPerDeviceAndWindow() {
        SignedIn account = signIn("push-burst");
        registerDevice(account, "installation-burst-0001", "ExponentPushToken[burst-one]");
        registerDevice(account, "installation-burst-0002", "ExponentPushToken[burst-two]");
        Instant window = Instant.now().truncatedTo(ChronoUnit.HOURS);

        for (int i = 0; i < 3; i++) {
            notificationRepository.enqueueForAccount(
                    WORKSPACE, RECIPIENT, account.accountId(), "PRACTICE_FEEDBACK", window, Instant.now());
        }

        assertThat(rowsFor(account)).isEqualTo(2);
    }

    @Test
    void shouldSendAContentFreeNudgeAndRecordTheTicketWhenEveryCheckPasses() throws Exception {
        SignedIn account = signIn("push-happy");
        registerDevice(account, "installation-happy-0001", "ExponentPushToken[happy]");
        linkRecipient(account);
        enqueue(account);
        expo.enqueue(json("{\"data\":[{\"status\":\"ok\",\"id\":\"ticket-1\"}]}"));

        dispatcher.sendDue();

        RecordedRequest request = Objects.requireNonNull(expo.takeRequest(5, TimeUnit.SECONDS));
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer test-expo-token");
        String body = Objects.requireNonNull(request.getBody()).utf8();
        assertThat(body)
                .contains("ExponentPushToken[happy]", "\"workspaceSlug\":\"team-ws\"", "practice-feedback")
                .contains("New practice feedback")
                .contains("\"nativeSessionId\":\"" + account.sessionId() + "\"");
        PushNotification row = onlyRow(account);
        assertThat(row.getState()).isEqualTo(PushNotification.State.SENT);
        assertThat(row.getTicketId()).isEqualTo("ticket-1");
    }

    @Test
    void shouldSuppressWhenTheSessionEndedBeforeSending() {
        SignedIn account = signIn("push-signed-out");
        registerDevice(account, "installation-out-0001", "ExponentPushToken[signed-out]");
        linkRecipient(account);
        enqueue(account);
        nativeSessionService.logout(account.refreshToken());

        dispatcher.sendDue();

        assertThat(expo.getRequestCount()).isZero();
        assertThat(onlyRow(account).getState()).isEqualTo(PushNotification.State.SUPPRESSED);
    }

    @Test
    void shouldSuppressWhenTheDeveloperLeftTheWorkspace() {
        SignedIn account = signIn("push-left");
        registerDevice(account, "installation-left-0001", "ExponentPushToken[left]");
        linkRecipient(account);
        enqueue(account);
        member = false;

        dispatcher.sendDue();

        assertThat(expo.getRequestCount()).isZero();
        PushNotification row = onlyRow(account);
        assertThat(row.getState()).isEqualTo(PushNotification.State.SUPPRESSED);
        assertThat(row.getFailureReason()).isEqualTo("not_a_member");
    }

    @Test
    void shouldForgetTheDeviceWhenExpoSaysItIsNoLongerRegistered() {
        SignedIn account = signIn("push-gone");
        registerDevice(account, "installation-gone-0001", "ExponentPushToken[gone]");
        linkRecipient(account);
        enqueue(account);
        expo.enqueue(
                json(
                        "{\"data\":[{\"status\":\"error\",\"message\":\"gone\",\"details\":{\"error\":\"DeviceNotRegistered\"}}]}"));

        dispatcher.sendDue();

        assertThat(deviceRepository.findByInstallationId("installation-gone-0001"))
                .isEmpty();
    }

    @Test
    void shouldReleaseForALaterAttemptWhenExpoIsUnavailable() {
        SignedIn account = signIn("push-retry");
        registerDevice(account, "installation-retry-0001", "ExponentPushToken[retry]");
        linkRecipient(account);
        enqueue(account);
        expo.enqueue(
                new MockResponse.Builder().code(503).body("{\"errors\":[]}").build());

        dispatcher.sendDue();

        assertThat(expo.getRequestCount()).isEqualTo(1);
        PushNotification row = onlyRow(account);
        assertThat(row.getState()).isEqualTo(PushNotification.State.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isAfter(Instant.now());
    }

    @Test
    void shouldLetOnlyOneSenderClaimARow() {
        SignedIn account = signIn("push-claim");
        registerDevice(account, "installation-claim-0001", "ExponentPushToken[claim]");
        enqueue(account);
        PushNotification row = onlyRow(account);
        Instant now = Instant.now();

        int first = notificationRepository.claim(row.getId(), WORKSPACE, now, now.plusSeconds(120), row.getVersion());
        int second = notificationRepository.claim(row.getId(), WORKSPACE, now, now.plusSeconds(120), row.getVersion());

        assertThat(first + second).isEqualTo(1);
    }

    @Test
    void shouldSettleASentNotificationFromItsReceipt() {
        SignedIn account = signIn("push-receipt");
        registerDevice(account, "installation-receipt-0001", "ExponentPushToken[receipt]");
        enqueue(account);
        jdbcTemplate.update(
                "UPDATE push_notification SET state = 'SENT', ticket_id = 'ticket-r', sent_at = now() - interval '20 minutes'"
                        + " WHERE workspace_id = ?",
                WORKSPACE);
        expo.enqueue(json("{\"data\":{\"ticket-r\":{\"status\":\"ok\"}}}"));

        dispatcher.settleReceipts();

        assertThat(onlyRow(account).getState()).isEqualTo(PushNotification.State.DELIVERED);
    }

    @Test
    void shouldRejectAStaleSenderCompletionAfterItsLeaseWasReclaimed() {
        SignedIn account = signIn("push-stale");
        registerDevice(account, "installation-stale-0001", "ExponentPushToken[stale]");
        enqueue(account);
        PushNotification stale = onlyRow(account);
        Instant now = Instant.now();
        assertThat(notificationRepository.claim(
                        stale.getId(), WORKSPACE, now, now.plusSeconds(120), stale.getVersion()))
                .isEqualTo(1);
        stale.setVersion(stale.getVersion() + 1);
        PushNotification newer = onlyRow(account);
        assertThat(notificationRepository.claim(
                        newer.getId(), WORKSPACE, now.plusSeconds(121), now.plusSeconds(241), newer.getVersion()))
                .isEqualTo(1);
        newer.setVersion(newer.getVersion() + 1);
        newer.setState(PushNotification.State.SENT);
        newer.setTicketId("newer-ticket");
        notificationRepository.saveAndFlush(newer);

        stale.setState(PushNotification.State.FAILED);
        assertThatThrownBy(() -> notificationRepository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(onlyRow(account).getTicketId()).isEqualTo("newer-ticket");
    }

    @Test
    void shouldRetryPerMessageRateLimitsAndStopAtTheAttemptLimit() {
        SignedIn account = signIn("push-ticket-rate");
        registerDevice(account, "installation-ticket-rate", "ExponentPushToken[ticket-rate]");
        linkRecipient(account);
        enqueue(account);
        UUID id = onlyRow(account).getId();
        for (int attempt = 1; attempt <= 5; attempt++) {
            jdbcTemplate.update("UPDATE push_notification SET next_attempt_at = now() WHERE id = ?", id);
            expo.enqueue(json("{\"data\":[{\"status\":\"error\",\"details\":{\"error\":\"MessageRateExceeded\"}}]}"));
            dispatcher.sendDue();
            assertThat(onlyRow(account).getState())
                    .isEqualTo(attempt == 5 ? PushNotification.State.FAILED : PushNotification.State.PENDING);
        }
        assertThat(onlyRow(account).getAttempts()).isEqualTo(5);
    }

    @Test
    void shouldRetryARateLimitedReceiptWithoutKeepingItsOldTicket() {
        SignedIn account = signIn("push-receipt-rate");
        registerDevice(account, "installation-receipt-rate", "ExponentPushToken[receipt-rate]");
        enqueue(account);
        UUID id = onlyRow(account).getId();
        jdbcTemplate.update(
                "UPDATE push_notification SET state = 'SENT', attempts = 1, ticket_id = 'rate-ticket',"
                        + " sent_at = now() - interval '20 minutes' WHERE id = ?",
                id);
        expo.enqueue(json(
                "{\"data\":{\"rate-ticket\":{\"status\":\"error\",\"details\":{\"error\":\"MessageRateExceeded\"}}}}"));
        dispatcher.settleReceipts();
        PushNotification row = onlyRow(account);
        assertThat(row.getState()).isEqualTo(PushNotification.State.PENDING);
        assertThat(row.getTicketId()).isNull();
        assertThat(row.getNextAttemptAt()).isAfter(Instant.now());
    }

    @Test
    void shouldKeepANewerRegistrationWhenAnOldReceiptRejectsItsDevice() {
        SignedIn account = signIn("push-new-registration");
        String installation = "installation-new-registration";
        registerDevice(account, installation, "ExponentPushToken[old-token]");
        linkRecipient(account);
        enqueue(account);
        expo.enqueue(json("{\"data\":[{\"status\":\"ok\",\"id\":\"old-ticket\"}]}"));
        dispatcher.sendDue();
        UUID id = onlyRow(account).getId();
        deviceService.register(
                account.accountId(),
                account.sessionId(),
                installation,
                "ExponentPushToken[new-token]",
                PushDevice.Platform.IOS);
        jdbcTemplate.update("UPDATE push_notification SET sent_at = now() - interval '20 minutes' WHERE id = ?", id);
        expo.enqueue(json(
                "{\"data\":{\"old-ticket\":{\"status\":\"error\",\"details\":{\"error\":\"DeviceNotRegistered\"}}}}"));
        dispatcher.settleReceipts();
        assertThat(deviceRepository
                        .findByInstallationId(installation)
                        .orElseThrow()
                        .getExpoPushToken())
                .isEqualTo("ExponentPushToken[new-token]");
    }

    @Test
    void shouldRegisterTheSameInstallationConcurrentlyWithoutAUniqueConstraintFailure() throws Exception {
        SignedIn account = signIn("push-register-race");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Void> register = () -> {
                start.await();
                deviceService.register(
                        account.accountId(),
                        account.sessionId(),
                        "installation-register-race",
                        "ExponentPushToken[registration-race]",
                        PushDevice.Platform.ANDROID);
                return null;
            };
            var first = executor.submit(register);
            var second = executor.submit(register);
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertThat(deviceRepository
                        .findByInstallationId("installation-register-race")
                        .orElseThrow()
                        .getAccountId())
                .isEqualTo(account.accountId());
    }

    @Test
    void shouldHideAndPreserveAnotherAccountsInstallationWhenReadingOrUnregistering() {
        SignedIn owner = signIn("push-device-owner");
        SignedIn other = signIn("push-device-other");
        String installation = UUID.randomUUID().toString();
        registerDevice(owner, installation, "ExpoPushToken[ownership123]");

        webTestClient
                .get()
                .uri("/user/push-devices/" + installation)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.registered")
                .isEqualTo(false);
        webTestClient
                .delete()
                .uri("/user/push-devices/" + installation)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
        assertThat(deviceRepository.findByInstallationId(installation)).isPresent();
        webTestClient
                .get()
                .uri("/user/push-devices/" + installation)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.registered")
                .isEqualTo(true);
        webTestClient
                .delete()
                .uri("/user/push-devices/" + installation)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
        assertThat(deviceRepository.findByInstallationId(installation)).isEmpty();
    }

    @Test
    void shouldNotInheritAnotherSessionsPushChoiceWhenSigningInAgain() {
        SignedIn first = signIn("push-session-choice");
        String installation = UUID.randomUUID().toString();
        registerDevice(first, installation, "ExpoPushToken[sessionchoice123]");
        String verifier = UUID.randomUUID() + "-" + UUID.randomUUID();
        String code = nativeSessionService.createHandoff(
                first.accountId(), challenge(verifier), Instant.now().plus(7, ChronoUnit.DAYS), Instant.now());
        NativeSessionService.NativeTokens next =
                nativeSessionService.exchange(code, verifier, null).orElseThrow();

        webTestClient
                .get()
                .uri("/user/push-devices/" + installation)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + next.accessToken())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.registered")
                .isEqualTo(false);
        assertThat(deviceRepository.findByInstallationId(installation)).isPresent();
    }

    @Test
    void shouldRefuseRegistrationWhenTheInstanceHasNoPushCredentials() {
        SignedIn owner = signIn("push-device-unavailable");
        String installation = UUID.randomUUID().toString();
        webTestClient
                .put()
                .uri("/user/push-devices/" + installation)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("expoPushToken", "ExpoPushToken[unavailable123]", "platform", "IOS"))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(deviceRepository.findByInstallationId(installation)).isEmpty();
    }

    @Test
    void shouldRequireAuthenticationToReadOrRemoveAnInstallation() {
        String installation = UUID.randomUUID().toString();
        webTestClient
                .get()
                .uri("/user/push-devices/" + installation)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        webTestClient
                .delete()
                .uri("/user/push-devices/" + installation)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    private SignedIn signIn(String name) {
        Account account = accountRepository.save(new Account(name));
        long accountId = Objects.requireNonNull(account.getId());
        consentService.completeFirstLogin(
                accountId,
                new ConsentService.FirstLoginConsentDTO(
                        consentService.status(accountId).noticeVersion(), true, null, null));
        String verifier = UUID.randomUUID() + "-" + UUID.randomUUID();
        String code = nativeSessionService.createHandoff(
                accountId, challenge(verifier), Instant.now().plus(7, ChronoUnit.DAYS), Instant.now());
        NativeSessionService.NativeTokens tokens =
                nativeSessionService.exchange(code, verifier, null).orElseThrow();
        String sid = com.jayway.jsonpath.JsonPath.read(
                new String(Base64.getUrlDecoder().decode(tokens.accessToken().split("\\.")[1]), StandardCharsets.UTF_8),
                "$.sid");
        return new SignedIn(accountId, UUID.fromString(sid), tokens.refreshToken(), tokens.accessToken());
    }

    private void registerDevice(SignedIn account, String installationId, String token) {
        deviceRepository.save(new PushDevice(
                UUID.randomUUID(),
                account.accountId(),
                installationId,
                token,
                PushDevice.Platform.IOS,
                account.sessionId()));
    }

    private void linkRecipient(SignedIn account) {
        when(memberships.activeAccountIdForMember(WORKSPACE, RECIPIENT))
                .thenReturn(Optional.of(account.accountId()));
    }

    private void enqueue(SignedIn account) {
        notificationRepository.enqueueForAccount(
                WORKSPACE,
                RECIPIENT,
                account.accountId(),
                "PRACTICE_FEEDBACK",
                Instant.now().truncatedTo(ChronoUnit.HOURS),
                Instant.now());
    }

    private int rowsFor(SignedIn account) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM push_notification n JOIN push_device d ON d.id = n.push_device_id"
                        + " WHERE d.account_id = ?",
                Integer.class,
                account.accountId());
        return Objects.requireNonNull(count);
    }

    private PushNotification onlyRow(SignedIn account) {
        UUID id = jdbcTemplate.queryForObject(
                "SELECT n.id FROM push_notification n JOIN push_device d ON d.id = n.push_device_id"
                        + " WHERE d.account_id = ?",
                UUID.class,
                account.accountId());
        return notificationRepository.findById(Objects.requireNonNull(id)).orElseThrow();
    }

    private static MockResponse json(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
