package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class ResearchDisclosureDatabaseTest {
    private static final String MIGRATION = "1792533036123_changelog.xml";
    private static final String DISCLOSURE = "00000000-0000-0000-0000-000000978081";
    private static final String SUBJECT = "00000000-0000-0000-0000-000000978082";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepDisclosureSubjectsInTheirParentsWorkspaceAndRetainTheParent(String context) {
        var database = NativePracticeFixture.resultParent(context);
        PracticeMigrationFixture.execute(database, disclosure(978001));
        assertForeignKey(
                database,
                "research_disclosure",
                "sfk_research_disclosure_workspace",
                "FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT");
        assertForeignKey(
                database,
                "research_disclosure_subject",
                "fk_research_disclosure_subject_disclosure",
                "FOREIGN KEY (disclosure_id, workspace_id) REFERENCES research_disclosure(id, workspace_id)");

        assertViolation(database, subject(978002), "23503");
        PracticeMigrationFixture.execute(database, subject(978001));
        assertViolation(database, "DELETE FROM research_disclosure WHERE id='" + DISCLOSURE + "'", "23503");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT workspace_id::text FROM research_disclosure_subject WHERE id='" + SUBJECT + "'"))
                .containsExactly("978001");
        PracticeMigrationFixture.execute(
                database,
                "DELETE FROM research_disclosure_subject WHERE id='" + SUBJECT + "';"
                        + "DELETE FROM research_disclosure WHERE id='" + DISCLOSURE + "'");
        PracticeMigrationFixture.execute(database, disclosure(978002));
        assertViolation(database, "DELETE FROM workspace WHERE id=978002", "23001");
        PracticeMigrationFixture.execute(database, "DELETE FROM research_disclosure WHERE id='" + DISCLOSURE + "'");
        assertViolation(database, disclosure(987654321), "23503");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldEnforceTheMigratedDisclosureAndRemovalStateChecks(String context) {
        var database = NativePracticeFixture.resultParent(context);
        PracticeMigrationFixture.execute(database, disclosure(978001));
        PracticeMigrationFixture.execute(database, subject(978001));
        assertThat(PracticeMigrationFixture.column(database, """
                SELECT conname || ':' || convalidated::text FROM pg_constraint
                WHERE conrelid IN ('research_disclosure'::regclass,'research_disclosure_subject'::regclass)
                  AND contype='c' AND conname LIKE 'ck_research_disclosure%'
                ORDER BY conname
                """))
                .containsExactly(
                        "ck_research_disclosure_closure:true",
                        "ck_research_disclosure_counts:true",
                        "ck_research_disclosure_hash:true",
                        "ck_research_disclosure_purpose:true",
                        "ck_research_disclosure_schema:true",
                        "ck_research_disclosure_subject_grant:true",
                        "ck_research_disclosure_subject_key:true",
                        "ck_research_disclosure_subject_link:true",
                        "ck_research_disclosure_subject_pruned:true",
                        "ck_research_disclosure_subject_reason:true",
                        "ck_research_disclosure_subject_removed:true",
                        "ck_research_disclosure_use:true",
                        "ck_research_disclosure_window:true");
        for (String assignment : List.of(
                "purpose='TERMS'",
                "schema_name='other'",
                "schema_version=0",
                "window_from=window_to",
                "window_to=cutoff_at + interval '2 days'",
                "content_sha256='not-a-digest'",
                "participant_count=-1",
                "result_count=-1",
                "problem_count=-1",
                "feedback_count=-1",
                "feedback_excluded_count=-1",
                "use_until=created_at",
                "closure='DESTROYED'",
                "closed_at=now()",
                "closure='OTHER',closed_at=now()")) {
            assertViolation(
                    database,
                    "UPDATE research_disclosure SET " + assignment + " WHERE id='" + DISCLOSURE + "'",
                    "23514");
        }
        for (String assignment : List.of(
                "participant_key='not-a-participant-key'",
                "removal_required_at=now()",
                "removal_reason='OTHER',removal_required_at=now()",
                "account_id=NULL",
                "grant_decision_id=NULL",
                "removed_at=now()",
                "removed_at=now(),removal_reason='ACCOUNT_ERASED',removal_required_at=now()")) {
            assertViolation(
                    database,
                    "UPDATE research_disclosure_subject SET " + assignment + " WHERE id='" + SUBJECT + "'",
                    "23514");
        }
        // Erasure retains the grant and removal key; a completed downstream removal prunes both links.
        PracticeMigrationFixture.execute(
                database,
                "UPDATE research_disclosure_subject SET account_id=NULL,"
                        + "removal_reason='ACCOUNT_ERASED',removal_required_at=now() WHERE id='" + SUBJECT + "'");
        PracticeMigrationFixture.execute(
                database,
                "UPDATE research_disclosure_subject SET grant_decision_id=NULL," + "removed_at=now() WHERE id='"
                        + SUBJECT + "'");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (account_id IS NULL AND grant_decision_id IS NULL"
                                + " AND removed_at IS NOT NULL)::text FROM research_disclosure_subject WHERE id='"
                                + SUBJECT + "'"))
                .containsExactly("true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPreserveExistingAuditRowsAndAcceptResearchEventsOnCurrentAndFuturePartitions(String context)
            throws Exception {
        var database = beforeResearch(context);
        int id = 978100;
        for (AuthEvent.EventType type : AuthEvent.EventType.values()) {
            if (type == AuthEvent.EventType.RESEARCH_DATASET_RELEASED
                    || type == AuthEvent.EventType.RESEARCH_REMOVAL_RECORDED) continue;
            PracticeMigrationFixture.execute(database, audit(id++, type.name(), "now()"));
        }
        var before = PracticeMigrationFixture.column(
                database, "SELECT to_jsonb(e)::text FROM auth_event e WHERE id>=978100 AND id<978200 ORDER BY id");
        var history = PracticeMigrationFixture.column(
                database,
                "SELECT id || ':' || author || ':' || filename || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted");
        try (Liquibase liquibase = liquibase(database)) {
            liquibase.update(new Contexts(context), new LabelExpression());
        }
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT to_jsonb(e)::text FROM auth_event e WHERE id>=978100 AND id<978200 ORDER BY id"))
                .isEqualTo(before);
        var migratedHistory = PracticeMigrationFixture.column(
                database,
                "SELECT id || ':' || author || ':' || filename || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted");
        assertThat(migratedHistory.subList(0, history.size())).isEqualTo(history);
        PracticeMigrationFixture.execute(database, audit(978201, "RESEARCH_DATASET_RELEASED", "now()"));
        PracticeMigrationFixture.execute(database, audit(978202, "RESEARCH_REMOVAL_RECORDED", "now()"));
        assertViolation(database, audit(978203, "NOT_AN_EVENT", "now()"), "23514");
        PracticeMigrationFixture.execute(
                database,
                "SELECT partman.create_partition_time('public.auth_event', ARRAY['2101-01-01'::timestamptz])");
        PracticeMigrationFixture.execute(
                database, audit(978204, "RESEARCH_DATASET_RELEASED", "'2101-01-02'::timestamptz"));
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT (tableoid <> 'public.auth_event_default'::regclass)::text FROM auth_event WHERE id=978204"))
                .containsExactly("true");
    }

    @ParameterizedTest
    @CsvSource({
        "prod, RESEARCH_DATASET_RELEASED",
        "prod, RESEARCH_REMOVAL_RECORDED",
        "dev, RESEARCH_DATASET_RELEASED",
        "dev, RESEARCH_REMOVAL_RECORDED"
    })
    void shouldRestoreTheParentAuditConstraintOnlyWhenNoResearchEventsRemain(String context, String researchEvent)
            throws Exception {
        var database = beforeResearch(context);
        PracticeMigrationFixture.execute(database, audit(978100, "LOGIN", "now()"));
        PracticeMigrationFixture.execute(database, audit(978101, "PASSKEY_CHANGED", "now()"));
        PracticeMigrationFixture.execute(database, audit(978102, "PASSKEY_VERIFIED", "now()"));
        var oldRows = auditRows(database);
        var parentConstraint = auditConstraint(database);
        assertThat(parentConstraint).singleElement().asString().contains("PASSKEY_CHANGED", "PASSKEY_VERIFIED");
        try (var liquibase = liquibase(database)) {
            liquibase.update(throughResearch(liquibase, context), new Contexts(context), new LabelExpression());
        }
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT filename FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 1"))
                .singleElement()
                .asString()
                .endsWith(MIGRATION);
        var migratedHistory = migrationHistory(database);
        assertThat(auditRows(database)).isEqualTo(oldRows);
        assertViolation(database, audit(978103, "NOT_AN_EVENT", "now()"), "23514");

        // Only the final audit change rolls back; the disclosure schema remains applied.
        try (var liquibase = liquibase(database)) {
            liquibase.rollback(1, new Contexts(context), new LabelExpression());
        }
        assertThat(auditConstraint(database)).isEqualTo(parentConstraint);
        assertThat(auditRows(database)).isEqualTo(oldRows);
        assertThat(migrationHistory(database)).isEqualTo(migratedHistory.subList(0, migratedHistory.size() - 1));
        PracticeMigrationFixture.execute(database, audit(978104, "PASSKEY_CHANGED", "now()"));
        PracticeMigrationFixture.execute(database, audit(978105, "PASSKEY_VERIFIED", "now()"));
        assertViolation(database, audit(978106, "RESEARCH_DATASET_RELEASED", "now()"), "23514");
        assertViolation(database, audit(978107, "RESEARCH_REMOVAL_RECORDED", "now()"), "23514");
        assertViolation(database, audit(978108, "NOT_AN_EVENT", "now()"), "23514");

        try (var liquibase = liquibase(database)) {
            liquibase.update(throughResearch(liquibase, context), new Contexts(context), new LabelExpression());
        }
        assertThat(migrationHistory(database)).isEqualTo(migratedHistory);
        PracticeMigrationFixture.execute(database, audit(978109, researchEvent, "now()"));
        var retainedRows = auditRows(database);
        var widenedConstraint = auditConstraint(database);
        try (var liquibase = liquibase(database)) {
            assertThatThrownBy(() -> liquibase.rollback(1, new Contexts(context), new LabelExpression()))
                    .rootCause()
                    .isInstanceOfSatisfying(SQLException.class, failure -> {
                        assertThat(failure.getSQLState()).isEqualTo("P0001");
                        assertThat(failure.getMessage())
                                .startsWith("ERROR: Research audit rows exist; retain the widened audit constraint");
                    });
        }
        assertThat(auditRows(database)).isEqualTo(retainedRows);
        assertThat(auditConstraint(database)).isEqualTo(widenedConstraint);
        assertThat(migrationHistory(database)).isEqualTo(migratedHistory);
        assertViolation(database, audit(978110, "NOT_AN_EVENT", "now()"), "23514");
    }

    /** Counts pending changes through the research file, leaving later migrations unapplied. */
    private static int throughResearch(Liquibase liquibase, String context) throws Exception {
        var pending = liquibase.listUnrunChangeSets(new Contexts(context), new LabelExpression());
        return IntStream.range(0, pending.size())
                        .filter(i -> pending.get(i).getFilePath().endsWith(MIGRATION))
                        .max()
                        .orElseThrow()
                + 1;
    }

    private static List<@Nullable String> auditRows(TestDatabase database) {
        return PracticeMigrationFixture.column(
                database, "SELECT to_jsonb(e)::text FROM auth_event e WHERE id>=978100 AND id<978200 ORDER BY id");
    }

    private static List<@Nullable String> auditConstraint(TestDatabase database) {
        return PracticeMigrationFixture.column(database, """
                SELECT convalidated::text || ':' || pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid='public.auth_event'::regclass AND conname='ck_auth_event_event_type' AND contype='c'
                """);
    }

    private static List<@Nullable String> migrationHistory(TestDatabase database) {
        return PracticeMigrationFixture.column(
                database,
                "SELECT id || ':' || author || ':' || filename || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted");
    }

    private static String disclosure(long workspaceId) {
        return """
                INSERT INTO research_disclosure(id,workspace_id,purpose,schema_name,schema_version,
                    window_from,window_to,cutoff_at,notice_version,research_organization,created_at,
                    content_sha256,participant_count,result_count,problem_count,feedback_count,
                    feedback_excluded_count,use_until)
                VALUES ('%s',%d,'RESEARCH_PARTICIPATION','hephaestus.research-dataset',1,
                    '2026-10-01','2026-10-02','2026-10-02','2026-10-01','Research organisation','2026-10-02',
                    repeat('a',64),1,1,1,0,0,'2026-11-01')
                """.formatted(DISCLOSURE, workspaceId);
    }

    private static String subject(long workspaceId) {
        return "INSERT INTO research_disclosure_subject(id,disclosure_id,workspace_id,account_id,participant_key,grant_decision_id)"
                + " VALUES ('" + SUBJECT + "','" + DISCLOSURE + "'," + workspaceId
                + ",978003,'p_aaaaaaaaaaaaaaaaaaaaaaaaaa',978007)";
    }

    private static String audit(int id, String event, String at) {
        return "INSERT INTO auth_event(id,occurred_at,event_type,result,details) VALUES (" + id + "," + at + ",'"
                + event + "','SUCCESS','native research migration witness')";
    }

    private static TestDatabase beforeResearch(String context) throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "research_upgrade_" + UUID.randomUUID().toString().replace("-", ""));
        try (Liquibase liquibase = liquibase(database)) {
            var pending = liquibase.listUnrunChangeSets(new Contexts(context), new LabelExpression());
            int index = IntStream.range(0, pending.size())
                    .filter(i -> pending.get(i).getFilePath().endsWith(MIGRATION))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(index, new Contexts(context), new LabelExpression());
        }
        return database;
    }

    private static Liquibase liquibase(TestDatabase database) throws Exception {
        var liquibase = new Liquibase(
                "db/master.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(DriverManager.getConnection(
                                database.jdbcUrl(), database.username(), database.password()))));
        // This fixture resumes a deliberately partial history before the production entry boundary.
        liquibase
                .getDatabaseChangeLog()
                .getPreconditions()
                .getNestedPreconditions()
                .clear();
        return liquibase;
    }

    private static void assertForeignKey(TestDatabase database, String table, String name, String definition) {
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT convalidated::text || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='public."
                                + table + "'::regclass AND contype='f' AND conname='" + name + "'"))
                .containsExactly("true:" + definition);
    }

    private static void assertViolation(TestDatabase database, String sql, String sqlState) {
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, sql))
                .rootCause()
                .isInstanceOfSatisfying(
                        SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo(sqlState));
    }
}
