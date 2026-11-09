package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettings;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.notification.email.CapturingJavaMailSender;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
import de.tum.cit.aet.hephaestus.notification.email.EmailRateLimitProperties;
import de.tum.cit.aet.hephaestus.notification.email.EmailRateLimiter;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Instant;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The whole spine, end to end: a committed {@code softDelete} → a registry row → the listener → one
 * {@code agent_action} → the outbox executor → a captured message. A relay that refused the message is retried
 * by the outbox; one that left the outcome unclear is never sent again. Listeners run inline here
 * ({@code TestAsyncConfiguration}), so what a real deployment does on the async executor happens before
 * {@code softDelete} returns; the test runs the executor itself.
 */
@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class AccountDeletionEmailIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BucketResolver buckets;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CapturingJavaMailSender mailSender;

    @Autowired
    private ActionExecutor executor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InstanceSettingsService instanceSettingsService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldSendNothingAndPersistNoPublicationWhenDeletionRollsBack() {
        String address = "rollback-" + UUID.randomUUID() + "@hephaestus.test";
        long accountId = persistAccount(address, Instant.now());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accountService.softDelete(accountId);
            status.setRollbackOnly();
        });

        assertThat(mailSender.sent()).noneMatch(message -> recipient(message).equals(address));
        assertThat(publications(accountId)).isEmpty();
        assertThat(actions(accountId)).isEmpty();
        assertThat(accountRepository.findById(accountId))
                .get()
                .extracting(Account::getStatus)
                .isEqualTo(Account.Status.ACTIVE);
    }

    @Test
    void shouldPersistSharedEmailBudgetsAndReserveEssentialCapacity() {
        String prefix = UUID.randomUUID() + ":";
        BucketResolver scoped = (key, configuration) -> buckets.resolve(prefix + key, configuration);
        var first = new EmailRateLimiter(scoped, new EmailRateLimitProperties(3, 2));
        var second = new EmailRateLimiter(scoped, new EmailRateLimitProperties(3, 2));
        try {
            assertThat(first.acquire(true)).isEmpty();
            assertThat(second.acquire(true)).isEmpty();
            assertThat(first.acquire(true)).isPresent();
            assertThat(second.acquire(false)).isEmpty();
            assertThat(first.acquire(false)).isPresent();
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM auth_rate_limit_bucket WHERE id IN (?, ?) AND state IS NOT NULL AND expires_at IS NOT NULL",
                            Long.class,
                            prefix + "email:optional",
                            prefix + "email:total"))
                    .isEqualTo(2);
        } finally {
            jdbc.update(
                    "DELETE FROM auth_rate_limit_bucket WHERE id IN (?, ?)",
                    prefix + "email:optional",
                    prefix + "email:total");
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

        accountService.softDelete(accountId);
        executor.runDue();
        executor.runDue();

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
        assertThat(actions(accountId)).singleElement().satisfies(action -> {
            assertThat(action).containsEntry("state", "DONE").containsEntry("payload", null);
            assertThat(action.get("readback_ref")).isEqualTo(message.getHeader("Message-ID")[0]);
        });
    }

    @Test
    void shouldWithholdWithoutARegistryRowWhenTheAddressIsUnverified() {
        String address = "unverified-" + UUID.randomUUID() + "@hephaestus.test";
        long accountId = persistAccount(address, null);

        accountService.softDelete(accountId);

        assertThat(mailSender.sent()).noneMatch(message -> recipient(message).equals(address));
        assertThat(publications(accountId)).isEmpty();
        assertThat(actions(accountId)).isEmpty();
    }

    @Test
    void shouldRetryAnEmailTheRelayNeverAcceptedAndSendItOnce() {
        String address = "retry-" + UUID.randomUUID() + "@hephaestus.test";
        long accountId = persistAccount(address, Instant.now());
        // JavaMailSenderImpl's connection failure: the cause, and every message's failure.
        var refused = new MessagingException("connect", new ConnectException("refused"));
        mailSender.failWith(
                new MailSendException("Mail server connection failed", refused, Map.of("message", refused)));

        accountService.softDelete(accountId);
        executor.runDue();

        assertThat(mailSender.sent()).noneMatch(message -> recipient(message).equals(address));
        assertThat(publications(accountId))
                .as("the listener completed: the outbox owns the retry")
                .isEmpty();
        assertThat(actions(accountId))
                .singleElement()
                .satisfies(action ->
                        assertThat(action).containsEntry("state", "ADMITTED").containsEntry("attempts", 1));

        mailSender.failWith(null);
        jdbc.update(
                "UPDATE agent_action SET next_attempt_at = now() WHERE subject_account_id = ? AND state = 'ADMITTED'",
                accountId);
        executor.runDue();
        executor.runDue();

        assertThat(mailSender.sent())
                .filteredOn(message -> recipient(message).equals(address))
                .hasSize(1);
        assertThat(actions(accountId))
                .singleElement()
                .satisfies(action -> assertThat(action).containsEntry("state", "DONE"));
    }

    @Test
    void shouldNeverSendAgainAnEmailWhoseAcceptanceTheRelayLeftUnclear() {
        String address = "unclear-" + UUID.randomUUID() + "@hephaestus.test";
        long accountId = persistAccount(address, Instant.now());
        mailSender.failWith(new MailSendException(
                Map.of("message", new MessagingException("data", new IOException("read timed out")))));

        accountService.softDelete(accountId);
        executor.runDue();
        mailSender.failWith(null);
        executor.runDue();

        assertThat(mailSender.sent()).noneMatch(message -> recipient(message).equals(address));
        assertThat(actions(accountId))
                .singleElement()
                .satisfies(action -> assertThat(action)
                        .containsEntry("state", "UNCERTAIN")
                        .containsEntry("stop_reason", "WRITE_UNCONFIRMED"));
    }

    @Test
    void shouldWithholdUnderSilentModeAndNotRetry() {
        String address = "silent-" + UUID.randomUUID() + "@hephaestus.test";
        long accountId = persistAccount(address, Instant.now());
        setSilentMode(true);
        try {
            accountService.softDelete(accountId);
            executor.runDue();
        } finally {
            setSilentMode(false);
        }

        assertThat(mailSender.sent()).noneMatch(message -> recipient(message).equals(address));
        assertThat(publications(accountId))
                .as("Silent Mode is a decision, not an outage: the publication completes as withheld")
                .isEmpty();
        assertThat(actions(accountId))
                .singleElement()
                .satisfies(action -> assertThat(action)
                        .containsEntry("state", "CANCELLED")
                        .containsEntry("stop_reason", "SILENCED"));
    }

    private void setSilentMode(boolean engaged) {
        InstanceSettings current = instanceSettingsService.get();
        if (current.isSilentModeEngaged() != engaged) {
            instanceSettingsService.updateSilentMode(
                    engaged,
                    engaged ? "notification test" : null,
                    null,
                    EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
        }
    }

    private long persistAccount(String email, @Nullable Instant verifiedAt) {
        Account account = new Account("Deletion " + email);
        account.setPrimaryEmail(email);
        account.setPrimaryEmailVerifiedAt(verifiedAt);
        return Objects.requireNonNull(accountRepository.save(account).getId());
    }

    private List<Map<String, @Nullable Object>> actions(long accountId) {
        return jdbc.queryForList(
                "SELECT state, stop_reason, attempts, readback_ref, payload::text AS payload FROM agent_action"
                        + " WHERE subject_account_id = ?",
                accountId);
    }

    /** Registry rows for this account's event: the serialized event carries the account id. */
    private List<Map<String, @Nullable Object>> publications(long accountId) {
        return jdbc.queryForList(
                "SELECT id, status, listener_id FROM event_publication WHERE serialized_event LIKE ?",
                "%\"accountId\":" + accountId + ",%");
    }

    private static String recipient(MimeMessage message) {
        try {
            return ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress();
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
    }
}
