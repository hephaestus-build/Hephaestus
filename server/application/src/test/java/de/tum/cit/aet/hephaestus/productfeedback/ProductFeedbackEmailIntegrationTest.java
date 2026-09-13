package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.notification.ProductFeedbackEmailRequested;
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
import java.net.ConnectException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class ProductFeedbackEmailIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private ProductFeedbackRepository feedback;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private NotificationSubscriptionService subscriptions;

    @Autowired
    private CapturingJavaMailSender mail;

    @Autowired
    private FailedEventPublications failed;

    @Autowired
    private InstanceSettingsService settings;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    private final List<Account> createdAccounts = new ArrayList<>();

    @BeforeEach
    void releaseSilentMode() {
        setSilentMode(false);
    }

    @AfterEach
    void restoreState() {
        mail.failWith(null);
        mail.sent().clear();
        setSilentMode(false);
        for (Account account : createdAccounts) {
            subscribed(account, false);
        }
    }

    @Test
    void shouldNotifyOnlySubscribedActiveVerifiedAdministratorsWithoutCopyingFeedbackContent() throws Exception {
        Account first = account(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE, true, true);
        Account second = account(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE, true, true);
        account(Account.AppRole.USER, Account.Status.ACTIVE, true, true);
        account(Account.AppRole.APP_ADMIN, Account.Status.SUSPENDED, true, true);
        account(Account.AppRole.APP_ADMIN, Account.Status.DELETING, true, true);
        account(Account.AppRole.APP_ADMIN, Account.Status.DELETED, true, true);
        account(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE, false, true);
        account(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE, true, false);

        ProductFeedback item = submit(first);

        assertThat(mail.sent())
                .extracting(ProductFeedbackEmailIntegrationTest::recipient)
                .containsExactlyInAnyOrder(first.getPrimaryEmail(), second.getPrimaryEmail());
        for (MimeMessage message : mail.sent()) {
            assertThat(message.getSubject()).isEqualTo("New product feedback in Hephaestus");
            assertThat(message.getHeader("List-Unsubscribe"))
                    .singleElement()
                    .asString()
                    .contains("/notifications/unsubscribe/");
            var bytes = new java.io.ByteArrayOutputStream();
            message.writeTo(bytes);
            assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8))
                    .contains("/admin/feedback")
                    .doesNotContain("private-feedback-content");
        }
        assertThat(publications(item.getId())).isEmpty();
    }

    @Test
    void shouldRollBackFeedbackAndEveryRecipientPublicationTogether() {
        Account admin = account(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE, true, true);
        UUID id = new TransactionTemplate(transactionManager).execute(status -> {
            ProductFeedback item = submit(admin);
            status.setRollbackOnly();
            return item.getId();
        });

        assertThat(id).isNotNull();
        assertThat(feedback.existsById(id)).isFalse();
        assertThat(publications(id)).isEmpty();
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void shouldRetryRecipientsIndependentlyWithoutResendingSuccessfulRecipients() {
        Account first = account(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE, true, true);
        Account second = account(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE, true, true);
        mail.failWith(new MailSendException("relay down", new ConnectException("refused")));
        ProductFeedback item = submit(first);
        assertThat(publications(item.getId())).hasSize(2);
        mail.failWith(null);

        failed.resubmit(ResubmissionOptions.defaults()
                .withFilter(publication -> publication.getEvent() instanceof ProductFeedbackEmailRequested event
                        && event.feedbackId().equals(item.getId())
                        && event.accountId() == Objects.requireNonNull(first.getId())));

        assertThat(publications(item.getId())).hasSize(1);
        assertThat(mail.sent())
                .extracting(ProductFeedbackEmailIntegrationTest::recipient)
                .containsExactly(first.getPrimaryEmail());

        resubmit(item.getId());

        assertThat(publications(item.getId())).isEmpty();
        assertThat(mail.sent())
                .extracting(ProductFeedbackEmailIntegrationTest::recipient)
                .containsExactly(first.getPrimaryEmail(), second.getPrimaryEmail());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"unsubscribed", "demoted", "suspended", "deleting", "deleted", "unverified", "source-erased"})
    void shouldRecheckEligibilityBeforeRetrying(String change) {
        Account admin = account(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE, true, true);
        mail.failWith(new MailSendException("relay down", new ConnectException("refused")));
        ProductFeedback item = submit(admin);
        assertThat(publications(item.getId())).hasSize(1);
        switch (change) {
            case "unsubscribed" -> subscribed(admin, false);
            case "demoted" -> admin.setAppRole(Account.AppRole.USER);
            case "suspended" -> admin.setStatus(Account.Status.SUSPENDED);
            case "deleting" -> admin.setStatus(Account.Status.DELETING);
            case "deleted" -> admin.setStatus(Account.Status.DELETED);
            case "unverified" -> admin.setPrimaryEmailVerifiedAt(null);
            case "source-erased" -> feedback.deleteById(item.getId());
            default -> throw new IllegalArgumentException(change);
        }
        accounts.save(admin);
        mail.failWith(null);

        resubmit(item.getId());

        assertThat(mail.sent()).isEmpty();
        assertThat(publications(item.getId())).isEmpty();
    }

    @Test
    void shouldCompleteAnExpiredPublicationWithoutSending() {
        Account admin = account(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE, true, false);
        ProductFeedback item = submit(admin);
        subscribed(admin, true);

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> events.publishEvent(new ProductFeedbackEmailRequested(
                        item.getId(), Objects.requireNonNull(admin.getId()), Instant.EPOCH)));

        assertThat(mail.sent()).isEmpty();
        assertThat(publications(item.getId())).isEmpty();
    }

    @Test
    void shouldCompleteWithoutSendingOrRetryingUnderSilentMode() {
        Account admin = account(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE, true, true);
        setSilentMode(true);

        ProductFeedback item = submit(admin);

        assertThat(mail.sent()).isEmpty();
        assertThat(publications(item.getId())).isEmpty();
    }

    private ProductFeedback submit(Account author) {
        return feedbackService.add(
                new FeedbackRequestDTO(ProductFeedback.Kind.BUG, "private-feedback-content", "/private/page", null),
                Objects.requireNonNull(author.getId()),
                null);
    }

    private Account account(Account.AppRole role, Account.Status status, boolean verified, boolean enabled) {
        Account account = new Account("Notification test");
        account.setPrimaryEmail(UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        account.setAppRole(Account.AppRole.APP_ADMIN);
        account = accounts.save(account);
        subscribed(account, enabled);
        account.setPrimaryEmailVerifiedAt(verified ? Instant.now() : null);
        account.setAppRole(role);
        account.setStatus(status);
        account = accounts.save(account);
        createdAccounts.add(account);
        return account;
    }

    private void subscribed(Account account, boolean enabled) {
        long id = Objects.requireNonNull(account.getId());
        var current = subscriptions.get(id);
        subscriptions.update(
                id,
                new UpdateNotificationPreferencesDTO(
                        enabled,
                        false,
                        false,
                        de.tum.cit.aet.hephaestus.notification.preferences.NotificationEmailFrequency.IMMEDIATE,
                        false,
                        false),
                EntityTagPrecondition.parse(current.etag()),
                true);
    }

    private List<UUID> publications(UUID feedbackId) {
        return jdbc
                .queryForList(
                        "SELECT id FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?",
                        UUID.class,
                        ProductFeedbackEmailRequested.class.getName(),
                        "%" + feedbackId + "%")
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    private void resubmit(UUID feedbackId) {
        failed.resubmit(ResubmissionOptions.defaults()
                .withFilter(publication -> publication.getEvent() instanceof ProductFeedbackEmailRequested event
                        && event.feedbackId().equals(feedbackId)));
    }

    private void setSilentMode(boolean engaged) {
        var current = settings.get();
        if (current.isSilentModeEngaged() != engaged) {
            settings.updateSilentMode(
                    engaged,
                    engaged ? "notification test" : null,
                    "notification-test",
                    EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
        }
    }

    private static String recipient(MimeMessage message) {
        try {
            return ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress();
        } catch (MessagingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
