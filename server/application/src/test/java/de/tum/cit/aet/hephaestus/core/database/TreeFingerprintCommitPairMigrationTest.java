package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceFingerprint;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.io.InputStream;
import java.sql.DriverManager;
import java.util.Objects;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A capture recorded before the checkout held only its commit pair keeps a tree fingerprint that the server can no
 * longer compute. The migration gives it the fingerprint of its review's pinned pair, so its result is not read as
 * changed work.
 */
@Tag("database")
class TreeFingerprintCommitPairMigrationTest {
    private static final String CHANGELOG = "20261117171358-fix-tree-fingerprint-is-the-commit-pair.xml";
    private static final long WORKSPACE = 997301;
    private static final long ITEM = 997302;
    private static final long PRACTICE = WORKSPACE + 200;
    private static final String TREE = "scm.repository.tree";
    private static final String OLD = "e".repeat(64);
    private static final String CORE = "c".repeat(64);

    @Test
    void shouldGiveEachPullRequestCaptureTheTreeFingerprintOfItsPinnedCommitPair() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "tree_fingerprint_migration_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE');
                INSERT INTO item(id,workspace_id,kind,source_id) VALUES(%2$d,%1$d,'scm.pull_request',42);
                """.formatted(WORKSPACE, ITEM));
        seedPractice(database);
        String base = "b".repeat(40);
        String head = "a".repeat(40);
        String pullRequest = review(database, "scm.pull_request:v1:%s:%s:%s".formatted(base, head, "d".repeat(64)));
        String scan = review(database, "scm.content:v1:" + "d".repeat(64));
        // A submitted review's version also names its own commit, after the head.
        String reviewHead = "f".repeat(40);
        String submitted = review(
                database,
                "scm.pull_request_review:v1:%s:%s:%s:%s".formatted(base, reviewHead, "9".repeat(40), "d".repeat(64)));
        String malformed = review(database, "scm.pull_request:v1:not-a-commit:" + head + ":" + "d".repeat(64));

        migrate(database);

        assertThat(fingerprint(database, pullRequest, TREE))
                .as("the fingerprint the server now computes for the pinned pair")
                .isEqualTo(SourceFingerprint.git(new SourceKind(TREE), GitRepositoryManager.treeIdentity(head, base)));
        assertThat(fingerprint(database, pullRequest, "scm.pull-request.core"))
                .as("other sources keep their fingerprints")
                .isEqualTo(CORE);
        assertThat(fingerprint(database, submitted, TREE))
                .as("the pull request's head, never the submitted review's own commit")
                .isEqualTo(SourceFingerprint.git(
                        new SourceKind(TREE), GitRepositoryManager.treeIdentity(reviewHead, base)));
        assertThat(fingerprint(database, scan, TREE))
                .as("a scan, which is never compared, is left as it was")
                .isEqualTo(OLD);
        assertThat(PracticeMigrationFixture.column(database, """
                        SELECT (capture_decision->'sourceFingerprints'->>'scm.repository.tree') IS NOT NULL
                        FROM review_practice
                        WHERE review_id='%s'
                        """.formatted(malformed)))
                .as("a pull request capture without a commit pair loses its tree key, never reads as changed")
                .containsExactly("f");
    }

    private static String review(TestDatabase database, String version) {
        String review = UUID.randomUUID().toString();
        PracticeMigrationFixture.execute(
                database, """
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at)
                VALUES('%1$s',%2$d,%3$d,'ITEM','%4$s','REQUEST','QUEUED',now());
                INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status,capture_decision)
                VALUES('%1$s',%2$d,%5$d,%5$d+1,'READY',
                    '{"sourceFingerprints":{"%6$s":"%7$s","scm.pull-request.core":"%8$s"}}');
                """.formatted(review, WORKSPACE, ITEM, version, PRACTICE, TREE, OLD, CORE));
        return review;
    }

    private static String fingerprint(TestDatabase database, String review, String kind) {
        return Objects.requireNonNull(PracticeMigrationFixture.column(database, """
                        SELECT capture_decision->'sourceFingerprints'->>'%s' FROM review_practice WHERE review_id='%s'
                        """.formatted(kind, review))
                .getFirst());
    }

    private static void seedPractice(TestDatabase database) throws Exception {
        String definition;
        try (InputStream input =
                Objects.requireNonNull(TreeFingerprintCommitPairMigrationTest.class.getResourceAsStream(
                        "/db/practice-retired/seeded-definitions.json"))) {
            definition = JsonMapper.builder()
                    .build()
                    .readTree(input)
                    .path("pr-scope")
                    .toString()
                    .replace("'", "''");
        }
        PracticeMigrationFixture.execute(database, """
                BEGIN;
                SET CONSTRAINTS ALL DEFERRED;
                INSERT INTO practice(id,workspace_id,slug,kind,current_version_id) VALUES(%1$d,%2$d,'pr-scope','scm.pull_request',%1$d+1);
                INSERT INTO practice_version(id,workspace_id,practice_id,number,definition) VALUES(%1$d+1,%2$d,%1$d,1,'%3$s');
                COMMIT;
                """.formatted(PRACTICE, WORKSPACE, definition));
    }

    private static void migrate(TestDatabase database) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/changelog/next/" + CHANGELOG,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts("prod"), new LabelExpression());
        }
    }
}
