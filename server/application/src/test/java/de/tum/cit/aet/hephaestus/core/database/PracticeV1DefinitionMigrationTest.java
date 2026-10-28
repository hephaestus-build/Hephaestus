package de.tum.cit.aet.hephaestus.core.database;

import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.seedBundledPractice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("database")
class PracticeV1DefinitionMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldConvertEveryMappedProgramWithoutChangingItsSyntheticOwnerCriteria(String context) throws Exception {
        var mapped = PracticeMigrationFixture.mappedPrograms();
        var programs = new ArrayList<PracticeMigrationFixture.ProgramMapping>();
        try (var input = Objects.requireNonNull(
                getClass().getResourceAsStream("/db/practice-cutover/mapped-program-targets.json"))) {
            var bindings = Objects.requireNonNull(JsonMapper.builder().build().readTree(input));
            assertThat(bindings.size()).isEqualTo(85);
            for (var binding : bindings) {
                var mapping = Objects.requireNonNull(
                        mapped.get(binding.path("sourceSha256").asString()));
                // The pinned digest, not today's bundled script: a later edit to the script leaves the
                // released migration's target unchanged.
                assertThat(mapping.targetSha256())
                        .as(
                                "historical target %s",
                                binding.path("targetResource").asString())
                        .isEqualTo(binding.path("targetSha256").asString());
                programs.add(mapping);
            }
        }
        assertThat(programs.stream()
                        .map(PracticeMigrationFixture.ProgramMapping::sourceSha256)
                        .distinct())
                .hasSize(85);
        var database = PracticeMigrationFixture.releasedDatabase(context);
        PracticeMigrationFixture.seedMappedPrograms(database, programs);
        PracticeMigrationFixture.upgrade(database, context);
        for (int index = 0; index < programs.size(); index++) {
            long practiceId = 1600000L + index;
            var mapping = programs.get(index);
            String owners = " FROM practice p JOIN practice_version v ON v.id=p.current_version_id"
                    + " JOIN catalog_entry c ON c.entry_type='PRACTICE' AND c.slug=p.catalog_slug WHERE p.id="
                    + practiceId;
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT v.definition->>'script'" + owners
                                    + " UNION ALL SELECT p.catalog_base#>>'{definition,script}'" + owners
                                    + " UNION ALL SELECT c.definition#>>'{definition,script}'" + owners
                                    + " UNION ALL SELECT c.base#>>'{definition,script}'" + owners))
                    .as("source %s on workspace/instance current and base paths", mapping.sourceSha256())
                    .containsExactly(mapping.target(), mapping.target(), mapping.target(), mapping.target());
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT v.definition->>'criteria'" + owners
                                    + " UNION ALL SELECT p.catalog_base#>>'{definition,criteria}'" + owners
                                    + " UNION ALL SELECT c.definition#>>'{definition,criteria}'" + owners
                                    + " UNION ALL SELECT c.base#>>'{definition,criteria}'" + owners))
                    .containsExactly(
                            "Workspace customized criterion.\n  Keep exact whitespace.\n",
                            "Workspace comparison criterion.\n  Keep exact whitespace.\n",
                            "Instance customized criterion.\n  Keep exact whitespace.\n",
                            "Instance comparison criterion.\n  Keep exact whitespace.\n");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT p.catalog_slug || '|' || v.number || '|' || (v.definition#>>'{reads,contract}')"
                                    + owners))
                    .containsExactly("synthetic-mapping-" + index + "|3|3.0.0");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT definition->>'script' FROM practice_version WHERE id=" + (1700000L + index)))
                    .containsExactly(mapping.source());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldRefuseAOneByteChangeToAnExactlyMappedProgram(String context) throws Exception {
        final String sourceSha;
        try (var input = Objects.requireNonNull(
                getClass().getResourceAsStream("/db/practice-cutover/mapped-program-targets.json"))) {
            sourceSha = Objects.requireNonNull(JsonMapper.builder().build().readTree(input))
                    .get(0)
                    .path("sourceSha256")
                    .asString();
        }
        var source = Objects.requireNonNull(
                        PracticeMigrationFixture.mappedPrograms().get(sourceSha))
                .source();
        var database = PracticeMigrationFixture.releasedDatabase(context);
        installProgram(database, source + " ");
        assertThatThrownBy(() -> PracticeMigrationFixture.upgrade(database, context))
                .hasMessageContaining("practice 992121 has an unsupported custom program");
        assertThat(PracticeMigrationFixture.column(database, "SELECT precompute_script FROM practice WHERE id=992121"))
                .containsExactly(source + " ");
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT count(*)::text FROM information_schema.tables WHERE table_schema='public' AND table_name='result'"))
                .containsExactly("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldTransformEverySupportedProgramWithoutChangingItsPinnedSource(String context) throws Exception {
        // The released migration's own mapping owns each target; today's bundled script may have moved on.
        var mapped = PracticeMigrationFixture.mappedPrograms();
        var database = PracticeMigrationFixture.releasedDatabase(context);
        try (var input =
                Objects.requireNonNull(getClass().getResourceAsStream("/db/practice-cutover/released-programs.json"))) {
            var programs = Objects.requireNonNull(JsonMapper.builder().build().readTree(input));
            try (var connection =
                    DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
                connection.setAutoCommit(false);
                int index = 0;
                for (var program : programs) {
                    long practiceId = 1000000L + index;
                    long versionId = 1100000L + index;
                    try (var statement = connection.prepareStatement("""
                        INSERT INTO practice(id,workspace_id,slug,name,criteria,applies_to,subject,signals,
                            evidence_requirements,review_when,automated_review_policy,delivery_behavior,precompute_script,created_at)
                        SELECT ?,workspace_id,?,name,criteria,applies_to,subject,signals,evidence_requirements,
                            review_when,automated_review_policy,delivery_behavior,?,created_at FROM practice WHERE id=992121
                        """)) {
                        statement.setLong(1, practiceId);
                        statement.setString(2, "program-" + index);
                        statement.setString(3, program.path("source").asString());
                        statement.executeUpdate();
                    }
                    try (var statement = connection.prepareStatement("""
                        INSERT INTO practice_revision(id,practice_id,revision_number,slug,name,applies_to,subject,
                            signals,evidence_requirements,review_when,criteria,automated_review_policy,delivery_behavior,
                            precompute_script,created_at)
                        SELECT ?,id,1,slug,name,applies_to,subject,signals,evidence_requirements,review_when,
                            criteria,automated_review_policy,delivery_behavior,precompute_script,'2026-01-01'
                        FROM practice WHERE id=?
                        """)) {
                        statement.setLong(1, versionId);
                        statement.setLong(2, practiceId);
                        statement.executeUpdate();
                    }
                    try (var statement =
                            connection.prepareStatement("UPDATE practice SET current_revision_id=? WHERE id=?")) {
                        statement.setLong(1, versionId);
                        statement.setLong(2, practiceId);
                        statement.executeUpdate();
                    }
                    index++;
                }
                connection.commit();
            }
            PracticeMigrationFixture.upgrade(database, context);
            int index = 0;
            for (var program : programs) {
                var mapping = releasedMapping(mapped, program);
                assertThat(PracticeMigrationFixture.column(
                                database,
                                "SELECT encode(sha256(convert_to(definition->>'script','UTF8')),'hex') FROM"
                                        + " practice_version WHERE id="
                                        + (1100000L + index)))
                        .containsExactly(program.path("sourceSha256").asString());
                assertThat(PracticeMigrationFixture.column(
                                database,
                                "SELECT encode(sha256(convert_to(v.definition->>'script','UTF8')),'hex') || '|' ||"
                                        + " v.number FROM practice p JOIN practice_version v ON v.id=p.current_version_id"
                                        + " WHERE p.id="
                                        + (1000000L + index)))
                        .containsExactly(mapping.targetSha256() + "|3");
                index++;
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldAppendAnExactBundledProgramTransformationAndKeepPinnedHistory(String context) throws Exception {
        var database = PracticeMigrationFixture.releasedDatabase(context);
        try (var input =
                Objects.requireNonNull(getClass().getResourceAsStream("/db/practice-cutover/released-programs.json"))) {
            var program = Objects.requireNonNull(
                    JsonMapper.builder().build().readTree(input).get(0));
            var mapping = releasedMapping(PracticeMigrationFixture.mappedPrograms(), program);
            installProgram(database, program.path("source").asString());
            PracticeMigrationFixture.upgrade(database, context);
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT encode(sha256(convert_to(definition->>'script','UTF8')),'hex') FROM"
                                    + " practice_version WHERE id=992132"))
                    .containsExactly(program.path("sourceSha256").asString());
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT encode(sha256(convert_to(v.definition->>'script','UTF8')),'hex') || '|' ||"
                                    + " v.number || '|' || (v.definition->'reads'->>'contract') FROM practice p"
                                    + " JOIN practice_version v ON v.id=p.current_version_id WHERE p.id=992121"))
                    .containsExactly(mapping.targetSha256() + "|4|3.0.0");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT practice_version_id::text FROM result WHERE"
                                    + " id='00000000-0000-0000-0000-000000992141'"))
                    .containsExactly("992131");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldIdentifyUnsupportedCustomProgramsAndRollBackTheCutover(String context) throws Exception {
        var database = PracticeMigrationFixture.releasedDatabase(context);
        installProgram(database, "export default async function custom() { return {outcome: 'MET'}; }");
        assertThatThrownBy(() -> PracticeMigrationFixture.upgrade(database, context))
                .hasMessageContaining("practice 992121 has an unsupported custom program");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT current_revision_id::text FROM practice WHERE id=992121"))
                .containsExactly("992132");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT count(*)::text FROM information_schema.tables WHERE table_schema='public' AND"
                                + " table_name='result'"))
                .containsExactly("0");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT locked::text FROM databasechangeloglock WHERE id=1"))
                .containsExactly("false");
    }

    private static void installProgram(TestDatabase database, String source) throws SQLException {
        try (var connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                INSERT INTO practice_revision(id,practice_id,revision_number,slug,name,applies_to,subject,
                    signals,evidence_requirements,review_when,criteria,automated_review_policy,
                    delivery_behavior,precompute_script,created_at)
                SELECT 992132,id,2,slug,name,applies_to,subject,signals,evidence_requirements,review_when,
                    criteria,automated_review_policy,delivery_behavior,?,'2026-01-02T00:00:00Z'
                FROM practice WHERE id=992121
                """)) {
                statement.setString(1, source);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE practice SET current_revision_id=992132,precompute_script=? WHERE id=992121")) {
                statement.setString(1, source);
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepPinnedHistoryAndCustomizedCatalogBases(String context) throws Exception {
        var database = PracticeMigrationFixture.releasedDatabase(context);
        PracticeMigrationFixture.execute(database, """
            INSERT INTO practice_group(id,workspace_id,slug,name,description,autonomy,created_at,display_order,visible_in_practice_dashboards)
            VALUES(993001,992101,'review-style','Review style','A workspace group.','HUMAN_APPROVAL','2026-01-01T00:00:00Z',0,true);
            INSERT INTO practice_revision(id,practice_id,revision_number,slug,name,applies_to,subject,
                signals,evidence_requirements,review_when,criteria,automated_review_policy,delivery_behavior,created_at)
            SELECT 992132,id,2,slug,name,applies_to,subject,signals,evidence_requirements,review_when,
                'Changed standard.',automated_review_policy,delivery_behavior,'2026-01-02T00:00:00Z'
            FROM practice WHERE id=992121;
            UPDATE practice SET criteria='Changed standard.',current_revision_id=992132,
                source_curated_slug='local-offer',source_curated_fingerprint='v5:'||repeat('a',64),
                adopted_base_source='EXACT_ADOPTION',
                adopted_base=jsonb_build_object('name',name,'signals',signals,'reviewWhen',review_when,
                    'subject',subject,'precondition',precondition,'criteria','Original standard.',
                    'evidenceRequirements',evidence_requirements,'automatedReviewPolicy',automated_review_policy,
                    'precomputeScript',precompute_script,'deliveryBehavior',delivery_behavior,
                    'whyItMatters',why_it_matters,'whatGoodLooksLike',what_good_looks_like,
                    'groupSlug',NULL,'visual',visual,'guide',guide)
                WHERE id=992121;
            INSERT INTO curated_practice_override(slug,name,applies_to,criteria,subject,signals,
                evidence_requirements,review_when,automated_review_policy,delivery_behavior,position,version,created_at,updated_at)
            SELECT 'local-offer','Local offer',applies_to,'Local offer standard.',subject,signals,
                evidence_requirements,review_when,automated_review_policy,delivery_behavior,2,7,'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z'
                FROM practice WHERE id=992121;
            INSERT INTO curated_group_override(slug,name,description,position,version,created_at,updated_at)
            VALUES('local-group','Local group','Keep this group.',3,9,'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z');
            INSERT INTO curated_practice_override(slug,position,retired_at,version,created_at,updated_at)
            VALUES('excluded-following-entry',4,'2026-01-02T00:00:00Z',11,'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z');
            INSERT INTO practice_catalog_installation(workspace_id,installed_at,provenance_linked_at)
            VALUES(992101,'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z');
            """);
        PracticeMigrationFixture.upgrade(database, context);
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT number || '|' || (definition->>'criteria') FROM practice_version WHERE id"
                                + " IN(992131,992132) ORDER BY number"))
                .containsExactly("1|Explain the purpose of the change.", "2|Changed standard.");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT p.catalog_slug || '|' || (p.catalog_base->'definition'->>'criteria') || '|' ||"
                                + " (v.definition->>'criteria') || '|' || v.number FROM practice p JOIN"
                                + " practice_version v ON v.id=p.current_version_id WHERE p.id=992121"))
                .containsExactly("local-offer|Original standard.|Changed standard.|4");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT feedback_mode FROM practice_group WHERE id=993001"))
                .containsExactly("APPROVE_FIRST");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT kind || '|' || (definition->'definition'->>'criteria') FROM"
                                + " catalog_entry WHERE entry_type='PRACTICE' AND slug='local-offer'"))
                .containsExactly("scm.pull_request|Local offer standard.");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (kind IS NULL AND definition IS NULL AND base IS NULL AND excluded_at IS NOT"
                                + " NULL)::text FROM catalog_entry WHERE entry_type='PRACTICE'"
                                + " AND slug='excluded-following-entry'"))
                .containsExactly("true");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (review_digest=practice_review_digest(definition))::text FROM practice_version"
                                + " WHERE practice_id=992121 ORDER BY number"))
                .containsExactly("true", "true", "true", "true");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (practice_catalog_installed_at='2026-01-01T00:00:00Z'::timestamptz)::text FROM"
                                + " workspace WHERE id=992101"))
                .containsExactly("true");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database,
                        "UPDATE practice_version SET definition=jsonb_set(definition,'{criteria}','\"Rewrite"
                                + " history\"'::jsonb) WHERE id=992131"))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("immutable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldAttachAuthoredProvenanceWithoutRestoringTheClearedBundledGroup(String context) throws Exception {
        var database = PracticeMigrationFixture.releasedDatabase(context);
        PracticeMigrationFixture.execute(database, """
            INSERT INTO practice_revision(id,practice_id,revision_number,slug,name,applies_to,subject,
                signals,evidence_requirements,review_when,criteria,automated_review_policy,delivery_behavior,created_at)
            SELECT 992132,id,2,'keeps-functions-small-and-single-purpose',name,applies_to,subject,
                signals,evidence_requirements,review_when,criteria,automated_review_policy,delivery_behavior,'2026-01-02'
            FROM practice WHERE id=992121;
            UPDATE practice SET slug='keeps-functions-small-and-single-purpose',current_revision_id=992132 WHERE id=992121;
            INSERT INTO curated_practice_override(slug,name,applies_to,criteria,subject,signals,
                evidence_requirements,review_when,automated_review_policy,delivery_behavior,group_slug,
                based_on_digest,position,version,created_at,updated_at)
            SELECT slug,name,applies_to,criteria,subject,signals,evidence_requirements,review_when,
                automated_review_policy,delivery_behavior,NULL,
                'practice:v3:cab2c04eb3617978148320711a11951f6fd19a1f348d19f032cf1d8a49a080f3',0,1,'2026-01-01','2026-01-02'
            FROM practice WHERE id=992121;
            INSERT INTO practice_catalog_installation(workspace_id,installed_at,provenance_linked_at)
            VALUES(992101,'2026-01-01',NULL);
            """);
        PracticeMigrationFixture.upgrade(database, context);
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT catalog_slug || '|' || (catalog_base->'groupSlug'='null'::jsonb)::text || '|' ||
                (catalog_base->'definition'->>'criteria') || '|' ||
                (catalog_base->'definition'->'reads'->>'contract') FROM practice WHERE id=992121
            """))
                .containsExactly(
                        "keeps-functions-small-and-single-purpose|true|Explain the purpose of the change.|3.0.0");
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT (definition->'groupSlug'='null'::jsonb)::text || '|' ||
                (definition->'definition'->'reads'->>'contract') FROM catalog_entry
                WHERE entry_type='PRACTICE' AND slug='keeps-functions-small-and-single-purpose'
            """)).containsExactly("true|3.0.0");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT definition->>'criteria' FROM practice_version WHERE id=992131"))
                .containsExactly("Explain the purpose of the change.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldAppendFinalBundledDefinitionsWithoutChangingPinnedOrCustomizedStandards(String context)
            throws Exception {
        var mapper = JsonMapper.builder().build();
        final JsonNode released;
        try (var input =
                Objects.requireNonNull(getClass().getResourceAsStream("/db/practice-cutover/released-catalog.json"))) {
            released = Objects.requireNonNull(mapper.readTree(input));
        }
        var database = PracticeMigrationFixture.releasedDatabase(context);
        try (var connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            connection.setAutoCommit(false);
            int index = 0;
            for (var entry : released) {
                if (!entry.path("entryType").asString().equals("PRACTICE")) continue;
                seedBundledPractice(connection, 1300000L + index, 1400000L + index, entry, null);
                if (entry.path("slug").asString().equals("keeps-functions-small-and-single-purpose")) {
                    seedBundledPractice(connection, 1300100L, 1400100L, entry, "The workspace's customized standard.");
                }
                index++;
            }
            connection.commit();
        }
        PracticeMigrationFixture.upgrade(database, context);
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT p.slug || '|' || v.number || '|' || (v.definition->>'about') || '|' ||
                (v.definition->'reads'->>'contract') || '|' ||
                COALESCE(v.definition->'scan'->>'itemKind','NO_SCAN')
            FROM practice p JOIN practice_version v ON v.id=p.current_version_id
            WHERE p.slug IN ('bundle-scope-one-reviewable-change',
                'bundle-keeps-functions-small-and-single-purpose',
                'bundle-logs-through-the-platform-logger',
                'bundle-validates-inputs-and-edge-cases-at-the-boundary') ORDER BY p.slug
            """))
                .containsExactly(
                        "bundle-keeps-functions-small-and-single-purpose|3|AUTHOR|3.0.0|scm.file",
                        "bundle-logs-through-the-platform-logger|3|AUTHOR|3.0.0|scm.file",
                        "bundle-scope-one-reviewable-change|3|AUTHOR|3.0.0|NO_SCAN",
                        "bundle-validates-inputs-and-edge-cases-at-the-boundary|3|AUTHOR|3.0.0|scm.file");
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT p.slug || '|' || v.number || '|' || (v.definition->'reads'->>'contract')
            FROM practice p JOIN practice_version v ON v.practice_id=p.id AND v.number=1
            WHERE p.slug LIKE 'bundle-%' ORDER BY p.slug
            """))
                .containsExactly(
                        "bundle-keeps-functions-small-and-single-purpose|1|1.3.0",
                        "bundle-logs-through-the-platform-logger|1|1.3.0",
                        "bundle-scope-one-reviewable-change|1|1.3.0",
                        "bundle-validates-inputs-and-edge-cases-at-the-boundary|1|1.3.0");
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT v.definition->>'criteria' || '|' || (v.definition->'scan'='null'::jsonb)::text
                || '|' || v.number FROM practice p JOIN practice_version v ON v.id=p.current_version_id WHERE p.id=1300100
            """))
                .containsExactly("The workspace's customized standard.|true|3");
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT definition->>'criteria' FROM practice_version WHERE id=1400100
            """))
                .containsExactly("The workspace's customized standard.");
    }

    /** The released migration's mapping of a pinned released program, checked against the pinned bytes. */
    private static PracticeMigrationFixture.ProgramMapping releasedMapping(
            Map<String, PracticeMigrationFixture.ProgramMapping> mapped, JsonNode program) {
        var mapping = Objects.requireNonNull(
                mapped.get(program.path("sourceSha256").asString()),
                () -> program.path("slug").asString() + " has no mapping in the released migration");
        assertThat(mapping.source()).isEqualTo(program.path("source").asString());
        assertThat(mapping.targetSha256())
                .isEqualTo(program.path("targetSha256").asString());
        return mapping;
    }
}
