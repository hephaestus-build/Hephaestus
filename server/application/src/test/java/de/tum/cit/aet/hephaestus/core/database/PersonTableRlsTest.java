package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
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

    @Test
    void shouldHideReceiptsAndRejectWritesForAnotherOwner() {
        long first = account();
        long second = account();
        long workspace = receiptWorkspace("receipt-owner-policy");
        UUID key = UUID.randomUUID();
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            owners.ofJob(second);
            assertThat(insertReceipt(jdbc, workspace, second, key, 0)).isEqualTo(1);
        });

        assertThat(jdbc.queryForList("SELECT idem_key FROM client_input WHERE idem_key = ?", UUID.class, key))
                .isEmpty();
        transaction.executeWithoutResult(status -> {
            owners.ofJob(first);
            assertThat(jdbc.queryForList("SELECT idem_key FROM client_input WHERE idem_key = ?", UUID.class, key))
                    .isEmpty();
            assertThat(jdbc.update("DELETE FROM client_input WHERE idem_key = ?", key))
                    .isZero();
            assertThat(insertReceipt(jdbc, workspace, first, key, 0)).isEqualTo(1);
        });
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    owners.ofJob(first);
                    insertReceipt(jdbc, workspace, second, UUID.randomUUID(), 0);
                }))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThat(PostgreSQLTestContainer.ownerJdbc()
                        .queryForList("SELECT account_id FROM client_input WHERE idem_key = ?", Long.class, key))
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    void shouldPruneOnlyExpiredReceiptsWithoutAnOwnerBinding() {
        long first = account();
        long second = account();
        long workspace = receiptWorkspace("receipt-expiration");
        UUID expired = UUID.randomUUID();
        UUID retained = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        var owner = PostgreSQLTestContainer.ownerJdbc();
        insertReceipt(owner, workspace, first, expired, 9);
        insertReceipt(owner, workspace, first, retained, 4);
        insertReceipt(owner, workspace, second, fresh, 0);

        assertThat(jdbc.queryForObject("SELECT public.prune_client_inputs()", Integer.class))
                .isPositive();
        assertThat(owner.queryForList(
                        "SELECT idem_key FROM client_input WHERE workspace_id = ?", UUID.class, workspace))
                .containsExactlyInAnyOrder(retained, fresh);
    }

    private long receiptWorkspace(String slug) {
        return Objects.requireNonNull(jdbc.queryForObject("""
            INSERT INTO workspace(slug,display_name,account_login,account_type,status)
            VALUES(?,?,?,'ORG','ACTIVE') RETURNING id
            """, Long.class, slug, slug, slug));
    }

    private static int insertReceipt(JdbcTemplate rows, long workspace, long account, UUID key, int daysAgo) {
        return rows.update("""
            INSERT INTO client_input(workspace_id,account_id,idem_key,fingerprint,result_id,related_id,created_at)
            VALUES(?,?,?,sha256('fixture'::bytea),uuidv7(),uuidv7(),now() - (? * interval '1 day'))
            """, workspace, account, key, daysAgo);
    }

    private long account() {
        return Objects.requireNonNull(accounts.save(new Account("Export owner")).getId());
    }
}
