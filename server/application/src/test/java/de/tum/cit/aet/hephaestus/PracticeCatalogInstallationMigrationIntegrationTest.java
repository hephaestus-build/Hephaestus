package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.evidence.RequiredCaptureQuality;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptionsFixture;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Tag("database")
class PracticeCatalogInstallationMigrationIntegrationTest {

    static {
        System.setProperty("liquibase.validateXmlChangelogFiles", "false");
    }

    private static final String MARKER_CHANGELOG = "1785274902740_changelog.xml";
    private static final String BEFORE_CATALOG_TAG = "before-instance-curated-catalog";
    private static final String MASTER = "db/practice-catalog-installation-migration-test.xml";

    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("hephaestus_practice_catalog_installation_migration");

    @Test
    void shouldMigrateCatalogStateAndWorkspaceRevisionPointers() throws Exception {
        updateUntilBefore(MARKER_CHANGELOG);
        seedExistingWorkspaces();
        updateOnly(MARKER_CHANGELOG);
        execute("DELETE FROM practice_catalog_installation WHERE workspace_id = 136104");
        execute("""
            UPDATE practice_catalog_installation
            SET installed_at = (
                SELECT dateexecuted + INTERVAL '1 second'
                FROM databasechangelog
                WHERE id = '1785274902740-4' AND author = 'hephaestus'
            )
            WHERE workspace_id = 136103
            """);

        try (Liquibase liquibase = liquibase()) {
            liquibase.tag(BEFORE_CATALOG_TAG);
        }
        updateThrough("1785743133884-4");
        execute(
                "UPDATE practice_revision SET detection_fingerprint = repeat('a', 64) "
                        + "WHERE practice_id = 136301 AND revision_number = "
                        + "(SELECT max(revision_number) FROM practice_revision WHERE practice_id = 136301)",
                "UPDATE practice SET source_curated_slug = 'second-practice', "
                        + "source_curated_fingerprint = repeat('c', 64) WHERE id = 136302");
        // Asserted here, not at the end of the chain: later change sets deliberately clear the
        // review-rule fingerprint, so a stored one is no longer observable once they have run.
        updateThrough("1785743133884-5");
        assertHistoricalFingerprintsVersioned();
        seedLegacyAuditVocabulary();
        seedLegacyCuratedOverrides();
        try (Liquibase liquibase = liquibase()) {
            liquibase.update(contexts());
        }

        assertLegacyCuratedDigestsVersioned();
        assertHistoricalPoliciesCanBeExplicitlyRepinned();
        assertMarkerRepair();
        assertHistoricalFingerprintsClearedForRecomputation();
        assertAuditHistoryPreserved();
        assertCatalogBootstrap(3, 1);
        assertWorkspaceRevisionBackfill();
        assertEvidenceRevisionBackfill();
        assertPointerOwnership();
        assertControlledProvenanceUpdate();
        assertAggregateDeletion();
        assertProjectionInvariant();
        assertRevisionImmutability();
        appendValidCurrentRevision();
        assertRollbackAndReapply();
    }

    private static void seedLegacyAuditVocabulary() throws SQLException {
        execute("""
            INSERT INTO config_audit_event (
                occurred_at, workspace_id, actor_kind, entity_type, entity_id,
                action, changed_keys, old_value, new_value
            ) VALUES (
                now(), 136103, 'SYSTEM', 'PRACTICE_ACTIVE', '136301',
                'UPDATED', ARRAY['active'], '{"active":false}'::jsonb, '{"active":true}'::jsonb
            ), (
                now(), 136103, 'SYSTEM', 'AGENT_BINDING', 'PRACTICE_DETECTION',
                'UPDATED', ARRAY['purpose'], '{"purpose":"PRACTICE_DETECTION"}'::jsonb,
                '{"purpose":"PRACTICE_DETECTION"}'::jsonb
            )
            """);
    }

