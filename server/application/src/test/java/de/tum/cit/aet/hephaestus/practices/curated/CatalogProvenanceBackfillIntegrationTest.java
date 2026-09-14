package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceDefaults;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class CatalogProvenanceBackfillIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String SHIPPED_SLUG = "describe-what-and-why";

    @Autowired
    private CatalogProvenanceBackfill backfill;

    @Autowired
    private CuratedCatalogService catalogService;

    @Autowired
    private PracticeEvidenceDefaults evidenceDefaults;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionOperations transactionOperations;

    @Autowired
    private ObjectMapper objectMapper;

    private Workspace matching;
    private Workspace edited;

    @BeforeEach
    void setUp() {
        User owner = persistUser("legacy-catalog-owner");
        matching = createWorkspace("legacy-one", "Legacy one", "legacy-one", AccountType.ORG, owner);
        edited = createWorkspace("legacy-two", "Legacy two", "legacy-two", AccountType.ORG, owner);
    }

    @Test
    void stampsEachEligibleInstallationThatStillMatchesTheBundledCatalog() {
        seedLegacyWorkspace(matching, shipped().criteria(), true);
        seedLegacyWorkspace(edited, shipped().criteria(), true);

        CatalogProvenanceBackfill.Stamped stamped = backfill.run();

        assertThat(stamped.practices()).isEqualTo(2);
        assertThat(stampedPractices(matching)).isOne();
        assertThat(stampedPractices(edited)).isOne();
        assertThat(unfingerprintedRevisions()).isZero();
        assertThat(workspacesAwaiting()).isZero();
    }

    @Test
    void looksAtEachWorkspaceOnlyOnce() {
        seedLegacyWorkspace(matching, shipped().criteria(), true);
        backfill.run();

        assertThat(backfill.run().practices()).isZero();
    }

    @Test
    void leavesAnEditedLegacySeedUnlinked() {
        seedLegacyWorkspace(matching, "The workspace changed these criteria", true);
        seedLegacyWorkspace(edited, shipped().criteria(), false);

        CatalogProvenanceBackfill.Stamped stamped = backfill.run();

        assertThat(stamped.practices()).isZero();
        assertThat(stampedPractices(matching)).isZero();
        assertThat(unfingerprintedRevisions()).isZero();
        assertThat(workspacesAwaiting()).isZero();
    }

    @Test
    void upgradesAnUneditedV1SourceCopyToTheExactBundledEvidence() {
        PracticeDefinition shipped = shipped();
        String v1Fingerprint = "v1:" + "a".repeat(64);
        seedLegacyWorkspace(
                matching, shipped.criteria(), false, evidenceDefaults.policyFor(shipped.artifactKind()), v1Fingerprint);

        backfill.run();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT automated_review_policy = ?::jsonb FROM practice WHERE workspace_id = ?",
                        Boolean.class,
                        evidenceJson(shipped),
                        matching.getId()))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT source_curated_fingerprint FROM practice WHERE workspace_id = ?",
                        String.class,
                        matching.getId()))
                .isEqualTo(shipped.provenanceFingerprint(SHIPPED_SLUG));
        assertThat(count(
                        "SELECT count(*) FROM practice_revision r JOIN practice p ON p.id = r.practice_id WHERE p.workspace_id = ?",
                        matching.getId()))
                .isEqualTo(3);
    }

    @Test
    void shouldContinueAligningWhenAPracticeIsMalformed() {
        String v1Fingerprint = "v1:" + "a".repeat(64);
        seedLegacyWorkspace(
                matching,
                shipped().criteria(),
                false,
                evidenceDefaults.policyFor(shipped().artifactKind()),
                v1Fingerprint);
        seedLegacyWorkspace(
                edited,
                shipped().criteria(),
                false,
                evidenceDefaults.policyFor(shipped().artifactKind()),
                v1Fingerprint);
        jdbcTemplate.update(
                "UPDATE practice SET automated_review_policy = '{\"subject\":\"INVALID\"}'::jsonb WHERE workspace_id = ?",
                matching.getId());

        backfill.run();

        assertThat(sourceFingerprint(edited)).isEqualTo(shipped().provenanceFingerprint(SHIPPED_SLUG));
        assertThat(sourceFingerprint(matching)).isEqualTo(v1Fingerprint);
    }

    @Test
    void shouldKeepCustomizedV1DefinitionWhenItDiffersFromTheCatalog() {
        String v1Fingerprint = "v1:" + "a".repeat(64);
        seedLegacyWorkspace(
                matching,
                "The workspace intentionally changed these criteria",
                false,
                evidenceDefaults.policyFor(shipped().artifactKind()),
                v1Fingerprint);

        backfill.run();

        assertThat(sourceFingerprint(matching)).isEqualTo(v1Fingerprint);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT criteria FROM practice WHERE workspace_id = ?", String.class, matching.getId()))
                .isEqualTo("The workspace intentionally changed these criteria");
    }

    private PracticeDefinition shipped() {
        return catalogService.catalog().practice(SHIPPED_SLUG).orElseThrow().effective();
    }

    @Test
    void fingerprintsAnUpgradedInstallCarryingAPreContractRevision() {
        // An instance upgraded across the contract migration keeps its first-generation revisions, whose
        // automated_review_policy the migration left null. Those rows must not enter the fingerprint pass:
        // digesting a null policy aborts the whole workspace, leaving every review claim UNVERIFIABLE.
        seedLegacyWorkspace(matching, shipped().criteria(), true);
        transactionOperations.executeWithoutResult(
                ignored -> jdbcTemplate.update("""
                INSERT INTO practice_revision (
                    practice_id, revision_number, slug, name, applies_to, bindings, criteria,
                    automated_review_policy, why_it_matters, group_slug, review_rule_fingerprint, created_at
                )
                SELECT id, 0, slug, name, applies_to, bindings, criteria,
                       NULL, 'Reviewers need context', ?, NULL, now()
                FROM practice WHERE workspace_id = ?
                """, shipped().groupSlug(), matching.getId()));

        backfill.run();

        Integer stillMissing = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM practice_revision r JOIN practice p ON p.id = r.practice_id
            WHERE p.workspace_id = ? AND r.automated_review_policy IS NOT NULL
              AND r.review_rule_fingerprint IS NULL
            """, Integer.class, matching.getId());
        assertThat(stillMissing).isZero();
    }

    private void seedLegacyWorkspace(Workspace workspace, String criteria, boolean provenancePending) {
        PracticeDefinition shipped = shipped();
        seedLegacyWorkspace(workspace, criteria, provenancePending, shipped.automatedReviewPolicy(), null);
    }

    private void seedLegacyWorkspace(
            Workspace workspace,
            String criteria,
            boolean provenancePending,
            @Nullable PracticeAutomatedReviewPolicy evidence,
            @Nullable String fingerprint) {
        PracticeDefinition shipped = shipped();
        transactionOperations.executeWithoutResult(ignored -> {
            Long groupId = jdbcTemplate.queryForObject("""
                INSERT INTO practice_group (
                    workspace_id, slug, name, visible_in_practice_dashboards, display_order, created_at
                )
                VALUES (?, ?, 'Group', true, 0, now())
                RETURNING id
                """, Long.class, workspace.getId(), shipped.groupSlug());
            Long practiceId = jdbcTemplate.queryForObject(
                    """
                INSERT INTO practice (
                    workspace_id, practice_group_id, slug, name, applies_to, display_order, bindings,
                    criteria, automated_review_policy, why_it_matters, source_curated_slug,
                    source_curated_fingerprint, autonomy, created_at
                ) VALUES (?, ?, ?, ?, ?, 0, ?::jsonb, ?, ?::jsonb, 'Reviewers need context', ?, ?, 'AUTOMATIC', now())
                RETURNING id
                """,
                    Long.class,
                    workspace.getId(),
                    groupId,
                    SHIPPED_SLUG,
                    shipped.name(),
                    shipped.artifactKind().value(),
                    bindingsJson(shipped),
                    criteria,
                    evidenceJson(evidence),
                    fingerprint == null ? null : SHIPPED_SLUG,
                    fingerprint);
            Long revisionId = jdbcTemplate.queryForObject(
                    """
                INSERT INTO practice_revision (
                    practice_id, revision_number, slug, name, applies_to, bindings, criteria,
                    automated_review_policy, why_it_matters, group_slug, review_rule_fingerprint, created_at
                ) VALUES (?, 1, ?, ?, ?, ?::jsonb, ?, ?::jsonb, 'Reviewers need context', ?, ?, now())
                RETURNING id
                """,
                    Long.class,
                    practiceId,
                    SHIPPED_SLUG,
                    shipped.name(),
                    shipped.artifactKind().value(),
                    bindingsJson(shipped),
                    criteria,
                    evidenceJson(evidence),
                    shipped.groupSlug(),
                    fingerprint);
            if (fingerprint != null) {
                revisionId = jdbcTemplate.queryForObject(
                        """
                    INSERT INTO practice_revision (
                        practice_id, revision_number, slug, name, applies_to, bindings, criteria,
                        automated_review_policy, why_it_matters, group_slug, review_rule_fingerprint, created_at
                    ) VALUES (?, 2, ?, ?, ?, ?::jsonb, ?, ?::jsonb, 'Reviewers need context', ?, NULL, now())
                    RETURNING id
                    """,
                        Long.class,
                        practiceId,
                        SHIPPED_SLUG,
                        shipped.name(),
                        shipped.artifactKind().value(),
                        bindingsJson(shipped),
                        criteria,
                        evidenceJson(evidence),
                        shipped.groupSlug());
            }
            jdbcTemplate.update("UPDATE practice SET current_revision_id = ? WHERE id = ?", revisionId, practiceId);
            jdbcTemplate.update("""
                INSERT INTO practice_catalog_installation (workspace_id, installed_at, provenance_linked_at)
                VALUES (?, now(), CASE WHEN ? THEN NULL ELSE now() END)
                """, workspace.getId(), provenancePending);
        });
    }

    /** The bindings column, as the legacy rows this backfill reads carry it. */
    private String bindingsJson(PracticeDefinition definition) {
        return objectMapper.valueToTree(definition.bindings()).toString();
    }

    private String evidenceJson(PracticeDefinition definition) {
        return evidenceJson(definition.automatedReviewPolicy());
    }

    private String evidenceJson(@Nullable PracticeAutomatedReviewPolicy evidence) {
        return objectMapper.valueToTree(evidence).toString();
    }

    private long stampedPractices(Workspace workspace) {
        return count(
                "SELECT count(*) FROM practice WHERE workspace_id = ? AND source_curated_slug IS NOT NULL",
                workspace.getId());
    }

    private String sourceFingerprint(Workspace workspace) {
        String fingerprint = jdbcTemplate.queryForObject(
                "SELECT source_curated_fingerprint FROM practice WHERE workspace_id = ?",
                String.class,
                workspace.getId());
        assertNotNull(fingerprint);
        return fingerprint;
    }

    private long unfingerprintedRevisions() {
        return count(
                "SELECT count(*) FROM practice_revision WHERE slug IS NOT NULL AND review_rule_fingerprint IS NULL");
    }

    private long workspacesAwaiting() {
        return count("SELECT count(*) FROM practice_catalog_installation WHERE provenance_linked_at IS NULL");
    }

    private long count(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertNotNull(count);
        return count;
    }
}
