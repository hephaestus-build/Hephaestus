package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.notification.email.CapturingJavaMailSender;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.notification.preferences.UpdateNotificationPreferencesDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * An erased report's GitHub issue: the app scrubs what it can, then hands the rest to a maintainer with an email and a
 * Needs action entry that stays until an admin confirms the step. Only the GitHub transport is controlled.
 */
@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class ProductFeedbackCleanupIntegrationTest extends BaseIntegrationTest {
    private static final ProductFeedbackIssues.Repository PRIVATE_REPOSITORY =
            new ProductFeedbackIssues.Repository(41, "", "acme/feedback-private", 7, true);

    @MockitoBean
    private ProductFeedbackIssues issues;

    @MockitoBean
    private ProductFeedbackIssueUpdates updates;

    @Autowired
    private CleanupService cleanup;

    @Autowired
    private ProductFeedbackCleanupRepository cleanups;

    @Autowired
    private ProductFeedbackFilingRepository filings;

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private FeedbackAccountErasureAdapter erasure;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private NotificationSubscriptionService subscriptions;

    @Autowired
    private CapturingJavaMailSender mail;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private InstanceSettingsService settings;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> createdCleanups = new ArrayList<>();
    private Account admin;

    @BeforeEach
    void setUp() {
        mail.failWith(null);
        mail.sent().clear();
        setSilentMode(false);
        admin = account(Account.AppRole.APP_ADMIN);
    }

    @AfterEach
    void tearDown() {
        cleanups.deleteAllById(createdCleanups);
        subscribed(admin, false);
        mail.sent().clear();
    }

    @Test
    void shouldLeaveTheEditHistoryToAMaintainerWhenAPrivateIssueIsScrubbed() throws Exception {
        ProductFeedbackCleanup record = record(false, 12L, "I_12", Instant.now());

        cleanup.cleanDue();

        verify(updates).edit(eq(PRIVATE_REPOSITORY), eq(12L), eq(CleanupService.ERASED), anyString());
        verify(updates).close(PRIVATE_REPOSITORY, 12L, "not_planned");
        ProductFeedbackCleanup stored = cleanups.findById(record.getId()).orElseThrow();
        assertThat(stored.getManualStep()).isEqualTo(ProductFeedbackCleanup.ManualStep.EDIT_HISTORY);
        assertThat(stored.getManualSince()).isNotNull();
        assertThat(sentTo(admin)).singleElement().satisfies(message -> {
            assertThat(message.getSubject()).isEqualTo("An erased report needs a manual step on GitHub");
            assertThat(body(message)).contains("/admin/feedback").contains("Needs action");
        });

        // A step that waits for a maintainer is never claimed again.
        cleanup.cleanDue();
        verify(updates).close(PRIVATE_REPOSITORY, 12L, "not_planned");

        webTestClient
                .get()
                .uri("/admin/product-feedback/needs-action")
                .headers(as(admin))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.cleanups[?(@.id == '" + record.getId() + "')].step")
                .isEqualTo("EDIT_HISTORY")
                .jsonPath("$.cleanups[?(@.id == '" + record.getId() + "')].issueUrl")
                .isEqualTo("https://github.com/acme/feedback-private/issues/12");
    }

    @Test
    void shouldHandTheCleanupToAMaintainerWhenItIsUnfinishedAfterThirtyDays() {
        ProductFeedbackCleanup record = record(true, null, null, Instant.now().minus(Duration.ofDays(31)));

        cleanup.cleanDue();

        verifyNoInteractions(issues, updates);
        ProductFeedbackCleanup stored = cleanups.findById(record.getId()).orElseThrow();
        assertThat(stored.getManualStep()).isEqualTo(ProductFeedbackCleanup.ManualStep.UNFINISHED);
        assertThat(sentTo(admin)).hasSize(1);
        webTestClient
                .get()
                .uri("/admin/product-feedback/needs-action")
                .headers(as(admin))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.cleanups[?(@.id == '" + record.getId() + "')].marker")
                .isEqualTo("hephaestus-product-feedback:" + record.getMarker())
                .jsonPath("$.cleanups[?(@.id == '" + record.getId() + "')].issueNumber")
                .isEmpty();
    }

    @Test
    void shouldForgetTheCleanupWhenTheCreateNeverReachedGitHub() {
        ProductFeedbackCleanup record = record(false, null, null, Instant.now());
        when(issues.findByMarker(any(), anyString(), any())).thenReturn(Optional.empty());

        cleanup.cleanDue();

        assertThat(cleanups.findById(record.getId())).isEmpty();
        verify(updates, never()).edit(any(), anyLong(), anyString(), anyString());
        assertThat(sentTo(admin)).isEmpty();
    }

    @Test
    void shouldHandThePublicHistoryToAMaintainerWhenNoPrivateRepositoryCanTakeTheIssue() {
        ProductFeedbackCleanup record = record(true, 30L, "I_30", Instant.now());

        cleanup.cleanDue();

        // Scrubbed at once; its edit history stays public, so a maintainer gets it now, not after 30 days.
        verify(updates).edit(any(), eq(30L), eq(CleanupService.ERASED), anyString());
        verify(updates, never()).transfer(any(), any());
        ProductFeedbackCleanup stored = cleanups.findById(record.getId()).orElseThrow();
        assertThat(stored.getManualStep()).isEqualTo(ProductFeedbackCleanup.ManualStep.EDIT_HISTORY);
        assertThat(sentTo(admin)).hasSize(1);
    }

    @Test
    void shouldDeleteTheLocatorWhenAnAdminConfirmsAWaitingStep() {
        ProductFeedbackCleanup waiting = record(false, 5L, "I_5", Instant.now());
        waiting.setManualStep(ProductFeedbackCleanup.ManualStep.EDIT_HISTORY);
        waiting.setManualSince(Instant.now());
        cleanups.save(waiting);
        ProductFeedbackCleanup automatic = record(false, 6L, "I_6", Instant.now());
        Account member = account(Account.AppRole.USER);

        confirm(waiting.getId(), member).expectStatus().isForbidden().expectBody(Void.class);
        assertThat(cleanups.findById(waiting.getId())).isPresent();

        confirm(automatic.getId(), admin).expectStatus().isNotFound().expectBody(Void.class);
        assertThat(cleanups.findById(automatic.getId())).isPresent();

        confirm(waiting.getId(), admin).expectStatus().isNoContent().expectBody(Void.class);
        assertThat(cleanups.findById(waiting.getId())).isEmpty();
        assertThat(jdbc.queryForList(
                        "SELECT acting_account_id FROM auth_event WHERE event_type = 'PRODUCT_FEEDBACK_CLEANUP_CONFIRMED'"
                                + " AND CAST(details AS text) LIKE ?",
                        Long.class,
                        "%" + waiting.getId() + "%"))
                .containsExactly(admin.getId());
        subscribed(member, false);
    }

    @Test
    void shouldKeepTheIssueLocatorWhenTheReporterIsErased() {
        Account reporter = account(Account.AppRole.USER);
        long reporterId = Objects.requireNonNull(reporter.getId());
        var receipt = feedbackService.add(
                new FeedbackRequestDTO(ProductFeedback.Kind.BUG, "erased-report-content", null, null, null),
                reporterId,
                null);
        ProductFeedbackFiling filing = filings.findById(receipt.id()).orElseThrow();
        filing.setState(FilingState.FILED);
        filing.setRoute(FilingRoute.PUBLIC);
        filing.setRepository("acme/feedback");
        filing.setRepositoryId(40L);
        filing.setInstallationId(7L);
        filing.setIssueNumber(77L);
        filing.setIssueNodeId("I_77");
        filing.setFirstAttemptAt(Instant.now().minusSeconds(600));
        filings.save(filing);

        erasure.eraseAccount(reporterId);

        assertThat(filings.findById(receipt.id())).isEmpty();
        ProductFeedbackCleanup kept = cleanups.findAll().stream()
                .filter(row -> row.getMarker().equals(receipt.id()))
                .findFirst()
                .orElseThrow();
        createdCleanups.add(kept.getId());
        assertThat(kept.getRepository()).isEqualTo("acme/feedback");
        assertThat(kept.getIssueNumber()).isEqualTo(77L);
        assertThat(kept.isPublicIssue()).isTrue();
        assertThat(kept.getManualStep()).isNull();
        subscribed(reporter, false);
    }

    private WebTestClient.ResponseSpec confirm(UUID id, Account actor) {
        return webTestClient
                .post()
                .uri("/admin/product-feedback/cleanups/" + id + "/confirm")
                .headers(as(actor))
                .exchange();
    }

    /** A due record, first in the claim order, that this test removes again. */
    private ProductFeedbackCleanup record(
            boolean publicIssue, @Nullable Long issueNumber, @Nullable String nodeId, Instant createdAt) {
        var record = new ProductFeedbackCleanup(
                PRIVATE_REPOSITORY.fullName(),
                PRIVATE_REPOSITORY.id(),
                PRIVATE_REPOSITORY.installationId(),
                UUID.randomUUID(),
                issueNumber,
                publicIssue,
                createdAt.minusSeconds(60),
                createdAt);
        record.setIssueNodeId(nodeId);
        record.setNextAttemptAt(Instant.EPOCH);
        record = cleanups.save(record);
        createdCleanups.add(record.getId());
        return record;
    }

    private Account account(Account.AppRole role) {
        Account account = new Account("Cleanup test");
        account.setPrimaryEmail(UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        account.setAppRole(role);
        account = accounts.save(account);
        subscribed(account, true);
        return account;
    }

    private void subscribed(Account account, boolean enabled) {
        long id = Objects.requireNonNull(account.getId());
        var current = subscriptions.get(id);
        subscriptions.update(
                id,
                new UpdateNotificationPreferencesDTO(enabled, false, false, null),
                EntityTagPrecondition.parse(current.etag()),
                true);
    }

    /** Another class in the database may leave Silent Mode on, which withholds every email. */
    private void setSilentMode(boolean engaged) {
        var current = settings.get();
        if (current.isSilentModeEngaged() != engaged) {
            settings.updateSilentMode(
                    engaged,
                    engaged ? "cleanup test" : null,
                    null,
                    EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
        }
    }

    private static Consumer<HttpHeaders> as(Account account) {
        String prefix = account.getAppRole() == Account.AppRole.APP_ADMIN ? "mock-jwt-sub-" : "mock-jwt-member-";
        return headers -> headers.setBearerAuth(prefix + account.getId());
    }

    private List<MimeMessage> sentTo(Account account) {
        return mail.sent().stream()
                .filter(message -> Objects.equals(account.getPrimaryEmail(), recipient(message)))
                .toList();
    }

    private static String recipient(MimeMessage message) {
        try {
            return ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress();
        } catch (MessagingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String body(MimeMessage message) throws IOException, MessagingException {
        var bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