    private static void seedLegacyCuratedOverrides() throws SQLException {
        execute("""
            INSERT INTO curated_area_override (
                slug, name, description, based_on_digest, created_at, updated_at, version
            ) VALUES (
                'real-area', 'Real area override', 'Preserve this curated intent', repeat('a', 64), now(), now(), 0
            )
            """, """
            INSERT INTO curated_practice_override (
                slug, name, applies_to, trigger_events, criteria, automated_review_policy,
                based_on_digest, created_at, updated_at, version
            ) VALUES
            (
                'real-practice', 'Real practice override', 'PULL_REQUEST', '[\"READY_FOR_REVIEW\"]'::jsonb,
                'Keep the effective override',
                '{"sourceContractVersion":"1.0.0","automatedReview":{"mode":"LANGUAGE_MODEL","evidenceSufficiency":"SUFFICIENT_WHEN_REQUIREMENTS_MET"},"requiredEvidence":[{"sourceKind":"scm.pull-request.core","completeness":"COMPLETE","freshness":"CURRENT"},{"sourceKind":"scm.pull-request.diff","completeness":"COMPLETE","freshness":"CURRENT"}],"optionalContext":[{"sourceKind":"scm.pull-request.comments"}],"whenEvidenceIsInsufficient":"SKIP_AUTOMATED_REVIEW","knownLimitations":[]}'::jsonb,
                repeat('b', 64), now(), now(), 0
            ), (
                'legacy-issue', 'Legacy issue override', 'ISSUE', '[\"IssueCreated\"]'::jsonb,
                'Preserve issue intent',
                '{"sourceContractVersion":"1.0.0","automatedReview":{"mode":"LANGUAGE_MODEL","evidenceSufficiency":"SUFFICIENT_WHEN_REQUIREMENTS_MET"},"requiredEvidence":[{"sourceKind":"scm.issue.core","completeness":"COMPLETE","freshness":"CURRENT"}],"optionalContext":[{"sourceKind":"scm.issue.comments"}],"whenEvidenceIsInsufficient":"SKIP_AUTOMATED_REVIEW","knownLimitations":[]}'::jsonb,
                NULL, now(), now(), 0
            ), (
                'legacy-conversation', 'Legacy conversation override', 'CONVERSATION_THREAD', '[]'::jsonb,
                'Preserve conversation intent',
                '{"sourceContractVersion":"1.0.0","automatedReview":{"mode":"LANGUAGE_MODEL","evidenceSufficiency":"SUFFICIENT_WHEN_REQUIREMENTS_MET"},"requiredEvidence":[{"sourceKind":"slack.conversation.thread","completeness":"COMPLETE","freshness":"CURRENT"}],"optionalContext":[],"whenEvidenceIsInsufficient":"SKIP_AUTOMATED_REVIEW","knownLimitations":[]}'::jsonb,
                NULL, now(), now(), 0
            )
            """);
    }

