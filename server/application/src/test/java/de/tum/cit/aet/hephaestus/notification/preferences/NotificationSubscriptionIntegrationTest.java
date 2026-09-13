package de.tum.cit.aet.hephaestus.notification.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
    private org.springframework.test.web.reactive.server.WebTestClient web;

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
                .jsonPath("$.productSurveys")
                .isEqualTo(false)
                .jsonPath("$.researchSurveys")
                .isEqualTo(false)
                .returnResult();
        String etag = Objects.requireNonNull(initial.getResponseHeaders().getETag());

        var updated = web.put()
                .uri(path)
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + first))
                .header(HttpHeaders.IF_MATCH, etag)
                .bodyValue(new UpdateNotificationPreferencesDTO(
                        false, true, false, NotificationEmailFrequency.IMMEDIATE, false, false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult();

        String token = subscriptions
                .unsubscribeToken(first, NotificationSubscriptionKind.PRODUCT_SURVEYS)
                .orElseThrow();
        assertThat(Objects.requireNonNull(updated.getResponseBody())).doesNotContain(token, "unsubscribe");
        assertThat(mapper.writeValueAsString(export.preferences(first))).doesNotContain(token, "unsubscribe");
        assertThat(updated.getResponseHeaders().getETag()).isNotBlank().isNotEqualTo(etag);
        assertThat(subscriptions.isEnabled(first, NotificationSubscriptionKind.PRODUCT_SURVEYS))
                .isTrue();
        assertThat(subscriptions.isEnabled(second, NotificationSubscriptionKind.PRODUCT_SURVEYS))
                .isFalse();
        web.get()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + second))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.researchSurveys")
                .isEqualTo(true)
                .jsonPath("$.productSurveys")
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
                .bodyValue(new UpdateNotificationPreferencesDTO(
                        false, true, false, NotificationEmailFrequency.IMMEDIATE, false, false))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldRejectMissingAndStaleHttpPreconditionsWithoutOverwritingChoices() {
        long account = account();
        var enable = new UpdateNotificationPreferencesDTO(
                false, true, false, NotificationEmailFrequency.IMMEDIATE, false, false);
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
                .bodyValue(new UpdateNotificationPreferencesDTO(
                        false, false, false, NotificationEmailFrequency.IMMEDIATE, false, false))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.PRECONDITION_FAILED)
                .expectBody(Void.class);

        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.PRODUCT_SURVEYS))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRefuseAdministratorSubscriptionsForAnOrdinaryAccount(boolean summary) {
        long account = account();
        web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .header(HttpHeaders.IF_MATCH, subscriptions.get(account).etag())
                .bodyValue(new UpdateNotificationPreferencesDTO(
                        !summary, false, false, NotificationEmailFrequency.IMMEDIATE, false, summary))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(repository.findAllByAccountIdOrderByKind(account)).isEmpty();
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
                .bodyValue(new UpdateNotificationPreferencesDTO(
                        false, true, false, NotificationEmailFrequency.IMMEDIATE, false, false))
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
                .bodyValue(new UpdateNotificationPreferencesDTO(
                        false, false, true, NotificationEmailFrequency.DAILY, false, false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.emailAvailable")
                .isEqualTo(false)
                .jsonPath("$.productSurveys")
                .isEqualTo(false)
                .jsonPath("$.researchSurveys")
                .isEqualTo(true)
                .jsonPath("$.productFeedbackFrequency")
                .isEqualTo("DAILY")
                .returnResult();

        web.put()
                .uri("/user/notification-preferences")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .header(
                        HttpHeaders.IF_MATCH,
                        Objects.requireNonNull(saved.getResponseHeaders().getETag()))
                .bodyValue(new UpdateNotificationPreferencesDTO(
                        false, true, true, NotificationEmailFrequency.DAILY, false, false))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Void.class);
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.PRODUCT_SURVEYS))
                .isFalse();
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.RESEARCH_SURVEYS))
                .isTrue();
    }

    @Test
    void shouldRequireExplicitAnonymousPostAndLeaveOtherSubscriptionsEnabled() {
        long account = account();
        update(account, true, true);
        String token = subscriptions
                .unsubscribeToken(account, NotificationSubscriptionKind.PRODUCT_SURVEYS)
                .orElseThrow();
        String path = "/notifications/unsubscribe/" + token;
        web.get().uri(path).exchange().expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.PRODUCT_SURVEYS))
                .isTrue();
        web.post()
                .uri(path)
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("List-Unsubscribe=invalid")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.PRODUCT_SURVEYS))
                .isTrue();
        for (String candidate :
                java.util.List.of(token, token, UUID.randomUUID().toString(), "invalid")) {
            web.post()
                    .uri("/notifications/unsubscribe/" + candidate)
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("List-Unsubscribe=One-Click")
                    .exchange()
                    .expectStatus()
                    .isNoContent()
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store")
                    .expectBody(Void.class);
        }
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.PRODUCT_SURVEYS))
                .isFalse();
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.RESEARCH_SURVEYS))
                .isTrue();
    }

    @Test
    void shouldDefaultToNoSubscriptionsWithoutCreatingRows() {
        long account = account();
        Account unverified = accounts.findById(account).orElseThrow();
        unverified.setPrimaryEmailVerifiedAt(null);
        accounts.saveAndFlush(unverified);
        var preferences = subscriptions.get(account);
        assertThat(preferences.productFeedback()).isFalse();
        assertThat(preferences.productSurveys()).isFalse();
        assertThat(preferences.researchSurveys()).isFalse();
        assertThat(preferences.emailAvailable()).isFalse();
        assertThat(repository.findAllByAccountIdOrderByKind(account)).isEmpty();
    }

    @Test
    void shouldInvalidateQueuedDigestsAfterOptOutAndReOptIn() {
        long account = account();
        var daily = new UpdateNotificationPreferencesDTO(
                true, false, false, NotificationEmailFrequency.DAILY, false, false);
        subscriptions.update(
                account,
                daily,
                EntityTagPrecondition.parse(subscriptions.get(account).etag()),
                true);
        var from = java.time.Instant.now();
        assertThat(subscriptions.isDigestWindowCurrent(account, from)).isTrue();
        subscriptions.unsubscribe(subscriptions
                .unsubscribeToken(account, NotificationSubscriptionKind.PRODUCT_FEEDBACK)
                .orElseThrow());
        subscriptions.update(
                account,
                daily,
                EntityTagPrecondition.parse(subscriptions.get(account).etag()),
                true);
        assertThat(subscriptions.isDigestWindowCurrent(account, from)).isFalse();
        assertThat(subscriptions.isDigestWindowCurrent(account, java.time.Instant.now()))
                .isTrue();
    }

    @Test
    void shouldUnsubscribeIdempotentlyWithoutChangingTokenOrOtherChoices() {
        long account = account();
        update(account, true, true);
        String token = subscriptions
                .unsubscribeToken(account, NotificationSubscriptionKind.PRODUCT_SURVEYS)
                .orElseThrow();
        subscriptions.unsubscribe("invalid");
        subscriptions.unsubscribe(UUID.randomUUID().toString());
        subscriptions.unsubscribe(token);
        subscriptions.unsubscribe(token);
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.PRODUCT_SURVEYS))
                .isFalse();
        assertThat(subscriptions.isEnabled(account, NotificationSubscriptionKind.RESEARCH_SURVEYS))
                .isTrue();
        update(account, true, true);
        assertThat(subscriptions.unsubscribeToken(account, NotificationSubscriptionKind.PRODUCT_SURVEYS))
                .contains(token);
    }

    @Test
    void shouldExportOnlyChoicesAndEraseOnlyTheTargetAccountsSubscriptions() {
        long first = account();
        long second = account();
        update(first, true, false);
        update(second, false, true);
        assertThat(export.preferences(first))
                .isEqualTo(new de.tum.cit.aet.hephaestus.core.auth.spi.NotificationPreferencesExportQuery.Preferences(
                        false, true, false, "IMMEDIATE", false, false));
        String token = subscriptions
                .unsubscribeToken(first, NotificationSubscriptionKind.PRODUCT_SURVEYS)
                .orElseThrow();
        erasure.eraseAccount(first);
        subscriptions.unsubscribe(token);
        assertThat(repository.findAllByAccountIdOrderByKind(first)).isEmpty();
        assertThat(subscriptions.isEnabled(second, NotificationSubscriptionKind.RESEARCH_SURVEYS))
                .isTrue();
    }

    private void update(long account, boolean product, boolean research) {
        subscriptions.update(
                account,
                new UpdateNotificationPreferencesDTO(
                        false,
                        product,
                        research,
                        de.tum.cit.aet.hephaestus.notification.preferences.NotificationEmailFrequency.IMMEDIATE,
                        false,
                        false),
                EntityTagPrecondition.parse(subscriptions.get(account).etag()),
                false);
    }

    private long account() {
        var row = new Account("Subscription " + UUID.randomUUID());
        row.setPrimaryEmail(UUID.randomUUID() + "@example.org");
        row.setPrimaryEmailVerifiedAt(java.time.Instant.now());
        return Objects.requireNonNull(accounts.save(row).getId());
    }
}
