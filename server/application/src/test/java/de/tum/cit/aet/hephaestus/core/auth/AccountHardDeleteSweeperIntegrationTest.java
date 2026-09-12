package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeature;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExport;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression coverage for the GDPR Art. 17 hard-delete sweeper ({@link AccountHardDeleteSweeper}).
 *
 * <p>The sweeper is the enforcement half of {@code AccountService.softDelete}: an account sits in
 * {@link Account.Status#DELETING} during the recovery window and, once {@code deleted_at} is older
 * than {@code hephaestus.auth.delete-cooldown}, its personal/auth child rows are purged and the row
 * is flipped to the terminal {@link Account.Status#DELETED} with PII cleared. This test pins that
 * contract against <b>observable database state</b> so a regression in the selection query, the
 * child-row deletes, the PII clearing, or the status flip fails the build.
 *
 * <h2>Time control</h2>
 * The production {@code clock} bean ({@code Clock.systemUTC()}) is used as-is; we never override
 * it. Instead each fixture sets {@code deleted_at} relative to the very clock the sweeper reads
 * (autowired below), and fixtures use the bound cooldown value.
 */
class AccountHardDeleteSweeperIntegrationTest extends BaseIntegrationTest {

    /** Tombstone left on display_name (see {@code AccountHardDeleteSweeper.TOMBSTONE_DISPLAY_NAME}). */
    private static final String TOMBSTONE_DISPLAY_NAME = "deleted-account";

    @Autowired
    private AccountHardDeleteSweeper sweeper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountFeatureRepository accountFeatureRepository;

    @Autowired
    private IdentityLinkRepository identityLinkRepository;

    @Autowired
    private IssuedJwtRepository issuedJwtRepository;

    @Autowired
    private AccountExportRepository accountExportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The same clock the sweeper reads — fixtures are positioned relative to it. */
    @Autowired
    private Clock clock;

    @Autowired
    private AuthProperties authProperties;

    @BeforeEach
    void cleanState() {
        databaseTestUtils.cleanDatabase();
    }

    // ── Case 1: account past the cooldown is fully purged ────────────────────────────────────

    @Test
    void sweepNow_pastCooldown_purgesChildrenClearsPiiAndFlipsToDeleted() {
        // Arrange: an account soft-deleted well before the cutoff, with one row in every child table.
        Account account = newAccount("Ada Lovelace", "ada@example.com");
        Long id = persistedId(account.getId());
        seedChildRows(id);
        // deleted_at = now - (cooldown + 1h) → strictly older than the cutoff → must be swept.
        markDeleting(id, clock.instant().minus(authProperties.deleteCooldown()).minus(Duration.ofHours(1)));

        assertThat(childRowCounts(id))
                .as("precondition: all four child tables seeded")
                .containsExactly(1, 1, 1, 1);

        // Act
        int purged = sweeper.sweepNow();

        // Assert: return count + every observable side effect.
        assertThat(purged)
                .as("exactly one account past the cooldown was purged")
                .isEqualTo(1);

        Account tombstone = accountRepository.findById(id).orElseThrow();
        assertThat(tombstone.getStatus())
                .as("status flipped to terminal DELETED")
                .isEqualTo(Account.Status.DELETED);
        assertThat(tombstone.getDisplayName()).as("display_name tombstoned").isEqualTo(TOMBSTONE_DISPLAY_NAME);
        assertThat(tombstone.getPrimaryEmail()).as("primary_email PII cleared").isNull();
        assertThat(tombstone.getPrimaryEmailVerifiedAt())
                .as("email-verified PII cleared")
                .isNull();

        assertThat(childRowCounts(id))
                .as(
                        "all personal/auth child rows are gone (account_feature, identity_link, issued_jwt, account_export)")
                .containsExactly(0, 0, 0, 0);
    }

    // ── Case 2: account still inside the cooldown is left untouched ───────────────────────────

    @Test
    void sweepNow_withinCooldown_leavesAccountAndChildrenIntact() {
        Account account = newAccount("Grace Hopper", "grace@example.com");
        Long id = persistedId(account.getId());
        seedChildRows(id);
        // One hour ago is inside the configured recovery window.
        markDeleting(id, clock.instant().minus(Duration.ofHours(1)));

        int purged = sweeper.sweepNow();

        assertThat(purged)
                .as("an account still in cooldown is excluded from the count")
                .isZero();

        Account stillDeleting = accountRepository.findById(id).orElseThrow();
        assertThat(stillDeleting.getStatus()).as("status stays DELETING").isEqualTo(Account.Status.DELETING);
        assertThat(stillDeleting.getDisplayName()).as("display_name untouched").isEqualTo("Grace Hopper");
        assertThat(stillDeleting.getPrimaryEmail())
                .as("primary_email untouched")
                .isEqualTo("grace@example.com");

        assertThat(childRowCounts(id))
                .as("child rows are intact while in cooldown")
                .containsExactly(1, 1, 1, 1);
    }

    // ── Case 3: terminal DELETED is never re-selected; the sweep is idempotent ────────────────

    @Test
    void sweepNow_alreadyDeleted_isNeverReselected_andSweepIsIdempotent() {
        Account account = newAccount("Alan Turing", "alan@example.com");
        Long id = persistedId(account.getId());
        seedChildRows(id);
        markDeleting(id, clock.instant().minus(authProperties.deleteCooldown()).minus(Duration.ofHours(1)));

        // First sweep purges it.
        assertThat(sweeper.sweepNow()).isEqualTo(1);
        assertThat(accountRepository.findById(id).orElseThrow().getStatus()).isEqualTo(Account.Status.DELETED);

        // Second sweep: the DELETED tombstone is no longer DELETING, so it is not re-selected.
        int second = sweeper.sweepNow();

        assertThat(second)
                .as("re-running the sweep over a DELETED tombstone is a no-op")
                .isZero();
        // The tombstone is unchanged and its children stay gone.
        Account tombstone = accountRepository.findById(id).orElseThrow();
        assertThat(tombstone.getStatus()).isEqualTo(Account.Status.DELETED);
        assertThat(tombstone.getDisplayName()).isEqualTo(TOMBSTONE_DISPLAY_NAME);
        assertThat(childRowCounts(id)).containsExactly(0, 0, 0, 0);
    }

    // ── Case 4: the cutoff is computed from delete-cooldown ───────────────────────────────────

    @Test
    void sweepNow_cutoffIsDrivenByDeleteCooldown_boundaryAccountsSplit() {
        // One account is just past the configured cooldown and one is just inside it.
        Account past = newAccount("Past Cutoff", "past@example.com");
        Long pastId = persistedId(past.getId());
        markDeleting(
                pastId, clock.instant().minus(authProperties.deleteCooldown()).minus(Duration.ofMinutes(5)));

        Account inside = newAccount("Inside Cutoff", "inside@example.com");
        Long insideId = persistedId(inside.getId());
        markDeleting(
                insideId, clock.instant().minus(authProperties.deleteCooldown()).plus(Duration.ofMinutes(5)));

        int purged = sweeper.sweepNow();

        assertThat(purged)
                .as("only the account older than the cooldown is swept")
                .isEqualTo(1);
        assertThat(accountRepository.findById(pastId).orElseThrow().getStatus()).isEqualTo(Account.Status.DELETED);
        assertThat(accountRepository.findById(insideId).orElseThrow().getStatus())
                .isEqualTo(Account.Status.DELETING);
    }

    // ── Case 5: GDPR erasure anonymizes the subject's retained audit rows ─────────────────────

    @Test
    void sweepNow_anonymizesSubjectAndImpersonatorAuditRows() {
        Account account = newAccount("Erased User", "erased@example.com");
        Long erasedId = persistedId(account.getId());
        // Row A: the erased user is the event SUBJECT. Row B: the erased user is the IMPERSONATOR
        // (acting_account_id) of an event about a different account → both must be redacted.
        seedAuthEvent(9001L, erasedId, null, "LOGIN", "203.0.113.7", "UA-A", "{\"x\":1}");
        seedAuthEvent(
                9002L, 88_888L, erasedId, "IMPERSONATION_BEGIN", "198.51.100.9", "UA-B", "{\"reason\":\"abuse\"}");
        markDeleting(
                erasedId, clock.instant().minus(authProperties.deleteCooldown()).minus(Duration.ofHours(1)));

        sweeper.sweepNow();

        var rows = jdbcTemplate.queryForList(
                "SELECT event_type, result, ip_inet, user_agent, details FROM auth_event "
                        + "WHERE account_id = ? OR acting_account_id = ? ORDER BY id",
                erasedId,
                erasedId);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("ip_inet")).as("IP redacted").isNull();
            assertThat(r.get("user_agent")).as("UA redacted").isNull();
            assertThat(r.get("details")).as("details redacted").isNull();
            assertThat(r.get("event_type")).as("skeleton kept").isNotNull();
            assertThat(r.get("result")).as("skeleton kept").isNotNull();
        });
    }

    // ── Case 6: product submissions the account owns are erased with it ───────────────────────

    @Test
    void shouldEraseProductSubmissionsWhenAccountIsPurged() {
        Long erasedId = persistedId(
                newAccount("Erased", "erased-submissions@example.com").getId());
        Long retainedId = persistedId(
                newAccount("Retained", "retained-submissions@example.com").getId());
        UUID erasedFeedback = seedProductFeedback(erasedId);
        UUID retainedFeedback = seedProductFeedback(retainedId);
        UUID erasedResponse = seedSurveyParticipation(erasedId, "RESPONDED");
        UUID erasedDecline = seedSurveyParticipation(erasedId, "DECLINED");
        UUID retainedResponse = seedSurveyParticipation(retainedId, "RESPONDED");
        markDeleting(
                erasedId, clock.instant().minus(authProperties.deleteCooldown()).minus(Duration.ofHours(1)));

        sweeper.sweepNow();

        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM product_feedback WHERE id IN (?, ?)",
                        UUID.class,
                        erasedFeedback,
                        retainedFeedback))
                .as("the erased account's feedback is gone and another account's is untouched")
                .containsExactly(retainedFeedback);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM product_survey_participation WHERE id IN (?, ?, ?)",
                        UUID.class,
                        erasedResponse,
                        erasedDecline,
                        retainedResponse))
                .as("both a response and a decline are erased; another account's response is untouched")
                .containsExactly(retainedResponse);
        assertThat(accountRepository.findById(erasedId).orElseThrow().getStatus())
                .isEqualTo(Account.Status.DELETED);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────

    private UUID seedProductFeedback(Long accountId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO product_feedback (id, account_id, kind, message, app_version, created_at, submission_minute) "
                        + "VALUES (?, ?, 'FEEDBACK', 'Personal submission', 'test', now(), now())",
                id,
                accountId);
        return id;
    }

    private UUID seedSurveyParticipation(Long accountId, String status) {
        UUID id = UUID.randomUUID();
        UUID surveyId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO product_survey (id, title, description, questions_json, starts_at, active, created_at) "
                        + "VALUES (?, 'Survey', 'Description', CAST('[]' AS jsonb), now(), true, now())",
                surveyId);
        jdbcTemplate.update(
                "INSERT INTO product_survey_participation (id, survey_id, account_id, status, answers_json, invited_at, decided_at) "
                        + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), now(), now())",
                id,
                surveyId,
                accountId,
                status,
                status.equals("RESPONDED") ? "[{\"questionId\":\"q\",\"text\":\"Personal response\"}]" : null);
        return id;
    }

    private void seedAuthEvent(
            long id,
            Long accountId,
            @Nullable Long actingAccountId,
            String eventType,
            String ip,
            String userAgent,
            String details) {
        jdbcTemplate.update(
                "INSERT INTO auth_event "
                        + "(id, occurred_at, account_id, acting_account_id, event_type, result, ip_inet, user_agent, details) "
                        + "VALUES (?, now(), ?, ?, ?, 'SUCCESS', CAST(? AS inet), ?, CAST(? AS jsonb))",
                id,
                accountId,
                actingAccountId,
                eventType,
                ip,
                userAgent,
                details);
    }

    /** Persists a real ACTIVE account via the production repository. */
    private Account newAccount(String displayName, String email) {
        Account account = new Account(displayName);
        account.setPrimaryEmail(email);
        account.setPrimaryEmailVerifiedAt(clock.instant());
        account.setStatus(Account.Status.ACTIVE);
        return accountRepository.save(account);
    }

    /** Persists one row in each child table that the sweeper purges, using real owned entities. */
    private void seedChildRows(Long accountId) {
        accountFeatureRepository.save(new AccountFeature(accountId, "mentor_access"));

        IdentityLink link = new IdentityLink();
        link.setAccount(accountRepository.findById(accountId).orElseThrow());
        link.setProviderId(1L); // scalar FK column (no JPA association → no ddl-auto FK constraint)
        link.setSubject("subject-" + accountId);
        link.setUsernameAtSignup("user" + accountId);
        identityLinkRepository.save(link);

        IssuedJwt jwt =
                new IssuedJwt(UUID.randomUUID(), accountId, clock.instant().plus(Duration.ofHours(1)));
        issuedJwtRepository.save(jwt);

        accountExportRepository.save(new AccountExport(accountId));
    }

    /**
     * Drives the account into the DELETING state with a chosen {@code deleted_at}. Mirrors what
     * {@code AccountService.softDelete} writes (status + deleted_at), but with explicit timing so
     * the fixture can sit either side of the cooldown cutoff deterministically.
     */
    private void markDeleting(Long accountId, Instant deletedAt) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        account.setStatus(Account.Status.DELETING);
        account.setDeletedAt(deletedAt);
        accountRepository.save(account);
    }

    /**
     * Observable child-row counts in fixed order: account_feature, identity_link, issued_jwt,
     * account_export. Read straight from the database (not the persistence context) so the
     * sweeper's JDBC deletes are reflected.
     */
    private List<Integer> childRowCounts(Long accountId) {
        return List.of(
                count("account_feature", accountId),
                count("identity_link", accountId),
                count("issued_jwt", accountId),
                count("account_export", accountId));
    }

    private int count(String table, Long accountId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE account_id = ?", Integer.class, accountId);
        return n == null ? 0 : n;
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}
