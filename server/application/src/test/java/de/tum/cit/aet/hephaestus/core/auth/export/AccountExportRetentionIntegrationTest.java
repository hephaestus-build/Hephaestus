package de.tum.cit.aet.hephaestus.core.auth.export;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Retention updates expired exports across accounts in a single sweep. */
class AccountExportRetentionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AccountExportService accountExportService;

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

        assertThat(accountExportService.expireRetention()).isGreaterThanOrEqualTo(2);

        for (long id : new long[] {first, second}) {
            assertThat(PostgreSQLTestContainer.ownerJdbc()
                            .queryForMap(
                                    "SELECT status, payload IS NULL AS cleared FROM account_export WHERE id = ?", id))
                    .containsEntry("status", "EXPIRED")
                    .containsEntry("cleared", true);
        }
    }

    @Test
    void shouldRefuseAnUnknownRuleAndKeepTheDownloadWindowEvenWithALaterCutoff() {
        Long id = persistReadyExport(Instant.now(clock).plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> jdbc.queryForObject(
                        "SELECT public.person_row_sweep('UNKNOWN', clock_timestamp())", Integer.class))
                .isInstanceOf(DataAccessException.class);
        jdbc.queryForObject(
                "SELECT public.person_row_sweep('EXPIRED_EXPORTS', 'infinity'::timestamptz)", Integer.class);

        assertThat(PostgreSQLTestContainer.ownerJdbc()
                        .queryForMap(
                                "SELECT status, payload IS NOT NULL AS retained FROM account_export WHERE id = ?", id))
                .containsEntry("status", "READY")
                .containsEntry("retained", true);
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
}
