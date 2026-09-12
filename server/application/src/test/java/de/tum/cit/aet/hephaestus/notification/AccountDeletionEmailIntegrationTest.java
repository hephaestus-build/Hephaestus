package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.event.AccountDeletionScheduledEvent;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettings;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.notification.email.CapturingJavaMailSender;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole spine, end to end: a committed {@code softDelete} → a registry row → the listener → the
 * gateway → a captured message, and on a transport failure a {@code FAILED} row that a resubmission
 * completes. Listeners run inline here ({@code TestAsyncConfiguration}), so what a real deployment
 * does on the async executor happens before {@code softDelete} returns.
 */
@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class AccountDeletionEmailIntegrationTest extends BaseIntegrationTest {

    private static final String LISTENER = AccountDeletionEmailListener.class.getName() + ".on(";

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CapturingJavaMailSender mailSender;

    @Autowired
    private FailedEventPublications failedPublications;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InstanceSettingsService instanceSettingsService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldSendNothingAndPersistNoPublicationWhenDeletionRollsBack() {
        String address = "rollback-" + UUID.randomUUID() + "@hephaestus.test";
        long accountId = persistAccount(address, Instant.now());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accountService.softDelete(accountId, null);
            status.setRollbackOnly();
        });

        assertThat(mailSender.sent()).noneMatch(message -> recipient(message).equals(address));
        assertThat(publications(accountId)).isEmpty();
        assertThat(accountRepository.findById(accountId))
                .get()
                .extracting(Account::getStatus)
                .isEqualTo(Account.Status.ACTIVE);
    }

    @Test
    void shouldDrainAnExpiredBatchBeforeDeliveringTheNextNotification() {
        String address = "backlog-" + UUID.randomUUID() + "@hephaestus.test";
        long accountId = persistAccount(address, Instant.now());
        mailSender.failWith(new MailSendException("relay down", new ConnectException("refused")));
        accountService.softDelete(accountId, null);
        List<UUID> expiredIds = new ArrayList<>();
        try {
            for (int i = 0; i < NotificationRedeliveryJob.BATCH_SIZE; i++) {
                UUID id = UUID.randomUUID();
                expiredIds.add(id);
                var event = new AccountDeletionScheduledEvent(-i - 1L, Instant.parse("2000-01-01T00:00:00Z"));
                assertThat(jdbc.update(
                                """
                        INSERT INTO event_publication
                            (id, listener_id, event_type, serialized_event, publication_date, status, completion_attempts)
                        SELECT ?, listener_id, event_type, ?, ?, 'FAILED', 1
                        FROM event_publication WHERE serialized_event LIKE ?
                        """,
                                id,
                                objectMapper.writeValueAsString(event),
                                Timestamp.from(Instant.now().minus(Duration.ofDays(8))),
                                "%\"accountId\":" + accountId + ",%"))
                        .as("the expired publication must exist before testing backlog drainage")
                        .isEqualTo(1);
            }
            jdbc.update(
                    "UPDATE event_publication SET publication_date = ? WHERE serialized_event LIKE ?",
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
                    "%\"accountId\":" + accountId + ",%");
            mailSender.failWith(null);

            new NotificationRedeliveryJob(failedPublications).resubmitFailed();
            assertThat(mailSender.sent())
                    .noneMatch(message -> recipient(message).equals(address));
            new NotificationRedeliveryJob(failedPublications).resubmitFailed();

            assertThat(mailSender.sent())
                    .filteredOn(message -> recipient(message).equals(address))
                    .hasSize(1);
            assertThat(publications(accountId)).isEmpty();
            for (UUID id : expiredIds) {
                assertThat(jdbc.queryForList("SELECT id FROM event_publication WHERE id = ?", id))
                        .isEmpty();
            }
        } finally {
            for (UUID id : expiredIds) {
                jdbc.update("DELETE FROM event_publication WHERE id = ?", id);
            }
        }
    }

    /** The instance installs with Silent Mode engaged (fail-closed); every test here needs it released. */
    @BeforeEach
    void releaseSilentMode() {
        setSilentMode(false);
    }

    @AfterEach
    void restoreTransport() {
        mailSender.failWith(null);
        mailSender.sent().clear();
    }

    @Test
    void shouldEmailTheVerifiedAddressOnceTheDeletionIsCommitted() throws Exception {
        String address = "delete-me-" + UUID.randomUUID() + "@hephaestus.test";
        long accountId = persistAccount(address, Instant.now());

        accountService.softDelete(accountId, null);

        List<MimeMessage> toAddress = mailSender.sent().stream()
                .filter(message -> recipient(message).equals(address))
                .toList();
        assertThat(toAddress).hasSize(1);
        MimeMessage message = toAddress.getFirst();
        assertThat(message.getSubject()).isEqualTo("Your Hephaestus account is scheduled for deletion");
        assertThat(message.getFrom()).containsExactly(new InternetAddress("noreply@hephaestus.test", "Hephaestus"));
        assertThat(message.getHeader("Message-ID")).singleElement().asString().endsWith("@hephaestus.test>");
        assertThat(publications(accountId))
                .as("a delivered notification leaves no registry row behind (completion-mode=delete)")
                .isEmpty();
    }

    @Test
    void shouldWithholdWithoutARegistryRowWhenTheAddressIsUnverified() {
        String address = "unverified-" + UUID.randomUUID() + "@hephaestus.test";
        long accountId = persistAccount(address, null);

        accountService.softDelete(accountId, null);

        assertThat(mailSender.sent()).noneMatch(message -> recipient(message).equals(address));
        assertThat(publications(accountId)).isEmpty();
    }

    @Test
    void shouldKeepAFailedPublicationAndDeliverItOnResubmission() {
        String address = "retry-" + UUID.randomUUID() + "@hephaestus.test";
        long accountId = persistAccount(address, Instant.now());
        mailSender.failWith(new MailSendException("relay down", new ConnectException("refused")));

        accountService.softDelete(accountId, null);

        assertThat(mailSender.sent()).noneMatch(message -> recipient(message).equals(address));
        assertThat(publications(accountId)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("listener_id")).asString().startsWith(LISTENER);
        });

        mailSender.failWith(null);
        failedPublications.resubmit(ResubmissionOptions.defaults());

        assertThat(mailSender.sent())
                .filteredOn(message -> recipient(message).equals(address))
                .hasSize(1);
        assertThat(publications(accountId)).isEmpty();
    }

    @Test
    void shouldWithholdUnderSilentModeAndNotRetry() {
        String address = "silent-" + UUID.randomUUID() + "@hephaestus.test";
        long accountId = persistAccount(address, Instant.now());
        setSilentMode(true);
        try {
            accountService.softDelete(accountId, null);
        } finally {
            setSilentMode(false);
        }

        assertThat(mailSender.sent()).noneMatch(message -> recipient(message).equals(address));
        assertThat(publications(accountId))
                .as("Silent Mode is a decision, not an outage: the publication completes as withheld")
                .isEmpty();
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

    private long persistAccount(String email, @Nullable Instant verifiedAt) {
        Account account = new Account("Deletion " + email);
        account.setPrimaryEmail(email);
        account.setPrimaryEmailVerifiedAt(verifiedAt);
        return Objects.requireNonNull(accountRepository.save(account).getId());
    }

    /** Registry rows for this account's event: the serialized event carries the account id. */
    private List<Map<String, @Nullable Object>> publications(long accountId) {
        return jdbc.queryForList(
                "SELECT status, listener_id FROM event_publication WHERE serialized_event LIKE ?",
                "%\"accountId\":" + accountId + ",%");
    }

    private static String recipient(MimeMessage message) {
        try {
            return ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress();
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException(e);
        }
    }
}
