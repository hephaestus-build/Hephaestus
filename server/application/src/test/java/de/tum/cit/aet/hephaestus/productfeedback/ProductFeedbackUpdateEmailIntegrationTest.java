package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.notification.ProductFeedbackUpdateEmailRequested;
import de.tum.cit.aet.hephaestus.notification.email.CapturingJavaMailSender;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.notification.preferences.UpdateNotificationPreferencesDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackUpdatedEvent;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackUpdatedEvent.Kind;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.net.ConnectException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
class ProductFeedbackUpdateEmailIntegrationTest extends BaseIntegrationTest {
    private static final String ISSUE = "https://github.com/example/feedback/issues/23";
    private static final String ORIGINAL = "https://github.com/example/feedback/issues/7";

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private FeedbackService feedback;

    @Autowired
    private NotificationSubscriptionService subscriptions;

    @Autowired
    private CapturingJavaMailSender mail;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FailedEventPublications failed;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void restoreMailTransport() {
        mail.failWith(null);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void shouldNotifyReporterWithoutCopyingReportTextWhenSubscribed(Kind kind) throws Exception {
        Account reporter = reporter();
        UUID id = report(reporter);
        assertThat(subscriptions.get(accountId(reporter)).productFeedbackUpdates())
                .isTrue();

        publish(id, reporter, kind, false);

        MimeMessage message = sentTo(reporter).getFirst();
        String expected = switch (kind) {
            case FILED -> "Your product feedback is now an issue on GitHub";
            case PUBLISHED -> "A maintainer made your report public";
            case COMMENTED -> "A new comment on your issue";
            case CLOSED_COMPLETED -> "Your issue was closed as completed";
            case CLOSED_NOT_PLANNED -> "Your issue was closed as not planned";
            case DUPLICATE -> "Your issue was closed as a duplicate. Hephaestus now follows the original";
            case REOPENED -> "Your issue was reopened";
            case INCLUDED_IN_RELEASE -> "The change that closed your issue is in release v1.0.0-rc.1";
            case MADE_PRIVATE ->
                "Your issue moved to the private repository. Copies made while it was public can remain.";
        };
        assertThat(sentTo(reporter)).hasSize(1);
        assertThat(message.getSubject()).isEqualTo(expected);
        assertThat(message.getHeader("List-Unsubscribe"))
                .singleElement()
                .asString()
                .contains("/notifications/unsubscribe/");
        var mixed = (MimeMultipart) message.getContent();
        var alternatives = (MimeMultipart) mixed.getBodyPart(0).getContent();
        for (int i = 0; i < alternatives.getCount(); i++) {
            String body = alternatives.getBodyPart(i).getContent().toString();
            // A private issue's address never reaches the reporter's mailbox.
            assertThat(body)
                    .contains(expected, "/product-feedback", "Your product feedback")
                    .doesNotContain(ISSUE, ORIGINAL, "private-feedback-content", "a maintainer replied", "fixed");
            if (kind == Kind.FILED) assertThat(body).contains("The maintainers can see it");
        }
        assertThat(publications(id)).isEmpty();

        subscriptions.unsubscribe(subscriptions
                .unsubscribeToken(accountId(reporter), NotificationSubscriptionKind.PRODUCT_FEEDBACK_UPDATES)
                .orElseThrow());
        publish(id, reporter, kind, true);

        assertThat(sentTo(reporter)).hasSize(1);
        assertThat(publications(id)).isEmpty();
    }

    @Test
    void shouldOmitThePrivateVisibilityNoteWhenTheIssueIsPublic() throws Exception {
        Account reporter = reporter();
        publish(report(reporter), reporter, Kind.FILED, true);

        assertThat(sentTo(reporter)).hasSize(1);
        var mixed = (MimeMultipart) sentTo(reporter).getFirst().getContent();
        var alternatives = (MimeMultipart) mixed.getBodyPart(0).getContent();
        for (int i = 0; i < alternatives.getCount(); i++) {
            assertThat(alternatives.getBodyPart(i).getContent().toString())
                    .contains(ISSUE)
                    .doesNotContain("The maintainers can see it");
        }
    }

    @Test
    void shouldLinkTheOriginalOfAPublicDuplicate() throws Exception {
        Account reporter = reporter();
        publish(report(reporter), reporter, Kind.DUPLICATE, true);

        var mixed = (MimeMultipart) sentTo(reporter).getFirst().getContent();
        var alternatives = (MimeMultipart) mixed.getBodyPart(0).getContent();
        for (int i = 0; i < alternatives.getCount(); i++) {
            assertThat(alternatives.getBodyPart(i).getContent().toString()).contains(ISSUE, ORIGINAL);
        }
    }

    @Test
    void shouldRollBackDefaultSubscriptionAndDeliveryRequestWhenUpdateRollsBack() {
        Account reporter = reporter();
        UUID id = report(reporter);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            events.publishEvent(event(id, reporter, Kind.COMMENTED, true));
            status.setRollbackOnly();
        });

