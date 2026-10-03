package de.tum.cit.aet.hephaestus.core.auth.export;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Regression coverage for {@link AccountExportService#expireRetention()} — the work behind the hourly
 * {@link ExportRetentionSweeper}. {@code expireReadyBefore} is a bulk
 * {@code @Modifying(flushAutomatically = true)} UPDATE that needs an active transaction, and the
 * scheduler reaches it with none of its own, so the method's {@code @Transactional} is load-bearing.
 *
 * <p>The test drives {@code expireRetention()} with NO ambient transaction ({@link BaseIntegrationTest}
 * is not {@code @Transactional}) and asserts the committed database state. Dropping {@code @Transactional}
 * therefore resurfaces the {@code TransactionRequiredException} and fails the build, rather than letting
 * the sweep regress silently — a {@code @Transactional} test would mask it by supplying the transaction
 * the scheduler never does.
 */
class AccountExportRetentionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AccountExportService accountExportService;

    @Autowired
    private AccountExportRepository accountExportRepository;

    @Autowired
    private AccountRepository accountRepository;

    /** The same clock the service reads — fixtures are positioned relative to it. */
    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanState() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void expireRetention_expiresReadyExportPastRetention_andNullsPayload() {
        Long id = persistReadyExport(Instant.now(clock).minus(Duration.ofHours(1)));

        int expired = accountExportService.expireRetention();

        assertThat(expired).isEqualTo(1);
        AccountExport swept = accountExportRepository.findById(id).orElseThrow();
        assertThat(swept.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(swept.getPayload()).as("PII payload freed on expiry").isNull();
    }

    @Test
    void expireRetention_leavesReadyExportWithinRetention_untouched() {
        Long id = persistReadyExport(Instant.now(clock).plus(Duration.ofHours(1)));

        int expired = accountExportService.expireRetention();

        assertThat(expired).isZero();
        AccountExport kept = accountExportRepository.findById(id).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(AccountExport.Status.READY);
        assertThat(kept.getPayload()).isNotNull();
    }

    private Long persistReadyExport(Instant expiresAt) {
        Account account = new Account("Export Retention Subject");
        account.setPrimaryEmail("export-retention@test.local");
        account.setPrimaryEmailVerifiedAt(clock.instant());
        account.setStatus(Account.Status.ACTIVE);
        Long accountId = accountRepository.save(account).getId();
        assertThat(accountId).isNotNull();

        AccountExport export = new AccountExport(accountId);
        export.setStatus(AccountExport.Status.READY);
        export.setExpiresAt(expiresAt);
        export.setPayload("{\"pii\":true}".getBytes(UTF_8));
        Long exportId = accountExportRepository.save(export).getId();
        assertThat(exportId).isNotNull();
        return exportId;
    }
}
