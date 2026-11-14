package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.notification.email.CapturingJavaMailSender;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
import de.tum.cit.aet.hephaestus.testconfig.AccountFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class AccountSecurityEmailIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private AccountRepository repository;

    @Autowired
    private CapturingJavaMailSender sender;

    @Autowired
    private InstanceSettingsService settings;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ActionExecutor executor;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void releaseSilentMode() {
        var current = settings.get();
        if (current.isSilentModeEngaged()) {
            settings.updateSilentMode(
                    false, null, null, EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
        }
    }

    @Test
    void shouldNotifyOnceForACommittedChangeEvenWhenItIsPublishedTwice() throws MessagingException {
        Account account = account(Account.Status.ACTIVE);
        long id = Objects.requireNonNull(account.getId());
        var change =
                new AccountSecurityChangedEvent(id, AccountSecurityChangedEvent.Kind.PASSKEY_CHANGED, Instant.now());
        commit(change);
        commit(change);
        ActionTestSupport.drain(executor, jdbc);
        var messages = sender.sent().stream()
                .filter(m -> recipient(m).equals(account.getPrimaryEmail()))
                .toList();
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getSubject()).isEqualTo("Your Hephaestus account security settings changed");
        assertThat(messages.getFirst().getHeader("List-Unsubscribe")).isNull();
    }

    @Test
    void shouldSendNothingWhenTheChangeRollsBack() {
        Account account = account(Account.Status.ACTIVE);
        long id = Objects.requireNonNull(account.getId());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            events.publishEvent(new AccountSecurityChangedEvent(
                    id, AccountSecurityChangedEvent.Kind.PASSKEY_CHANGED, Instant.now()));
            tx.setRollbackOnly();
        });
        ActionTestSupport.drain(executor, jdbc);
        assertThat(sender.sent()).noneMatch(m -> recipient(m).equals(account.getPrimaryEmail()));
    }

    @ParameterizedTest
    @EnumSource(
            value = Account.Status.class,
            names = {"BLOCKED", "DELETING", "DELETED"})
    void shouldWithholdSecurityNoticesForInactiveAccounts(Account.Status status) {
        Account account = account(status);
        long id = Objects.requireNonNull(account.getId());
        commit(new AccountSecurityChangedEvent(id, AccountSecurityChangedEvent.Kind.PASSKEY_CHANGED, Instant.now()));
        ActionTestSupport.drain(executor, jdbc);
        assertThat(sender.sent()).noneMatch(m -> recipient(m).equals(account.getPrimaryEmail()));
    }

    private void commit(AccountSecurityChangedEvent change) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> events.publishEvent(change));
    }

    private Account account(Account.Status status) {
        Account account = AccountFixtures.withStatus("Security notice", status);
        account.setPrimaryEmail("security-" + UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        return repository.save(account);
    }

    private static String recipient(MimeMessage message) {
        try {
            return ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress();
        } catch (MessagingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
