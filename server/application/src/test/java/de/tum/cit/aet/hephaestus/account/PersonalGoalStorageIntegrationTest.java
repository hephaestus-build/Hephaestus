package de.tum.cit.aet.hephaestus.account;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.AccountPurger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PersonalGoalStorageIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OwnerScope owners;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private AccountPurger purger;

    @Test
    void shouldRequireTheAccountOwnerEvenWithoutAWorkspace() {
        long owner =
                Objects.requireNonNull(accounts.save(new Account("Goal owner")).getId());
        long other = Objects.requireNonNull(
                accounts.save(new Account("Other account")).getId());
        String words = "Give specific review feedback.";
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            owners.ofJob(owner);
            jdbc.update(
                    "INSERT INTO personal_goal (account_id, goal_id, words, revision) VALUES (?, ?, ?, 1)",
                    owner,
                    UUID.randomUUID(),
                    words);
        });

        assertThat(jdbc.queryForList("SELECT words FROM personal_goal WHERE account_id = ?", String.class, owner))
                .isEmpty();
        var denied = tx.execute(status -> {
            owners.ofJob(other);
            assertThat(jdbc.update("UPDATE personal_goal SET words = 'Other' WHERE account_id = ?", owner))
                    .isZero();
            return jdbc.queryForList("SELECT words FROM personal_goal WHERE account_id = ?", String.class, owner);
        });
        assertThat(denied).isEmpty();
        var allowed = tx.execute(status -> {
            owners.ofJob(owner);
            return jdbc.queryForList("SELECT words FROM personal_goal WHERE account_id = ?", String.class, owner);
        });
        assertThat(allowed).containsExactly(words);
    }

    @Test
    void shouldEraseTheGoalDuringTheExistingAccountPurge() {
        long owner = Objects.requireNonNull(
                accounts.save(new Account("Goal owner for erasure")).getId());
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            owners.ofJob(owner);
            jdbc.update(
                    "INSERT INTO personal_goal (account_id, goal_id, words, revision) VALUES (?, ?, ?, 1)",
                    owner,
                    UUID.randomUUID(),
                    "Give specific review feedback.");
        });

        jdbc.update("UPDATE account SET status = 'DELETING', deleted_at = CURRENT_TIMESTAMP WHERE id = ?", owner);
        tx.executeWithoutResult(status -> purger.purgeInCurrentTransaction(owner));

        tx.executeWithoutResult(status -> {
            owners.ofJob(owner);
            assertThat(jdbc.queryForList("SELECT revision FROM personal_goal WHERE account_id = ?", Long.class, owner))
                    .isEmpty();
        });
    }
}
