package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettings;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class EmailAdminControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private CapturingJavaMailSender mailSender;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private InstanceSettingsService instanceSettingsService;

    @BeforeEach
    void releaseSilentMode() {
        setSilentMode(false);
    }

    @AfterEach
    void clearOutbox() {
        mailSender.sent().clear();
    }

    @Test
    @WithUser
    void shouldRefuseNonAdmins() {
        webTestClient
                .post()
                .uri("/admin/email/test")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("to", "someone@hephaestus.test"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(mailSender.sent()).isEmpty();
    }

    @Test
    @WithAdminUser
    void shouldSendToTheGivenAddress() throws Exception {
        EmailTestResponseDTO response = webTestClient
                .post()
                .uri("/admin/email/test")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("to", "ops@hephaestus.test"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(EmailTestResponseDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.outcome()).isEqualTo(Outcome.SENT);
        assertThat(response.to()).isEqualTo("ops@hephaestus.test");
        assertThat(response.messageId()).endsWith("@hephaestus.test>");
        assertThat(mailSender.sent()).hasSize(1);
        assertThat(mailSender.sent().getFirst().getSubject()).isEqualTo("Test email from Hephaestus");
    }

    @Test
    @WithAdminUser
    void shouldRejectAMalformedAddressBeforeTouchingTheRelay() {
        webTestClient
                .post()
                .uri("/admin/email/test")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("to", "not-an-address"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(mailSender.sent()).isEmpty();
    }

    @Test
    @WithAdminUser
    void shouldReportSilentModeInsteadOfSending() {
        setSilentMode(true);
        try {
            EmailTestResponseDTO response = webTestClient
                    .post()
                    .uri("/admin/email/test")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("to", "ops@hephaestus.test"))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(EmailTestResponseDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(response).isNotNull();
            assertThat(response.outcome()).isEqualTo(Outcome.SILENT_MODE);
            assertThat(mailSender.sent()).isEmpty();
        } finally {
            setSilentMode(false);
        }
    }

    @Test
    void shouldFallBackToTheCallersVerifiedAddress() {
        String address = "admin-" + UUID.randomUUID() + "@hephaestus.test";
        Account account = new Account("Admin " + address);
        account.setPrimaryEmail(address);
        account.setPrimaryEmailVerifiedAt(Instant.now());
        long accountId = Objects.requireNonNull(accountRepository.save(account).getId());

        EmailTestResponseDTO response = webTestClient
                .post()
                .uri("/admin/email/test")
                .headers(headers -> headers.setBearerAuth("mock-jwt-sub-" + accountId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(EmailTestResponseDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.outcome()).isEqualTo(Outcome.SENT);
        assertThat(response.to()).isEqualTo(address);
    }

    @Test
    void shouldReportNoRecipientWhenTheCallerHasNoVerifiedAddress() {
        long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("No address")).getId());

        EmailTestResponseDTO response = webTestClient
                .post()
                .uri("/admin/email/test")
                .headers(headers -> headers.setBearerAuth("mock-jwt-sub-" + accountId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(EmailTestResponseDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.outcome()).isEqualTo(Outcome.NO_RECIPIENT);
        assertThat(response.to()).isNull();
    }

    private void setSilentMode(boolean engaged) {
        InstanceSettings current = instanceSettingsService.get();
        if (current.isSilentModeEngaged() != engaged) {
            instanceSettingsService.updateSilentMode(
                    engaged,
                    engaged ? "notification test" : null,
                    "notification-test",
                    EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
        }
    }
}
