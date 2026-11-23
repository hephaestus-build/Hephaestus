package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.Collections;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PersonTableRlsTest extends BaseIntegrationTest {
    @Autowired
    private AccountRepository accounts;

    @Autowired
    private OwnerScope owners;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void shouldRequireATransactionToBindAnOwner() {
        long account = account();

        assertThatThrownBy(() -> owners.ofJob(account))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Owner scope requires a database transaction.");
    }

    @Test
    void shouldDeclareTheOwnerFunctionStableForIndexedPolicyReads() {
        assertThat(jdbc.queryForObject("""
                SELECT provolatile FROM pg_proc
                WHERE oid = 'public.current_owner_account()'::regprocedure
                """, String.class)).isEqualTo("s");
    }

    @Test
    void shouldHideAForeignExportFromNativeQueriesJobsAndBatchWrites() {
        long first = account();
        long second = account();
        var transaction = new TransactionTemplate(transactions);
        long foreign = Objects.requireNonNull(transaction.execute(status -> {
            owners.ofJob(second);
            return jdbc.queryForObject("""
                INSERT INTO account_export(account_id) VALUES (?) RETURNING id
                """, Long.class, second);
        }));

        transaction.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("hephaestus_app");
            owners.ofJob(first);
            assertThat(jdbc.queryForList("SELECT id FROM account_export WHERE id = ?", Long.class, foreign))
                    .isEmpty();
            assertThat(jdbc.batchUpdate(
                            "UPDATE account_export SET status = 'FAILED' WHERE id = ?",
                            Collections.singletonList(new Object[] {foreign})))
                    .containsExactly(0);
            assertThatThrownBy(() -> owners.ofJob(second)).isInstanceOf(IllegalStateException.class);
        });
        assertThat(jdbc.queryForList("SELECT id FROM account_export WHERE id = ?", Long.class, foreign))
                .isEmpty();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    owners.ofJob(first);
                    jdbc.update("INSERT INTO account_export(account_id) VALUES (?)", second);
                }))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    private long account() {
        return Objects.requireNonNull(accounts.save(new Account("Export owner")).getId());
    }
}
