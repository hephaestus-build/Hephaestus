package de.tum.cit.aet.hephaestus.notification.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestSubscriptions;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.NotificationPreferencesExportQuery.Preferences;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

class NotificationSubscriptionIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private NotificationSubscriptionService subscriptions;

    @Autowired
    private NotificationSubscriptionRepository repository;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private NotificationAccountErasureAdapter erasure;

    @Autowired
    private NotificationPreferencesExportAdapter export;

    @Autowired
    private WebTestClient web;

    @Autowired
    private WeeklyDigestSubscriptions weeklyDigests;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void shouldReadAndUpdateOnlyTheAuthenticatedAccountsChoicesWithoutExposingTokens() {
        long first = account();
        long second = account();
        update(second, false, true);
        String path = "/user/notification-preferences?accountId=" + second;
        var initial = web.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + first))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.workspaceAlerts")
                .isEqualTo(false)
                .jsonPath("$.weeklyDigest")
                .isEqualTo(false)
                .jsonPath("$.deliveryConfigured")
                .isEqualTo(false)
                .returnResult();
        String etag = Objects.requireNonNull(initial.getResponseHeaders().getETag());

        var updated = web.put()
                .uri(path)
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + first))
                .header(HttpHeaders.IF_MATCH, etag)
                .bodyValue(new UpdateNotificationPreferencesDTO(false, true, false, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult();

        String token = subscriptions
                .unsubscribeToken(first, NotificationSubscriptionKind.WORKSPACE_ALERTS)
                .orElseThrow();
        assertThat(Objects.requireNonNull(updated.getResponseBody())).doesNotContain(token, "unsubscribe");
        assertThat(mapper.writeValueAsString(export.preferences(first))).doesNotContain(token, "unsubscribe");
        assertThat(updated.getResponseHeaders().getETag()).isNotBlank().isNotEqualTo(etag);
        assertThat(subscriptions.isEnabled(first, NotificationSubscriptionKind.WORKSPACE_ALERTS))
                .isTrue();
        assertThat(subscriptions.isEnabled(second, NotificationSubscriptionKind.WORKSPACE_ALERTS))
                .isFalse();
        web.get()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + second))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.weeklyDigest")
                .isEqualTo(true)
                .jsonPath("$.workspaceAlerts")
                .isEqualTo(false)
                .jsonPath("$.unsubscribeToken")
                .doesNotExist();
    }

    @Test
    void shouldRequireAuthenticationForReadingAndChangingPreferences() {
        web.get()
                .uri("/user/notification-preferences")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        String csrf = TestAuthUtils.fetchCsrfToken(web);
        web.put()
                .uri("/user/notification-preferences")
                .headers(TestAuthUtils.withCsrf(csrf))
                .header(HttpHeaders.IF_MATCH, "*")
                .bodyValue(new UpdateNotificationPreferencesDTO(false, true, false, null))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldRejectMissingAndStaleHttpPreconditionsWithoutOverwritingChoices() {
        long account = account();
        var enable = new UpdateNotificationPreferencesDTO(false, true, false, null);
        String etag = Objects.requireNonNull(web.get()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getETag());
        web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .bodyValue(enable)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
                .expectBody(Void.class);
        assertThat(repository.findAllByAccountIdOrderByKind(account)).isEmpty();
        web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .header(HttpHeaders.IF_MATCH, etag)
                .bodyValue(enable)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .header(HttpHeaders.IF_MATCH, etag)
                .bodyValue(new UpdateNotificationPreferencesDTO(false, false, false, null))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.PRECONDITION_FAILED)
                .expectBody(Void.class);

        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.WORKSPACE_ALERTS))
                .isTrue();
    }

    @Test
    void shouldRefuseAdministratorSubscriptionsForAnOrdinaryAccount() {
        long account = account();
        web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .header(HttpHeaders.IF_MATCH, subscriptions.get(account).etag())
                .bodyValue(new UpdateNotificationPreferencesDTO(true, false, false, null))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(repository.findAllByAccountIdOrderByKind(account)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRejectMissingOrNullChoicesWithoutOverwritingSubscriptions(boolean explicitNull) {
        long account = account();
        update(account, true, true);
        var before = subscriptions.get(account);
        var body = mapper.createObjectNode().put("productFeedback", false).put("weeklyDigest", true);
        if (explicitNull) body.putNull("workspaceAlerts");

        web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .header(HttpHeaders.IF_MATCH, before.etag())
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);

        assertThat(subscriptions.get(account)).isEqualTo(before);
    }

    @Test
    void shouldRefuseEmailOptInUntilTheCurrentAccountsContactIsVerified() {
        long account = account();
        var unverified = accounts.findById(account).orElseThrow();
        unverified.setPrimaryEmailVerifiedAt(null);
        accounts.saveAndFlush(unverified);
        var initial = web.get()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.emailAvailable")
                .isEqualTo(false)
                .returnResult();

        web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .header(
                        HttpHeaders.IF_MATCH,
                        Objects.requireNonNull(initial.getResponseHeaders().getETag()))
                .bodyValue(new UpdateNotificationPreferencesDTO(false, true, false, null))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Void.class);

        assertThat(repository.findAllByAccountIdOrderByKind(account)).isEmpty();
    }

    @Test
    void shouldAllowDisablingOneSubscriptionAfterContactLossButRejectReEnablingIt() {
        long account = account();
        update(account, true, true);
        var unverified = accounts.findById(account).orElseThrow();
        unverified.setPrimaryEmailVerifiedAt(null);
        accounts.saveAndFlush(unverified);

        var saved = web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .header(HttpHeaders.IF_MATCH, subscriptions.get(account).etag())
                .bodyValue(new UpdateNotificationPreferencesDTO(false, false, true, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.emailAvailable")
                .isEqualTo(false)
                .jsonPath("$.workspaceAlerts")
                .isEqualTo(false)
                .jsonPath("$.weeklyDigest")
                .isEqualTo(true)
                .returnResult();

        web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .header(
                        HttpHeaders.IF_MATCH,
                        Objects.requireNonNull(saved.getResponseHeaders().getETag()))
                .bodyValue(new UpdateNotificationPreferencesDTO(false, true, true, null))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Void.class);
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.WORKSPACE_ALERTS))
                .isFalse();
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.MENTOR_DIGEST))
                .isTrue();
    }

    @Test
    void shouldRequireExplicitAnonymousPostAndLeaveOtherSubscriptionsEnabled() {
        long account = account();
        update(account, true, true);
        String token = subscriptions
                .unsubscribeToken(account, NotificationSubscriptionKind.WORKSPACE_ALERTS)
                .orElseThrow();
        String path = "/notifications/unsubscribe/" + token;
        web.get().uri(path).exchange().expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.WORKSPACE_ALERTS))
                .isTrue();
        web.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("List-Unsubscribe=invalid")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.WORKSPACE_ALERTS))
                .isTrue();
        for (String candidate : List.of(token, token, UUID.randomUUID().toString(), "invalid")) {
            web.post()
                    .uri("/notifications/unsubscribe/" + candidate)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("List-Unsubscribe=One-Click")
                    .exchange()
                    .expectStatus()
                    .isNoContent()
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store")
                    .expectBody(Void.class);
        }
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.WORKSPACE_ALERTS))
                .isFalse();
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.MENTOR_DIGEST))
                .isTrue();
    }

    @Test
    void shouldDefaultToReporterUpdatesWithoutCreatingRows() {
        long account = account();
        Account unverified = accounts.findById(account).orElseThrow();
        unverified.setPrimaryEmailVerifiedAt(null);
        accounts.saveAndFlush(unverified);
        var preferences = subscriptions.get(account);
        assertThat(preferences.productFeedback()).isFalse();
        assertThat(preferences.workspaceAlerts()).isFalse();
        assertThat(preferences.weeklyDigest()).isFalse();
        assertThat(preferences.productFeedbackUpdates()).isTrue();
        assertThat(preferences.emailAvailable()).isFalse();
        assertThat(repository.findAllByAccountIdOrderByKind(account)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(NotificationSubscriptionKind.class)
    void shouldKeepOldRequestsCancelledAfterReSubscribingToAnyOptionalKind(NotificationSubscriptionKind kind) {
        long account = account();
        var choices = new UpdateNotificationPreferencesDTO(
                kind == NotificationSubscriptionKind.PRODUCT_FEEDBACK,
                kind == NotificationSubscriptionKind.WORKSPACE_ALERTS,
                kind == NotificationSubscriptionKind.MENTOR_DIGEST,
                kind == NotificationSubscriptionKind.PRODUCT_FEEDBACK_UPDATES);
        subscriptions.update(
                account,
                choices,
                EntityTagPrecondition.parse(subscriptions.get(account).etag()),
                true);
        var originalRequest = Instant.now();
        String token =
                subscriptions.unsubscribeToken(account, kind, originalRequest).orElseThrow();
        subscriptions.unsubscribe(token);
        subscriptions.update(
                account,
                choices,
                EntityTagPrecondition.parse(subscriptions.get(account).etag()),
                true);
        assertThat(subscriptions.unsubscribeToken(account, kind, originalRequest))
                .isEmpty();
        assertThat(subscriptions.unsubscribeToken(account, kind, Instant.now())).contains(token);
    }

    @Test
    void shouldUnsubscribeIdempotentlyWithoutChangingTokenOrOtherChoices() {
        long account = account();
        update(account, true, true);
        String token = subscriptions
                .unsubscribeToken(account, NotificationSubscriptionKind.WORKSPACE_ALERTS)
                .orElseThrow();
        subscriptions.unsubscribe("invalid");
        subscriptions.unsubscribe(UUID.randomUUID().toString());
        subscriptions.unsubscribe(token);
        subscriptions.unsubscribe(token);
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.WORKSPACE_ALERTS))
                .isFalse();
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.MENTOR_DIGEST))
                .isTrue();
        update(account, true, true);
        assertThat(subscriptions.unsubscribeToken(account, NotificationSubscriptionKind.WORKSPACE_ALERTS))
                .contains(token);
    }

    @Test
    void shouldExportOnlyChoicesAndEraseOnlyTheTargetAccountsSubscriptions() {
        long first = account();
        long second = account();
        update(first, true, false);
        update(second, false, true);
        assertThat(export.preferences(first)).isEqualTo(new Preferences(false, true, false, true));
        String token = subscriptions
                .unsubscribeToken(first, NotificationSubscriptionKind.WORKSPACE_ALERTS)
                .orElseThrow();
        erasure.eraseAccount(first);
        subscriptions.unsubscribe(token);
        assertThat(repository.findAllByAccountIdOrderByKind(first)).isEmpty();
        assertThat(subscriptions.isEnabled(second, NotificationSubscriptionKind.MENTOR_DIGEST))
                .isTrue();
    }

    @Test
    void shouldTurnTheWeeklyDigestOnAndOffThroughItsPortWhenTheAccountHasNoEmailAddress() {
        var row = new Account("Digest " + UUID.randomUUID());
        long account = Objects.requireNonNull(accounts.save(row).getId());

        var turnedOn = subscriptions.update(
                account,
                new UpdateNotificationPreferencesDTO(false, false, true, null),
                EntityTagPrecondition.parse(subscriptions.get(account).etag()),
                false);

        assertThat(turnedOn.weeklyDigest()).isTrue();
        assertThat(turnedOn.emailAvailable()).isFalse();
        assertThat(weeklyDigests.subscribedAccountIds()).contains(account);
        assertThat(weeklyDigests.enabledSince(account)).isPresent();
        assertThat(export.preferences(account).weeklyDigest()).isTrue();

        weeklyDigests.turnOff(account);

        assertThat(subscriptions.get(account).weeklyDigest()).isFalse();
        assertThat(weeklyDigests.enabledSince(account)).isEmpty();
        assertThat(weeklyDigests.subscribedAccountIds()).doesNotContain(account);
    }

    @Test
    void shouldKeepTheWeeklyDigestWhenTheRequestDoesNotChooseIt() {
        long account = account();
        subscriptions.update(
                account,
                new UpdateNotificationPreferencesDTO(false, false, true, null),
                EntityTagPrecondition.parse(subscriptions.get(account).etag()),
                false);
        var body = mapper.createObjectNode().put("productFeedback", false).put("workspaceAlerts", true);

        web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .header(HttpHeaders.IF_MATCH, subscriptions.get(account).etag())
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.weeklyDigest")
                .isEqualTo(true)
                .jsonPath("$.workspaceAlerts")
                .isEqualTo(true);

        assertThat(repository.findAllByAccountIdOrderByKind(account))
                .filteredOn(row -> row.getKind() == NotificationSubscriptionKind.MENTOR_DIGEST)
                .singleElement()
                .satisfies(row -> assertThat(row.isEnabled()).isTrue());
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.WORKSPACE_ALERTS))
                .isTrue();
    }

    @Test
    void shouldTurnTheWeeklyDigestOffWhenTheRequestChoosesIt() {
        long account = account();
        subscriptions.update(
                account,
                new UpdateNotificationPreferencesDTO(false, false, true, null),
                EntityTagPrecondition.parse(subscriptions.get(account).etag()),
                false);

        web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .header(HttpHeaders.IF_MATCH, subscriptions.get(account).etag())
                .bodyValue(new UpdateNotificationPreferencesDTO(false, false, false, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.weeklyDigest")
                .isEqualTo(false);

        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.MENTOR_DIGEST))
                .isFalse();
    }

    @Test
    void shouldTurnTheWeeklyDigestOffWhenItsEmailUnsubscribeLinkIsUsed() {
        long account = account();
        subscriptions.update(
                account,
                new UpdateNotificationPreferencesDTO(false, false, true, null),
                EntityTagPrecondition.parse(subscriptions.get(account).etag()),
                false);
        String token = subscriptions
                .unsubscribeToken(account, NotificationSubscriptionKind.MENTOR_DIGEST)
                .orElseThrow();

        web.post()
                .uri("/notifications/unsubscribe/{token}", token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("List-Unsubscribe=One-Click")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(subscriptions.get(account).weeklyDigest()).isFalse();
    }

    private void update(long account, boolean alerts, boolean digest) {
        subscriptions.update(
                account,
                new UpdateNotificationPreferencesDTO(false, alerts, digest, null),
                EntityTagPrecondition.parse(subscriptions.get(account).etag()),
                false);
    }

    private long account() {
        var row = new Account("Subscription " + UUID.randomUUID());
        row.setPrimaryEmail(UUID.randomUUID() + "@example.org");
        row.setPrimaryEmailVerifiedAt(Instant.now());
        return Objects.requireNonNull(accounts.save(row).getId());
    }
}
