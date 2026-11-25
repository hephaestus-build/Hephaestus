package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.privacy.spi.NativeId;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.ResearchSubjectReference;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class PrivacyJournalIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PrivacyJournal journal;

    @Autowired
    private PrivacyJournalRetention retention;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private PersonDataRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @AfterEach
    void clearBackupObservation() {
        PostgreSQLTestContainer.ownerJdbc().update("DELETE FROM privacy_journal_backup_horizon WHERE id = 1");
    }

    @Test
    void shouldKeepFactsAndSignalAnUnavailableBackupHorizon() {
        var now = Instant.now();
        var old = journalFactAt(now.minus(Duration.ofDays(100)));

        clearBackupObservation();
        assertThat(retention.prune(now)).isZero();
        assertThat(count(old)).isOne();
        assertThat(backupHorizonUnavailable()).isEqualTo(1);

        observeBackup(null, now);
        assertThat(retention.prune(now)).isZero();
        assertThat(count(old)).isOne();
        assertThat(backupHorizonUnavailable()).isEqualTo(1);

        observeBackup(now.minus(Duration.ofDays(5)), now.minus(Duration.ofHours(31)));
        assertThat(retention.prune(now)).isZero();
        assertThat(count(old)).isOne();
        assertThat(backupHorizonUnavailable()).isEqualTo(1);

        var beforeBackupButWithinAgeLimit = journalFactAt(now.minus(Duration.ofDays(20)));
        observeBackup(now.minus(Duration.ofDays(5)), now);
        retention.prune(now);
        assertThat(count(old)).isZero();
        assertThat(count(beforeBackupButWithinAgeLimit)).isZero();
        assertThat(backupHorizonUnavailable()).isZero();
    }

    @Test
    void shouldKeepAnOldFactWhileABackupIsReplacingTheReport() throws Exception {
        var now = Instant.now();
        var old = journalFactAt(now.minus(Duration.ofDays(100)));
        observeBackup(now.minus(Duration.ofDays(5)), now.minusSeconds(1));

        var reporter = HeldTransaction.holdIn(transactions, jdbc, () -> jdbc.execute("""
            SELECT pg_advisory_xact_lock(hashtext('hephaestus'), hashtext('privacy-journal-backup-horizon'))
            """));
        var sweep = CompletableFuture.supplyAsync(() -> retention.prune(now));
        reporter.commitAfter(sweep);

        assertThat(sweep.get(20, TimeUnit.SECONDS)).isZero();
        assertThat(count(old)).isOne();
    }

    @Test
    void shouldKeepTheReportStableWhileTheSweepWaitsForAJournalRow() throws Exception {
        var now = Instant.now();
        var old = journalFactAt(now.minus(Duration.ofDays(100)));
        observeBackup(now.minus(Duration.ofDays(5)), now.minusSeconds(1));

        var row = HeldTransaction.holdIn(
                transactions,
                jdbc,
                () -> jdbc.queryForObject(
                        "SELECT id FROM privacy_journal WHERE entry_id = ? FOR UPDATE", Long.class, old));
        var sweep = CompletableFuture.supplyAsync(() -> retention.prune(now));
        row.commitAfter(
                sweep, () -> assertThat(jdbc.queryForObject("""
            SELECT pg_try_advisory_xact_lock(hashtext('hephaestus'), hashtext('privacy-journal-backup-horizon'))
            """, Boolean.class)).isFalse());

        sweep.get(20, TimeUnit.SECONDS);
        assertThat(count(old)).isZero();
    }

    @Test
    void shouldPruneByAgeWhenBackupsAreExplicitlyOff() {
        var now = Instant.now();
        var old = journalFactAt(now.minus(Duration.ofDays(36)));
        var recent = journalFactAt(now.minus(Duration.ofDays(34)));
        var withoutBackups = new PrivacyJournalRetention(
                jdbc,
                Clock.systemUTC(),
                new PrivacyJournalProperties(Duration.ofDays(35), false),
                meters,
                transactions);

        withoutBackups.prune(now);

        assertThat(count(old)).isZero();
        assertThat(count(recent)).isOne();
    }

    @Test
    void shouldKeepFactsAtAndAfterTheOldestBackup() {
        var now = Instant.now();
        var oldestBackup = now.minusSeconds(100L * 86400);
        var before = UUID.randomUUID();
        var at = UUID.randomUUID();
        var after = UUID.randomUUID();
        for (var item : Map.of(before, -1, at, 0, after, 1).entrySet()) {
            jdbc.update("""
                INSERT INTO privacy_journal(entry_id, kind, refs, retention_observed_at)
                VALUES (?, 'ACCOUNT_ERASED', '{"accountId":37}', ?)
                """, item.getKey(), Timestamp.from(oldestBackup.plusSeconds(item.getValue())));
        }

        // A failed rotation can leave an old backup in the repository even though the report is fresh.
        observeBackup(oldestBackup, now);
        retention.prune(now);

        assertThat(jdbc.queryForList(
                        "SELECT entry_id FROM privacy_journal WHERE entry_id IN (?,?,?)",
                        UUID.class,
                        before,
                        at,
                        after))
                .containsExactlyInAnyOrderElementsOf(List.of(at, after));
    }

    @Test
    void shouldKeepAFactWhoseTransactionCommitsAfterTheBackupPoint() {
        var id = UUID.randomUUID();
        var backup = Objects.requireNonNull(transactions.execute(status -> {
            journal.record(id, PrivacyJournalKind.ACCOUNT_ERASED, Map.of("accountId", 37L));
            return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        }));
        assertThat(jdbc.queryForList(
                        "SELECT recorded_at < ? FROM privacy_journal WHERE entry_id = ?", Boolean.class, backup, id))
                .containsExactly(true);

        var now = backup.toInstant().plus(Duration.ofDays(100));
        observeBackup(backup.toInstant(), now);
        retention.prune(now);

        assertThat(jdbc.queryForList(
                        "SELECT retention_observed_at >= ? FROM privacy_journal WHERE entry_id = ?",
                        Boolean.class,
                        backup,
                        id))
                .containsExactly(true);
    }

    @Test
    void storesTheProviderIdInTheRecoveryFormat() {
        var id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> journal.record(
                id,
                PrivacyJournalKind.SLACK_CHANNEL_ERASED,
                Map.of("workspaceId", 37L, "channelId", new NativeId("C723"))));

        assertThat(jdbc.queryForList(
                        "SELECT refs->>'channelId' FROM privacy_journal WHERE entry_id = ?", String.class, id))
                .containsExactly("C723");
    }

    @Test
    void shouldRollBackTheFactWithItsCause() {
        var id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            journal.record(
                    id, PrivacyJournalKind.ENTRY_DELETED, Map.of("workspaceId", 37L, "entryId", UUID.randomUUID()));
            assertThat(count(id)).isOne();
            status.setRollbackOnly();
        });
        assertThat(count(id)).isZero();
    }

    @Test
    void shouldExportOnlyTheAccountsRecoveryFactsAndKeepThemOnErasure() {
        var entryId = UUID.randomUUID();
        var releaseId = UUID.randomUUID();
        var someoneElse = UUID.randomUUID();
        var now = Instant.now();
        transactions.executeWithoutResult(status -> {
            journal.record(
                    entryId, PrivacyJournalKind.MEMBERSHIP_ENDED, Map.of("workspaceId", 357L, "accountId", 659L));
            journal.record(
                    someoneElse, PrivacyJournalKind.MEMBERSHIP_ENDED, Map.of("workspaceId", 357L, "accountId", 660L));
            journal.record(
                    releaseId,
                    PrivacyJournalKind.RESEARCH_DATASET_RELEASED,
                    Map.of(
                            "datasetId",
                            releaseId,
                            "workspaceId",
                            357L,
                            "receiptDigest",
                            "a".repeat(64),
                            "releasedAt",
                            now,
                            "useUntil",
                            now.plusSeconds(3600),
                            "subjectLinks",
                            List.of(new ResearchSubjectReference(659L, "released-row", 17L))));
        });
        var store = registry.stores().stream()
                .filter(s -> s.store().equals(CorePrivacyPersonDataCatalog.PRIVACY_JOURNAL))
                .findFirst()
                .orElseThrow();
        var scope = new PersonScope(659L, List.of(), List.of());
        var selected = store.select(scope);

        var fixtureIds = List.of(entryId.toString(), releaseId.toString(), someoneElse.toString());
        assertThat(store.export(selected, scope))
                .filteredOn(entry -> fixtureIds.contains(entry.path("entry_id").asString()))
                .allSatisfy(entry -> assertThat(entry.has("refs")).isFalse())
                .extracting(entry -> entry.path("entry_id").asString() + ":"
                        + entry.path("kind").asString())
                .containsExactly(entryId + ":MEMBERSHIP_ENDED");
        assertThat(store.erase(selected, scope)).isZero();
        assertThat(count(entryId)).isOne();
    }

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PersonDataService personData;

    @Test
    void shouldExportNativeErasureAndSlackWithdrawalOnlyForTheExactTeam() {
        var providerId = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .orElseGet(() ->
                        providers.saveAndFlush(new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com")))
                .getId());
        var erasure = UUID.randomUUID();
        var withdrawal = UUID.randomUUID();
        var otherTeam = UUID.randomUUID();
        var scope = new PersonScope(null, List.of(new PersonIdentity(providerId, "U123", "T123")), List.of());
        transactions.executeWithoutResult(status -> {
            journal.record(
                    erasure,
                    PrivacyJournalKind.PERSON_ERASED,
                    Map.of("identities", scope.identities(), "externalCopiesRemoved", false));
            journal.record(
                    otherTeam,
                    PrivacyJournalKind.PERSON_ERASED,
                    Map.of(
                            "identities",
                            List.of(new PersonIdentity(providerId, "U123", "T999")),
                            "externalCopiesRemoved",
                            false));
            journal.record(
                    withdrawal,
                    PrivacyJournalKind.SLACK_PARTICIPANT_WITHDRAWN,
                    Map.of(
                            "workspaceId",
                            357L,
                            "slackTeamId",
                            "T123",
                            "slackUserId",
                            "U123",
                            "scope",
                            "MONITORED_CHANNELS",
                            "decidedAt",
                            Instant.now()));
        });

        var store = registry.stores().stream()
                .filter(s -> s.store().equals(CorePrivacyPersonDataCatalog.PRIVACY_JOURNAL))
                .findFirst()
                .orElseThrow();
        var fixtureIds = List.of(erasure.toString(), withdrawal.toString(), otherTeam.toString());
        assertThat(store.export(store.select(scope), scope))
                .filteredOn(entry -> fixtureIds.contains(entry.path("entry_id").asString()))
                .allSatisfy(entry -> assertThat(entry.has("refs")).isFalse())
                .extracting(entry -> entry.path("entry_id").asString())
                .containsExactlyInAnyOrder(erasure.toString(), withdrawal.toString());
    }

    @Test
    void shouldRecordTheAcceptedErasureOnceWithItsRequestId() {
        var administrator = new Account("Recovery operator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        var operatorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var accountId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Erasure subject")).getId());
        var id = personData.preview(operatorId, accountId, List.of()).request().getId();

        personData.requestErasure(id, operatorId, false);
        personData.requestErasure(id, operatorId, false);

        assertThat(jdbc.queryForList(
                        "SELECT kind FROM privacy_journal WHERE entry_id = ? AND refs->>'accountId' = ?",
                        String.class,
                        id,
                        accountId.toString()))
                .containsExactly("PERSON_ERASED");
        assertThat(accounts.findById(accountId).orElseThrow().getStatus()).isEqualTo(Account.Status.DELETING);
    }

    @Test
    void shouldLogInternalRecoveryTargetsWithoutIdentityFieldsOrSubjectLinks() {
        var membership = UUID.randomUUID();
        var release = UUID.randomUUID();
        var erasure = UUID.randomUUID();
        var now = Instant.now();
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(DatabasePrivacyJournal.class);
        appender.start();
        logger.addAppender(appender);
        try {
            transactions.executeWithoutResult(status -> {
                journal.record(
                        membership,
                        PrivacyJournalKind.MEMBERSHIP_ENDED,
                        Map.of("accountId", 418L, "workspaceId", 357L));
                journal.record(
                        release,
                        PrivacyJournalKind.RESEARCH_DATASET_RELEASED,
                        Map.of(
                                "datasetId",
                                release,
                                "workspaceId",
                                357L,
                                "receiptDigest",
                                "a".repeat(64),
                                "releasedAt",
                                now,
                                "useUntil",
                                now.plusSeconds(3600),
                                "subjectLinks",
                                List.of(new ResearchSubjectReference(418L, "released-row", 17L))));
                journal.record(
                        erasure,
                        PrivacyJournalKind.PERSON_ERASED,
                        Map.of(
                                "identities",
                                List.of(new PersonDataRecovery.NativeIdentity(
                                        "SLACK", "https://slack.com", "native-person-42", "native-team-42")),
                                "externalCopiesRemoved",
                                false));
            });
        } finally {
            logger.detachAppender(appender);
        }
        assertLoggedIds(
                appender.list,
                membership,
                PrivacyJournalKind.MEMBERSHIP_ENDED,
                Map.of("accountId", 418L, "workspaceId", 357L));
        assertLoggedIds(
                appender.list,
                release,
                PrivacyJournalKind.RESEARCH_DATASET_RELEASED,
                Map.of("datasetId", release, "workspaceId", 357L));
        assertLoggedIds(appender.list, erasure, PrivacyJournalKind.PERSON_ERASED, Map.of());
    }

    private static void assertLoggedIds(
            List<ILoggingEvent> events, UUID id, PrivacyJournalKind kind, Map<String, Object> targets) {
        assertThat(events)
                .filteredOn(event -> event.getKeyValuePairs().stream()
                        .anyMatch(pair -> pair.key.equals("privacy.journal.entry.id") && id.equals(pair.value)))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getKeyValuePairs())
                            .extracting(pair -> pair.key)
                            .containsExactly(
                                    "event.name",
                                    "privacy.journal.kind",
                                    "privacy.journal.entry.id",
                                    "privacy.journal.target.ids");
                    assertThat(event.getKeyValuePairs())
                            .extracting(pair -> pair.value)
                            .containsExactly("privacy.journal.recorded", kind, id, targets);
                    assertThat(event.getFormattedMessage()).isEqualTo("Recorded a privacy fact");
                });
    }

    private void observeBackup(@Nullable Instant oldest, Instant observed) {
        PostgreSQLTestContainer.ownerJdbc()
                .update("""
            INSERT INTO privacy_journal_backup_horizon (id, observed_at, oldest_backup_at) VALUES (1, ?, ?)
            ON CONFLICT (id) DO UPDATE
            SET observed_at = excluded.observed_at, oldest_backup_at = excluded.oldest_backup_at
            """, Timestamp.from(observed), oldest == null ? null : Timestamp.from(oldest));
    }

    private UUID journalFactAt(Instant observed) {
        var entryId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO privacy_journal(entry_id, kind, refs, retention_observed_at)
            VALUES (?, 'ACCOUNT_ERASED', '{"accountId":37}', ?)
            """, entryId, Timestamp.from(observed));
        return entryId;
    }

    private double backupHorizonUnavailable() {
        return meters.get(CoreMetrics.PRIVACY_JOURNAL_BACKUP_HORIZON_UNAVAILABLE)
                .gauge()
                .value();
    }

    private int count(UUID id) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT count(*) FROM privacy_journal WHERE entry_id = ?", Integer.class, id));
    }
}
