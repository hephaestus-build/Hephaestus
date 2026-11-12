package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.AfterCommitCheckpoint;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PasskeyAuditDurabilityIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PasskeySecurityEvents security;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @EnumSource(
            value = AuthEvent.EventType.class,
            names = {"PASSKEY_CHANGED", "PASSKEY_VERIFIED"})
    void shouldCommitTheAuditWithThePasskeyChangeBeforeAfterCommitListenersRun(AuthEvent.EventType type)
            throws Exception {
        long id = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Passkey audit subject")).getId());

        AfterCommitCheckpoint.hold(transactions, () -> change(id, type), () -> {
            // A second connection sees the committed rows while all AFTER_COMMIT event listeners are still held.
            assertThat(jdbc.queryForObject(
                            "SELECT passkey_protection_enabled FROM account WHERE id=?", Boolean.class, id))
                    .isTrue();
            assertThat(audits(id, type)).isOne();
        });

        assertThat(audits(id, type)).isOne();
    }

    @ParameterizedTest
    @EnumSource(
            value = AuthEvent.EventType.class,
            names = {"PASSKEY_CHANGED", "PASSKEY_VERIFIED"})
    void shouldRollBackTheAuditWithThePasskeyChange(AuthEvent.EventType type) {
        long id = Objects.requireNonNull(accounts.saveAndFlush(new Account("Rolled-back passkey subject"))
                .getId());

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            change(id, type);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT passkey_protection_enabled FROM account WHERE id=?", Boolean.class, id))
                .isFalse();
        assertThat(audits(id, type)).isZero();
    }

    private void change(long id, AuthEvent.EventType type) {
        assertThat(accounts.lockStatusForUpdate(id)).isPresent();
        Account account = accounts.findById(id).orElseThrow();
        account.setPasskeyProtectionEnabled(true);
        accounts.save(account);
        if (type == AuthEvent.EventType.PASSKEY_CHANGED) {
            security.changed(account, "protection_enabled");
        } else {
            security.verified(id);
        }
    }

    private long audits(long id, AuthEvent.EventType type) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM auth_event WHERE account_id=? AND event_type=? AND result='SUCCESS'",
                Long.class,
                id,
                type.name()));
    }
}
