package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeature;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExport;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
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

// Set deletion times relative to the production clock and bound cooldown; overriding the clock would change selection.
class AccountHardDeleteSweeperIntegrationTest extends BaseIntegrationTest {

    private static final String TOMBSTONE_DISPLAY_NAME = "deleted-account";

    @Autowired
    private AccountHardDeleteSweeper sweeper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountFeatureRepository accountFeatureRepository;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private IssuedJwtRepository issuedJwtRepository;

    @Autowired
    private AccountExportRepository accountExportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    /** The same clock the sweeper reads — fixtures are positioned relative to it. */
    @Autowired
    private Clock clock;

    @Autowired
    private AuthProperties authProperties;

    @BeforeEach
    void cleanState() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void sweepNow_pastCooldown_purgesChildrenClearsPiiAndFlipsToDeleted() {
        Account account = newAccount("Ada Lovelace", "ada@example.com");
        account.setTimeZone("Europe/Berlin");
        Long id = persistedId(accountRepository.save(account).getId());
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
        assertThat(tombstone.getTimeZone()).as("time zone cleared").isNull();

        assertThat(childRowCounts(id))
                .as("all personal/auth child rows are gone (account_feature, identity,"
                        + " issued_jwt, account_export)")
                .containsExactly(0, 0, 0, 0);
    }

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

    @Test
    void sweepNow_anonymizesSubjectAndImpersonatorAuditRows() {
        Account account = newAccount("Erased User", "erased@example.com");
        Long erasedId = persistedId(account.getId());
        // Row A: the erased user is the event SUBJECT. Row B: the erased user is the IMPERSONATOR
        // (acting_account_id) of an event about a different account → both must be redacted.
        seedAuthEvent(9001L, erasedId, null, "LOGIN", "203.0.113.7", "UA-A", "{\"x\":1}");
        seedAuthEvent(
                9002L,
                persistedId(newAccount("Operator", "operator@example.com").getId()),
                erasedId,
                "IMPERSONATION_BEGIN",
                "198.51.100.9",
                "UA-B",
                "{\"reason\":\"abuse\"}");
        markDeleting(
                erasedId, clock.instant().minus(authProperties.deleteCooldown()).minus(Duration.ofHours(1)));

        sweeper.sweepNow();

        var rows = jdbcTemplate.queryForList("SELECT event_type, result, ip_inet, user_agent, details FROM auth_event "
                + "WHERE id IN (9001,9002) ORDER BY id");
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("ip_inet")).as("IP redacted").isNull();
            assertThat(r.get("user_agent")).as("UA redacted").isNull();
            assertThat(r.get("details")).as("details redacted").isNull();
            assertThat(r.get("event_type")).as("skeleton kept").isNotNull();
            assertThat(r.get("result")).as("skeleton kept").isNotNull();
        });
    }

    @Test
    void shouldRedactAViewOfTheErasedUserWhenTheViewPredatesTheirSignIn() {
        Long erasedId = persistedId(
                newAccount("Viewed user", "viewed-erased@example.com").getId());
        var provider = provider();
        var viewed = users.saveAndFlush(TestUserFactory.createUser(77_777L, "viewed-erased", provider));
        var retained = users.saveAndFlush(TestUserFactory.createUser(66_666L, "viewed-retained", provider));
        long operator = persistedId(newAccount("Viewer", "viewer@example.com").getId());
        seedUserView(9012L, operator, persistedId(viewed.getId()), "{\"reason\":\"Before sign-in\"}");
        seedUserView(9011L, operator, persistedId(retained.getId()), "{\"reason\":\"Another user\"}");
        Identity linkedLater = new Identity();
        linkedLater.setAccount(accountRepository.findById(erasedId).orElseThrow());
        linkedLater.setProviderId(persistedId(provider.getId()));
        linkedLater.setProviderUserKey("77777");
        identityRepository.save(linkedLater);
        markDeleting(
                erasedId, clock.instant().minus(authProperties.deleteCooldown()).minus(Duration.ofHours(1)));

        sweeper.sweepNow();

        assertThat(jdbcTemplate.queryForObject("SELECT details::text FROM auth_event WHERE id = 9012", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT details::text FROM auth_event WHERE id = 9011", String.class))
                .contains("Another user");
    }

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
                .as("both a response and a decline are erased; another account's response is" + " untouched")
                .containsExactly(retainedResponse);
        assertThat(accountRepository.findById(erasedId).orElseThrow().getStatus())
                .isEqualTo(Account.Status.DELETED);
    }

    private UUID seedProductFeedback(Long accountId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO product_feedback (id, account_id, kind, message, app_version,"
                        + " created_at, submission_minute) VALUES (?, ?, 'FEEDBACK', 'Personal"
                        + " submission', 'test', now(), now())",
                id,
                accountId);
        return id;
    }

    private UUID seedSurveyParticipation(Long accountId, String status) {
        UUID id = UUID.randomUUID();
        UUID surveyId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO product_survey (id, title, description, questions_json, starts_at,"
                        + " active, created_at) VALUES (?, 'Survey', 'Description', CAST('[]' AS"
                        + " jsonb), now(), true, now())",
                surveyId);
        jdbcTemplate.update(
                "INSERT INTO product_survey_participation (id, survey_id, account_id, status,"
                        + " answers_json, invited_at, decided_at) VALUES (?, ?, ?, ?, CAST(? AS jsonb),"
                        + " now(), now())",
                id,
                surveyId,
                accountId,
                status,
                status.equals("RESPONDED") ? "[{\"questionId\":\"q\",\"text\":\"Personal response\"}]" : null);
        return id;
    }

    private void seedUserView(long id, long actingAccountId, long viewedActorId, String details) {
        jdbcTemplate.update(
                "INSERT INTO auth_event (id, occurred_at, acting_account_id, viewed_actor_id,"
                        + " event_type, result, details) VALUES (?, now(), ?, ?, 'USER_VIEW',"
                        + " 'SUCCESS', CAST(? AS jsonb))",
                id,
                actingAccountId,
                viewedActorId,
                details);
    }

    private void seedAuthEvent(
            long id,
            @Nullable Long accountId,
            @Nullable Long actingAccountId,
            String eventType,
            String ip,
            String userAgent,
            String details) {
        jdbcTemplate.update(
                "INSERT INTO auth_event (id, occurred_at, account_id, acting_account_id,"
                        + " event_type, result, ip_inet, user_agent, details) VALUES (?, now(), ?, ?,"
                        + " ?, 'SUCCESS', CAST(? AS inet), ?, CAST(? AS jsonb))",
                id,
                accountId,
                actingAccountId,
                eventType,
                ip,
                userAgent,
                details);
    }

    private IdentityProvider provider() {
        return providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
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
        accountFeatureRepository.save(new AccountFeature(accountId, "notification_access"));

        Identity link = new Identity();
        link.setAccount(accountRepository.findById(accountId).orElseThrow());
        link.setProviderId(persistedId(provider().getId()));
        link.setProviderUserKey(Long.toString(100_000L + accountId));
        identityRepository.save(link);

        IssuedJwt jwt =
                new IssuedJwt(UUID.randomUUID(), accountId, clock.instant().plus(Duration.ofHours(1)));
        jwt.setIssuedAt(clock.instant());
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

    // Read JDBC deletion results from the database rather than the persistence context.
    private List<Integer> childRowCounts(Long accountId) {
        return List.of(
                count("account_feature", accountId),
                count("identity", accountId),
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
