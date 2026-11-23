package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class RlsTrapTest extends BaseIntegrationTest {
    @Autowired
    private AccountRepository accounts;

    @Test
    void shouldReadAWorkReviewAndHidePersonRowsOnTheNextUseOfTheSameConnection() throws SQLException {
        long account = Objects.requireNonNull(
                accounts.save(new Account("Scoped export")).getId());
        var fixture = PostgreSQLTestContainer.ownerJdbc();
        UUID review = UUID.randomUUID();
        String slug = "rls-trap-" + review.toString().substring(0, 8);
        long workspace = Objects.requireNonNull(fixture.queryForObject("""
                INSERT INTO workspace(slug, display_name, account_login, account_type, status)
                VALUES (?, 'RLS trap', ?, 'ORG', 'ACTIVE') RETURNING id
                """, Long.class, slug, slug));
        long item = Objects.requireNonNull(fixture.queryForObject("""
                INSERT INTO item(workspace_id, kind, source_id)
                VALUES (?, 'scm.pull_request', ?) RETURNING id
                """, Long.class, workspace, workspace));
        fixture.update("""
                INSERT INTO review(id, workspace_id, item_id, kind, item_version, origin, status, ordered_at)
                VALUES (?, ?, ?, 'ITEM', 'v1', 'REQUEST', 'QUEUED', now())
                """, review, workspace, item);
        var app = PostgreSQLTestContainer.application();
        try (var connection = DriverManager.getConnection(app.jdbcUrl(), app.username(), app.password())) {
            var dataSource = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(dataSource);
            var owners = new OwnerScope(jdbc);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            long export = Objects.requireNonNull(transaction.execute(status -> {
                owners.ofJob(account);
                long id = Objects.requireNonNull(jdbc.queryForObject(
                        "INSERT INTO account_export(account_id) VALUES (?) RETURNING id", Long.class, account));
                assertThat(jdbc.queryForList("SELECT id FROM account_export WHERE id = ?", Long.class, id))
                        .containsExactly(id);
                return id;
            }));

            transaction.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT public.current_owner_account()", Long.class))
                        .isNull();
                assertThat(jdbc.queryForList(
                                "SELECT id FROM review WHERE workspace_id = ? AND id = ?",
                                UUID.class,
                                workspace,
                                review))
                        .containsExactly(review);
                assertThat(jdbc.queryForList("SELECT id FROM account_export WHERE id = ?", Long.class, export))
                        .isEmpty();
            });
        }
    }
}