        assertThat(sentTo(reporter)).isEmpty();
        assertThat(publications(id)).isEmpty();
        assertThat(subscriptions.unsubscribeToken(
                        accountId(reporter), NotificationSubscriptionKind.PRODUCT_FEEDBACK_UPDATES))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"retry", "unsubscribed", "resubscribed", "suspended", "unverified"})
    void shouldRecheckReporterAndSubscriptionWhenRetrying(String change) {
        Account reporter = reporter();
        UUID id = report(reporter);
        mail.failWith(new MailSendException("relay down", new ConnectException("refused")));
        publish(id, reporter, Kind.COMMENTED, true);
        assertThat(publications(id)).hasSize(1);
        switch (change) {
            case "retry" -> {}
            case "unsubscribed", "resubscribed" -> {
                subscriptions.unsubscribe(subscriptions
                        .unsubscribeToken(accountId(reporter), NotificationSubscriptionKind.PRODUCT_FEEDBACK_UPDATES)
                        .orElseThrow());
                if (change.equals("resubscribed")) {
                    var current = subscriptions.get(accountId(reporter));
                    subscriptions.update(
                            accountId(reporter),
                            new UpdateNotificationPreferencesDTO(false, false, null, true),
                            EntityTagPrecondition.parse(current.etag()),
                            false);
                }
            }
            case "suspended" -> {
                reporter.setStatus(Account.Status.SUSPENDED);
                accounts.save(reporter);
            }
            case "unverified" -> {
                reporter.setPrimaryEmailVerifiedAt(null);
                accounts.save(reporter);
            }
            default -> throw new IllegalArgumentException(change);
        }
        mail.failWith(null);

        failed.resubmit(ResubmissionOptions.defaults()
                .withFilter(publication -> publication.getEvent() instanceof ProductFeedbackUpdateEmailRequested request
                        && request.feedbackId().equals(id)));

        assertThat(sentTo(reporter)).hasSize(change.equals("retry") ? 1 : 0);
        assertThat(publications(id)).isEmpty();
    }

    @Test
    void shouldCompleteRequestWithoutSendingWhenExpired() {
        Account reporter = reporter();
        UUID id = report(reporter);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> events.publishEvent(new ProductFeedbackUpdatedEvent(
                        id, accountId(reporter), Kind.COMMENTED, ISSUE, true, null, Instant.EPOCH)));

        assertThat(sentTo(reporter)).isEmpty();
        assertThat(publications(id)).isEmpty();
    }

    private Account reporter() {
        Account account = new Account("Reporter");
        account.setPrimaryEmail(UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        return accounts.save(account);
    }

    private UUID report(Account reporter) {
        return feedback.add(
                        new FeedbackRequestDTO(
                                ProductFeedback.Kind.BUG, "private-feedback-content", "/private/page", null, null),
                        accountId(reporter),
                        null)
                .id();
    }

    private void publish(UUID id, Account reporter, Kind kind, boolean publicIssue) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> events.publishEvent(event(id, reporter, kind, publicIssue)));
    }

    private static ProductFeedbackUpdatedEvent event(UUID id, Account reporter, Kind kind, boolean publicIssue) {
        String detail = switch (kind) {
            case DUPLICATE -> ORIGINAL;
            case INCLUDED_IN_RELEASE -> "v1.0.0-rc.1";
            default -> null;
        };
        return new ProductFeedbackUpdatedEvent(
                id, accountId(reporter), kind, ISSUE, publicIssue, detail, Instant.now());
    }

    private List<MimeMessage> sentTo(Account reporter) {
        return mail.sent().stream()
                .filter(message -> {
                    try {
                        return ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0])
                                .getAddress()
                                .equals(reporter.getPrimaryEmail());
                    } catch (MessagingException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toList();
    }

    private List<UUID> publications(UUID id) {
        return jdbc
                .queryForList(
                        "SELECT id FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?",
                        UUID.class,
                        ProductFeedbackUpdateEmailRequested.class.getName(),
                        "%" + id + "%")
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    private static long accountId(Account reporter) {
        return Objects.requireNonNull(reporter.getId());
    }
}