    private static void assertHistoricalPoliciesCanBeExplicitlyRepinned() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        var sources = new ClasspathArtifactSourceCatalogRegistry(mapper, Clock.systemUTC());
        var validator = new PracticeDefinitionValidator(sources, PracticeSignalOptionsFixture.real());
        int validated = 0;
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT slug, name, bindings::text, criteria, automated_review_policy::text, area_slug "
                                + "FROM curated_practice_override ORDER BY slug")) {
            while (rows.next()) {
                List<PracticeBinding> bindings =
                        mapper.readValue(rows.getString("bindings"), new TypeReference<List<PracticeBinding>>() {});
                PracticeAutomatedReviewPolicy policy = mapper.readValue(
                        rows.getString("automated_review_policy"), PracticeAutomatedReviewPolicy.class);
                // Historical definitions remain editable; capture requires an explicitly updated contract.
                assertThat(policy.sourceContractVersion().value()).isEqualTo("1.0.0");
                PracticeDefinition historical = new PracticeDefinition(
                        rows.getString("name"),
                        bindings,
                        rows.getString("criteria"),
                        null,
                        policy,
                        null,
                        null,
                        rows.getString("area_slug"));
                validator.validate(historical);
                var repinnedPolicy = new PracticeAutomatedReviewPolicy(
                        sources.current().version(),
                        policy.automatedReview(),
                        policy.whenEvidenceIsInsufficient(),
                        policy.knownLimitations(),
                        policy.insufficiencyReason());
                validator.validate(new PracticeDefinition(
                        rows.getString("name"),
                        bindings,
                        rows.getString("criteria"),
                        null,
                        repinnedPolicy,
                        null,
                        null,
                        rows.getString("area_slug")));
                if (rows.getString("slug").equals("real-practice")) {
                    assertThat(bindings).flatExtracting(PracticeBinding::needs).anySatisfy(need -> {
                        assertThat(need.sourceKind()).isEqualTo(new SourceKind("scm.pull-request.diff"));
                        assertThat(need.stance()).isEqualTo(EvidenceStance.REQUIRED);
                    });
                }
                validated++;
            }
        }
        assertThat(validated).isEqualTo(3);
        assertThat(scalar("SELECT criteria FROM curated_practice_override WHERE slug = 'real-practice'"))
                .isEqualTo("Keep the effective override");
        assertThat(sources.requireSource(sources.current().version(), new SourceKind("scm.pull-request.diff"))
                        .requiredQuality())
                .isEqualTo(RequiredCaptureQuality.COMPLETE);
    }

    private static void assertLegacyCuratedDigestsVersioned() throws SQLException {
        assertThat(scalar("SELECT based_on_digest FROM curated_area_override WHERE slug = 'real-area'"))
                .isEqualTo("area:v1:" + "a".repeat(64));
        assertThat(scalar("SELECT based_on_digest FROM curated_practice_override WHERE slug = 'real-practice'"))
                .isEqualTo("practice:v1:" + "b".repeat(64));
    }

    private static void assertAuditHistoryPreserved() throws SQLException {
        assertThat(scalar("SELECT count(*)::text FROM config_audit_event WHERE entity_type = 'PRACTICE_ACTIVE'"))
                .isEqualTo("1");
        assertThat(scalar("""
                SELECT count(*)::text
                FROM config_audit_event
                WHERE entity_id = 'PRACTICE_DETECTION'
                  AND old_value->>'purpose' = 'PRACTICE_DETECTION'
                  AND new_value->>'purpose' = 'PRACTICE_DETECTION'
                """)).isEqualTo("1");
    }

    /** Straight after {@code -5}, which stamps a scheme onto every fingerprint stored without one. */
    private static void assertHistoricalFingerprintsVersioned() throws SQLException {
        assertThat(scalar("SELECT detection_fingerprint FROM practice_revision "
                        + "WHERE practice_id = 136301 ORDER BY revision_number DESC LIMIT 1"))
                .isEqualTo("v1:" + "a".repeat(64));
        assertThat(scalar("SELECT source_curated_fingerprint FROM practice WHERE id = 136302"))
                .isEqualTo("v1:" + "c".repeat(64));
    }

    /**
     * Every stored review-rule fingerprint is gone after the whole chain, on purpose: each described a
     * rule set in a spelling the vocabulary moves erased, so a stale digest would report every bundled
     * practice as locally edited; clearing it hands recomputation to the boot-time backfill.
     *
     * <p>The provenance fingerprint is untouched — it identifies which bundled definition a copy came
     * from, which no rename changes.
     */
    private static void assertHistoricalFingerprintsClearedForRecomputation() throws SQLException {
        assertThat(scalar("SELECT count(*)::text FROM practice_revision "
                        + "WHERE practice_id = 136301 AND slug IS NOT NULL AND review_rule_fingerprint IS NOT NULL"))
                .isEqualTo("0");
        assertThat(scalar("SELECT source_curated_fingerprint FROM practice WHERE id = 136302"))
                .isEqualTo("v1:" + "c".repeat(64));
    }

    private static void assertMarkerRepair() throws SQLException {
        assertThat(markedWorkspaceSlugs()).containsExactly("area-only", "practice-only");
    }

    private static void assertCatalogBootstrap(int practices, int areas) throws SQLException {
        assertThat(scalar("SELECT count(*)::text FROM curated_practice_override"))
                .isEqualTo(String.valueOf(practices));
        assertThat(scalar("SELECT count(*)::text FROM curated_area_override")).isEqualTo(String.valueOf(areas));
        assertThat(
                        scalar(
                                "SELECT count(*)::text FROM practice_catalog_installation WHERE provenance_linked_at IS NOT NULL"))
                .isEqualTo("1");
        assertThat(scalar(
                        "SELECT count(*)::text FROM practice_catalog_installation WHERE provenance_linked_at IS NULL"))
                .isEqualTo("1");
        assertThatThrownBy(() -> execute("""
                INSERT INTO curated_practice_override (slug, created_at, updated_at, version)
                VALUES ('says-nothing', now(), now(), 0)
                """)).isInstanceOf(SQLException.class);
    }

    private static void assertWorkspaceRevisionBackfill() throws SQLException {
        assertThat(scalar("""
                SELECT count(*)::text
                FROM practice practice
                JOIN practice_revision revision ON revision.id = practice.current_revision_id
                LEFT JOIN practice_area area ON area.id = practice.practice_area_id
                WHERE practice.id IN (136301, 136302)
                  AND revision.practice_id = practice.id
                  AND revision.slug = practice.slug
                  AND revision.name = practice.name
                  AND revision.applies_to = practice.applies_to
                  AND revision.bindings = practice.bindings
                  AND revision.criteria = practice.criteria
                  AND revision.precompute_script IS NOT DISTINCT FROM practice.precompute_script
                  AND revision.why_it_matters IS NOT DISTINCT FROM practice.why_it_matters
                  AND revision.what_good_looks_like IS NOT DISTINCT FROM practice.what_good_looks_like
                  AND revision.area_slug IS NOT DISTINCT FROM area.slug
                """)).isEqualTo("2");
        assertThat(scalar("""
                SELECT revision.revision_number::text
                FROM practice practice
                JOIN practice_revision revision ON revision.id = practice.current_revision_id
                WHERE practice.id = 136301
                """)).isEqualTo("3");
        assertThat(scalar("""
                SELECT count(*)::text
                FROM practice_revision
                WHERE practice_id = 136301
                  AND revision_number = 1
                  AND criteria = 'legacy criteria'
                  AND slug IS NULL
                  AND name IS NULL
                """)).isEqualTo("1");
    }

    private static void assertPointerOwnership() {
        assertThatThrownBy(() -> execute("""
                UPDATE practice
                SET current_revision_id = (
                    SELECT current_revision_id FROM practice WHERE id = 136302
                )
                WHERE id = 136301
                """)).isInstanceOf(SQLException.class);
    }

    private static void assertAggregateDeletion() throws SQLException {
        execute("DELETE FROM practice WHERE id = 136302");
        assertThat(scalar("SELECT count(*)::text FROM practice WHERE id = 136302"))
                .isEqualTo("0");
        assertThat(scalar("SELECT count(*)::text FROM practice_revision WHERE practice_id = 136302"))
                .isEqualTo("0");
    }

    private static void assertControlledProvenanceUpdate() throws SQLException {
        execute("""
            UPDATE practice_revision
            SET review_rule_fingerprint = ('v2:' || repeat('b', 64))
            WHERE id = (SELECT current_revision_id FROM practice WHERE id = 136302)
            """);
        assertThat(scalar("""
                SELECT count(*)::text
                FROM practice_revision
                WHERE id = (SELECT current_revision_id FROM practice WHERE id = 136302)
                  AND review_rule_fingerprint = ('v2:' || repeat('b', 64))
                """)).isEqualTo("1");
        assertThatThrownBy(() -> execute("""
                UPDATE practice_revision
                SET review_rule_fingerprint = ('v2:' || repeat('d', 64))
                WHERE id = (SELECT current_revision_id FROM practice WHERE id = 136302)
                """)).isInstanceOf(SQLException.class);

        execute("""
            UPDATE practice
            SET source_curated_slug = 'second-practice', source_curated_fingerprint = ('v2:' || repeat('b', 64))
            WHERE id = 136302
            """);
        assertThatThrownBy(() -> execute("UPDATE practice SET source_curated_fingerprint = NULL WHERE id = 136302"))
                .isInstanceOf(SQLException.class);
    }

    private static void assertProjectionInvariant() {
        assertThatThrownBy(() -> execute("UPDATE practice SET name = 'Diverged' WHERE id = 136301"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("practice current revision does not match its current projection");
        assertThatThrownBy(() -> execute("UPDATE practice SET current_revision_id = NULL WHERE id = 136301"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("practice current revision does not match its current projection");
        assertThatThrownBy(
                        () -> execute(
                                "UPDATE practice SET automated_review_policy = "
                                        + "jsonb_set(automated_review_policy, '{whenEvidenceIsInsufficient}', '\"NEVER\"') WHERE id = 136301"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("practice current revision does not match its current projection");
        assertThatThrownBy(() -> execute("""
                INSERT INTO practice (
                    workspace_id, slug, name, applies_to, display_order, bindings,
                    criteria, automated_review_policy, autonomy, created_at
                ) VALUES (
                    136103, 'missing-current-revision', 'Missing revision', 'scm.pull_request', 3,
                    '[]'::jsonb, 'criteria', '{}'::jsonb, 'AUTOMATIC', now()
                )
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("practice current revision does not match its current projection");
    }

    private static void assertRevisionImmutability() throws SQLException {
        assertThatThrownBy(
                        () -> execute("UPDATE practice_revision SET criteria = 'mutated' WHERE practice_id = 136301"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("practice revisions are immutable");

        assertThatThrownBy(() -> execute("UPDATE practice_revision SET automated_review_policy = '{}'::jsonb "
                        + "WHERE id = (SELECT current_revision_id FROM practice WHERE id = 136301)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("practice revisions are immutable");

        // Scoped to the current revision: the migration clears the fingerprint on every stored revision,
        // so "whichever one is still null" would otherwise name more than one row.
        execute("""
            UPDATE practice_revision
            SET review_rule_fingerprint = ('v2:' || repeat('a', 64))
            WHERE id = (SELECT current_revision_id FROM practice WHERE id = 136301)
              AND review_rule_fingerprint IS NULL
            """);
        assertThat(scalar("SELECT count(*)::text FROM practice_revision WHERE practice_id = 136301"
                        + " AND review_rule_fingerprint = ('v2:' || repeat('a', 64))"))
                .isEqualTo("1");

        assertThatThrownBy(() ->
                        execute("UPDATE practice_revision SET review_rule_fingerprint = ('v2:' || repeat('b', 64))"
                                + " WHERE practice_id = 136301 AND slug IS NOT NULL"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("practice revisions are immutable");
    }

    private static void appendValidCurrentRevision() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("UPDATE practice SET name = 'Updated practice' WHERE id = 136301");
            statement.execute("""
                INSERT INTO practice_revision (
                    practice_id, revision_number, criteria, created_at,
                    slug, name, applies_to, bindings, precompute_script,
                    why_it_matters, what_good_looks_like,
                    area_slug, area_name, area_description, area_icon, area_color,
                    review_rule_fingerprint, automated_review_policy
                )
                SELECT practice.id,
                       (SELECT max(revision_number) + 1 FROM practice_revision WHERE practice_id = practice.id),
                       practice.criteria,
                       now(),
                       practice.slug,
                       practice.name,
                       practice.applies_to,
                       practice.bindings,
                       practice.precompute_script,
                       practice.why_it_matters,
                       practice.what_good_looks_like,
                       area.slug,
                       area.name,
                       area.description,
                       area.icon,
                       area.color,
                       ('v2:' || repeat('a', 64)),
                       practice.automated_review_policy
                FROM practice
                LEFT JOIN practice_area area ON area.id = practice.practice_area_id
                WHERE practice.id = 136301
                """);
            statement.execute("""
                UPDATE practice
                SET current_revision_id = (
                    SELECT id
                    FROM practice_revision
                    WHERE practice_id = 136301
                    ORDER BY revision_number DESC
                    LIMIT 1
                )
                WHERE id = 136301
                """);
            connection.commit();
        }
        assertThat(scalar("SELECT name FROM practice WHERE id = 136301")).isEqualTo("Updated practice");
        assertThat(scalar("""
                SELECT revision_number::text
                FROM practice_revision
                WHERE id = (SELECT current_revision_id FROM practice WHERE id = 136301)
                """)).isEqualTo("4");
    }

    private static void assertRollbackAndReapply() throws Exception {
        execute("UPDATE curated_practice_override SET based_on_digest = 'practice:v2:' || repeat('e', 64) "
                + "WHERE slug = 'real-practice'");
        try (Liquibase liquibase = liquibase()) {
            assertThatThrownBy(() -> liquibase.rollback(BEFORE_CATALOG_TAG, contexts(), new LabelExpression()))
                    .hasMessageContaining("ck_curated_practice_override_rollback_v1");
        }
        assertThat(scalar("SELECT based_on_digest FROM curated_practice_override WHERE slug = 'real-practice'"))
                .startsWith("practice:v2:");
        execute("UPDATE curated_practice_override SET based_on_digest = 'practice:v1:' || repeat('b', 64) "
                + "WHERE slug = 'real-practice'");
        try (Liquibase liquibase = liquibase()) {
            liquibase.rollback(BEFORE_CATALOG_TAG, contexts(), new LabelExpression());
        }

        assertThat(scalar("SELECT to_regclass('curated_practice_override')::text"))
                .isNull();
        assertThat(scalar("""
                SELECT count(*)::text
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'practice'
                  AND column_name = 'current_revision_id'
                """)).isEqualTo("0");
        assertThat(scalar("SELECT count(*)::text FROM practice_revision WHERE practice_id = 136301"))
                .isEqualTo("1");
        assertThat(scalar("SELECT count(*)::text FROM practice_revision WHERE criteria = 'legacy criteria'"))
                .isEqualTo("1");

        try (Liquibase liquibase = liquibase()) {
            liquibase.update(contexts());
        }

        assertMarkerRepair();
        assertCatalogBootstrap(0, 0);
        assertThat(scalar("""
                SELECT revision.revision_number::text
                FROM practice practice
                JOIN practice_revision revision ON revision.id = practice.current_revision_id
                WHERE practice.id = 136301
                """)).isEqualTo("3");
        assertThat(scalar("""
                SELECT count(*)::text
                FROM practice_revision
                WHERE practice_id IN (136301, 136302)
                  AND automated_review_policy IS NOT NULL
                  AND automated_review_policy ? 'sourceContractVersion'
                  AND automated_review_policy #>> '{automatedReview,mode}' = 'LANGUAGE_MODEL'
                  AND automated_review_policy #>> '{automatedReview,evidenceSufficiency}' =
                      'SUFFICIENT_WHEN_REQUIREMENTS_MET'
                  -- The sources a review reads live on the bindings now; every spelling the policy ever
                  -- carried them under must be gone from it after a re-applied chain.
                  AND NOT automated_review_policy ? 'needs'
                  AND NOT automated_review_policy ? 'requiredEvidence'
                  AND NOT automated_review_policy ? 'optionalContext'
                  AND NOT automated_review_policy ? 'evidenceProfile'
                  AND NOT automated_review_policy ? 'profile'
                  AND NOT automated_review_policy ? 'detectorCapability'
                  AND NOT automated_review_policy ? 'optionalEvidence'
                  AND jsonb_path_exists(bindings, '$[*].signals')
                """)).isEqualTo("1");
    }

    private static void assertEvidenceRevisionBackfill() throws SQLException {
        assertThat(scalar("""
                SELECT count(*)::text
                FROM practice practice
                JOIN practice_revision revision ON revision.id = practice.current_revision_id
                WHERE practice.id IN (136301, 136302)
                  AND revision.automated_review_policy = practice.automated_review_policy
                  AND revision.automated_review_policy IS NOT NULL
                """)).isEqualTo("2");
        assertThat(scalar("""
                SELECT count(*)::text
                FROM practice_revision
                WHERE practice_id IN (136301, 136302)
                  AND automated_review_policy IS NULL
                """)).isEqualTo("3");
    }

    private static void seedExistingWorkspaces() throws SQLException {
        execute("""
            INSERT INTO workspace (id, account_login, account_type, display_name, slug, status, is_publicly_viewable)
            VALUES (136101, 'migration-empty-marked',   'ORG', 'Empty marked',   'empty-marked',   'ACTIVE', false),
                   (136102, 'migration-area-only',      'ORG', 'Area only',      'area-only',      'ACTIVE', false),
                   (136103, 'migration-practice-only',  'ORG', 'Practice only',  'practice-only',  'ACTIVE', false),
                   (136104, 'migration-empty-unmarked', 'ORG', 'Empty unmarked', 'empty-unmarked', 'ACTIVE', false)
            """, """
            INSERT INTO practice_area (
                id, workspace_id, slug, name, is_active, display_order, icon, color, created_at
            ) VALUES
                (136201, 136102, 'existing-area', 'Existing area', true, 0, 'Package', 'sky', now()),
                (136202, 136103, 'practice-area', 'Practice area', true, 0, 'Package', 'sky', now())
            """, """
            INSERT INTO practice (
                id, workspace_id, practice_area_id, slug, name, applies_to, display_order,
                trigger_events, criteria, precompute_script, why_it_matters, what_good_looks_like,
                is_active, created_at
            ) VALUES
                (136301, 136103, 136202, 'existing-practice', 'Existing practice', 'PULL_REQUEST', 0,
                 '["PullRequestCreated"]'::jsonb, 'Current criteria', NULL, 'Why', 'Good', true, now()),
                (136302, 136103, NULL, 'second-practice', 'Second practice', 'ISSUE', 1,
                 '["IssuesEvent"]'::jsonb, 'Second criteria', NULL, NULL, NULL, true, now())
            """, """
            INSERT INTO practice_revision (practice_id, revision_number, criteria, created_at)
            VALUES (136301, 1, 'legacy criteria', now())
            """);
    }

    private static void updateUntilBefore(String changelogName) throws Exception {
        try (Liquibase liquibase = liquibase()) {
            List<ChangeSet> pending = liquibase.listUnrunChangeSets(contexts(), new LabelExpression());
            List<Integer> indexes = indexesOf(pending, changelogName);
            assertThat(indexes)
                    .as("%s must be present in the pending changelog", changelogName)
                    .isNotEmpty();
            liquibase.update(indexes.getFirst(), contexts(), new LabelExpression());
        }
    }

    private static void updateOnly(String changelogName) throws Exception {
        try (Liquibase liquibase = liquibase()) {
            List<ChangeSet> pending = liquibase.listUnrunChangeSets(contexts(), new LabelExpression());
            List<Integer> indexes = indexesOf(pending, changelogName);
            assertThat(indexes)
                    .as("%s must be the next pending changelog", changelogName)
                    .isNotEmpty()
                    .startsWith(0);
            liquibase.update(indexes.size(), contexts(), new LabelExpression());
        }
    }

    private static void updateThrough(String changeSetId) throws Exception {
        try (Liquibase liquibase = liquibase()) {
            List<ChangeSet> pending = liquibase.listUnrunChangeSets(contexts(), new LabelExpression());
            int index = java.util.stream.IntStream.range(0, pending.size())
                    .filter(candidate -> pending.get(candidate).getId().equals(changeSetId))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(index + 1, contexts(), new LabelExpression());
        }
    }

    private static List<Integer> indexesOf(List<ChangeSet> changesets, String changelogName) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < changesets.size(); index++) {
            if (changesets.get(index).getFilePath().endsWith(changelogName)) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    private static List<String> markedWorkspaceSlugs() throws SQLException {
        List<String> slugs = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                SELECT workspace.slug
                FROM practice_catalog_installation installation
                JOIN workspace workspace ON workspace.id = installation.workspace_id
                WHERE workspace.id BETWEEN 136101 AND 136104
                ORDER BY workspace.slug
                """)) {
            while (rows.next()) {
                slugs.add(rows.getString(1));
            }
        }
        return slugs;
    }

    private static Liquibase liquibase() throws Exception {
        Database database =
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connect()));
        return new Liquibase(MASTER, new ClassLoaderResourceAccessor(), database);
    }

    private static Contexts contexts() {
        return new Contexts("prod");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }

    private static void execute(String... statements) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static @Nullable String scalar(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
