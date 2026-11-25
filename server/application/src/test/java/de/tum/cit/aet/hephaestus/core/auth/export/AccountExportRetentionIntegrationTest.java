package de.tum.cit.aet.hephaestus.core.auth.export;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerSweeps;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepRule;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Retention expires the exports of every account in bounded batches, and skips an export that another session holds. */
class AccountExportRetentionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OwnerSweeps ownerSweeps;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /** The same clock the service reads — fixtures are positioned relative to it. */
    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanState() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void expireRetention_expiresReadyExportPastRetention_andNullsPayload() {
        Instant past = Instant.now(clock).minus(Duration.ofHours(1));
        Long first = persistReadyExport(past);
        Long second = persistReadyExport(past);

        assertThat(expireBatch()).isGreaterThanOrEqualTo(2);

        for (long id : new long[] {first, second}) {
            assertThat(PostgreSQLTestContainer.ownerJdbc()
                            .queryForMap(
                                    "SELECT status, payload IS NULL AS cleared FROM account_export WHERE id = ?", id))
                    .containsEntry("status", "EXPIRED")
                    .containsEntry("cleared", true);
        }
    }

    @Test
    void shouldExpireTheOtherExportsWhileAnotherSessionHoldsOneAndExpireItInTheNextRun() throws SQLException {
        Instant past = Instant.now(clock).minus(Duration.ofHours(1));
        long held = persistReadyExport(past);
        long other = persistReadyExport(past);

        TestDatabase owner = PostgreSQLTestContainer.owner();
        try (Connection session = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password())) {
            session.setAutoCommit(false);
            try (var lock = session.prepareStatement("SELECT id FROM account_export WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, held);
                lock.executeQuery().close();
            }

            expireBatch();

            assertExpired(other);
            assertThat(PostgreSQLTestContainer.ownerJdbc()
                            .queryForMap(
                                    "SELECT status, payload IS NOT NULL AS retained FROM account_export WHERE id = ?",
                                    held))
                    .containsEntry("status", "READY")
                    .containsEntry("retained", true);
            session.commit();
        }

        expireBatch();

        assertExpired(held);
    }

    @Test
    void shouldExpireAtMostOneBatchInATransactionAndTheRestInTheNextRun() {
        long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Export Retention Batch")).getId());
        List<Long> ids = PostgreSQLTestContainer.ownerJdbc()
                .query(
                        """
                        INSERT INTO account_export(account_id, status, expires_at, payload)
                        SELECT ?, 'READY', TIMESTAMPTZ '2000-01-01 00:00:00+00' + n * INTERVAL '1 second', ?
                        FROM generate_series(1, ?) n RETURNING id
                        """,
                        (rs, row) -> rs.getLong(1),
                        accountId,
                        "{\"pii\":true}".getBytes(UTF_8),
                        ExportRetentionSweeper.BATCH + 1);

        assertThat(expireBatch()).isEqualTo(ExportRetentionSweeper.BATCH);
        assertThat(readyAmong(ids)).isEqualTo(1);

        new ExportRetentionSweeper(ownerSweeps, new PrivacyJobMetrics(new SimpleMeterRegistry()), clock).sweep();

        assertThat(readyAmong(ids)).isZero();
    }

    @Test
    void shouldRefuseAnUnknownRuleAndKeepTheDownloadWindowEvenWithALaterCutoff() {
        Long id = persistReadyExport(Instant.now(clock).plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> jdbc.queryForObject(
                        "SELECT public.person_row_sweep('UNKNOWN', NULL, NULL, clock_timestamp())", Integer.class))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.queryForObject(
                        "SELECT public.person_row_sweep('EXPIRED_EXPORTS', NULL::timestamptz)", Integer.class))
                .isInstanceOf(DataAccessException.class);
        jdbc.queryForObject(
                "SELECT public.person_row_sweep('EXPIRED_EXPORTS', NULL, NULL, 'infinity'::timestamptz)",
                Integer.class);

        assertThat(PostgreSQLTestContainer.ownerJdbc()
                        .queryForMap(
                                "SELECT status, payload IS NOT NULL AS retained FROM account_export WHERE id = ?", id))
                .containsEntry("status", "READY")
                .containsEntry("retained", true);
    }

    private void assertExpired(long id) {
        assertThat(PostgreSQLTestContainer.ownerJdbc()
                        .queryForMap("SELECT status, payload IS NULL AS cleared FROM account_export WHERE id = ?", id))
                .containsEntry("status", "EXPIRED")
                .containsEntry("cleared", true);
    }

    private long readyAmong(List<Long> ids) {
        return Objects.requireNonNull(PostgreSQLTestContainer.ownerJdbc()
                .queryForObject(
                        "SELECT count(*) FROM account_export WHERE id = ANY(?) AND status = 'READY'",
                        Long.class,
                        (Object) ids.toArray(Long[]::new)));
    }

    private Long persistReadyExport(Instant expiresAt) {
        Account account = new Account("Export Retention Subject");
        long accountId = Objects.requireNonNull(accountRepository.save(account).getId());
        return Objects.requireNonNull(PostgreSQLTestContainer.ownerJdbc()
                .queryForObject(
                        """
                        INSERT INTO account_export(account_id, status, expires_at, payload)
                        VALUES (?, 'READY', ?, ?) RETURNING id
                        """, Long.class, accountId, Timestamp.from(expiresAt), "{\"pii\":true}".getBytes(UTF_8)));
    }

    private int expireBatch() {
        return ownerSweeps.expire(RowSweepRule.EXPIRED_EXPORTS, Instant.now(clock));
    }
}
