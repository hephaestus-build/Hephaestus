package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.notification.email.CapturingJavaMailSender;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class AccountSecurityEmailIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private AccountService accounts;

    @Autowired
    private AccountRepository repository;

    @Autowired
    private CapturingJavaMailSender sender;

    @Autowired
    private InstanceSettingsService settings;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void releaseSilentMode() {
        var current = settings.get();
        if (current.isSilentModeEngaged()) {
            settings.updateSilentMode(
                    false, null, "security-test", EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
        }
    }

    @Test
    void shouldNotifyOnceForACommittedRoleChangeButNotItsNoOpReplay() throws MessagingException {
        Account account = account(Account.Status.ACTIVE);
        long id = Objects.requireNonNull(account.getId());
        accounts.adminSetRole(id, "APP_ADMIN", id);
        accounts.adminSetRole(id, "APP_ADMIN", id);
        var messages = sender.sent().stream()
                .filter(m -> recipient(m).equals(account.getPrimaryEmail()))
                .toList();
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getSubject()).isEqualTo("Your Hephaestus account security settings changed");
        assertThat(messages.getFirst().getHeader("List-Unsubscribe")).isNull();
    }

    @Test
    void shouldSendNothingWhenTheRoleChangeRollsBack() {
        Account account = account(Account.Status.ACTIVE);
        long id = Objects.requireNonNull(account.getId());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            accounts.adminSetRole(id, "APP_ADMIN", id);
            tx.setRollbackOnly();
        });
        assertThat(sender.sent()).noneMatch(m -> recipient(m).equals(account.getPrimaryEmail()));
        assertThat(repository.findById(id))
                .get()
                .extracting(Account::getAppRole)
                .isEqualTo(Account.AppRole.USER);
    }

    @ParameterizedTest
    @EnumSource(
            value = Account.Status.class,
            names = {"SUSPENDED", "DELETING", "DELETED"})
    void shouldWithholdSecurityNoticesForInactiveAccounts(Account.Status status) {
        Account account = account(status);
        long id = Objects.requireNonNull(account.getId());
        accounts.adminSetRole(id, "APP_ADMIN", id);
        assertThat(sender.sent()).noneMatch(m -> recipient(m).equals(account.getPrimaryEmail()));
    }

    private Account account(Account.Status status) {
        Account account = new Account("Security notice");
        account.setPrimaryEmail("security-" + UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        account.setStatus(status);
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
