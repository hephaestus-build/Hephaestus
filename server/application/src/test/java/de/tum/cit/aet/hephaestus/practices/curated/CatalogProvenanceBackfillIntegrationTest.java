package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AdoptedBaseSource;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionField;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceDefaults;
import de.tum.cit.aet.hephaestus.practices.PracticeReleaseChoice;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
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
    private CuratedPracticeOverrideRepository overrideRepository;

    @Autowired
    private CuratedCatalogService catalogService;

    @Autowired
    private CuratedPracticeReleaseService releases;

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
        assertThat(baseSource(matching)).isEqualTo("BUNDLED_FINGERPRINT_MATCH");
        assertThat(unfingerprintedRevisions()).isEqualTo(2);
        assertThat(workspacesAwaiting()).isZero();
    }

    @Test
    void switchesOffAWithdrawnCopyTheSameRunLinksToTheCatalog() {
        String withdrawn = "issue-closed-with-unmet-outcome";
        PracticeDefinition entry = shipped(withdrawn);
        seedLegacyWorkspace(matching, withdrawn, entry.criteria(), true, entry.automatedReviewPolicy(), null);

        backfill.run();

        assertThat(jdbcTemplate.queryForMap(
                        "SELECT source_curated_slug, autonomy FROM practice WHERE workspace_id = ?", matching.getId()))
                .containsEntry("source_curated_slug", withdrawn)
                .containsEntry("autonomy", "OFF");
        assertThat(count(
                        "SELECT count(*) FROM config_audit_event WHERE workspace_id = ? AND entity_type = 'PRACTICE_USAGE'",
                        matching.getId()))
                .isOne();
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
        assertThat(unfingerprintedRevisions()).isEqualTo(2);
        assertThat(workspacesAwaiting()).isZero();
    }

    @Test
    void shouldUseMatchingBundledDefinitionForOldAdoption() {
        PracticeDefinition bundled = shipped();
        seedLegacyWorkspace(
                matching,
                bundled.criteria(),
                false,
                bundled.automatedReviewPolicy(),
                bundled.provenanceFingerprint(SHIPPED_SLUG));

        backfill.run();

        assertThat(baseSource(matching)).isEqualTo("BUNDLED_FINGERPRINT_MATCH");
        assertThat(baseCriteria(matching)).isEqualTo(bundled.criteria());
    }

    @Test
    void shouldUseCurrentDefinitionWhenOldFingerprintDoesNotMatch() {
        seedLegacyWorkspace(
                matching, "Locally changed criteria", false, shipped().automatedReviewPolicy(), "v3:" + "a".repeat(64));

        backfill.run();

        assertThat(baseSource(matching)).isEqualTo("CURRENT_DEFINITION");
        assertThat(baseCriteria(matching)).isEqualTo("Locally changed criteria");
    }

    @Test
    void shouldBackfillInstanceOverrideFromMatchingBundledDigest() {
        PracticeDefinition bundled = shipped();
        CuratedPracticeOverride override = new CuratedPracticeOverride(SHIPPED_SLUG, Instant.now());
        override.write(
                withCriteria(bundled, "Instance criteria"),
                CuratedDefinitionDigest.of(SHIPPED_SLUG, bundled),
                Instant.now());
        overrideRepository.save(override);

        backfill.run();

        CuratedPracticeOverride saved =
                overrideRepository.findBySlug(SHIPPED_SLUG).orElseThrow();
        assertThat(saved.getAdoptedBase()).isEqualTo(bundled);
        assertThat(saved.getAdoptedBaseSource()).isEqualTo(AdoptedBaseSource.BUNDLED_DIGEST_MATCH);
    }

    @Test
    void shouldUseCurrentInstanceDefinitionWhenBundledDigestDoesNotMatch() {
        PracticeDefinition bundled = shipped();
        CuratedPracticeOverride override = new CuratedPracticeOverride(SHIPPED_SLUG, Instant.now());
        PracticeDefinition edited = withCriteria(bundled, "Old instance criteria");
        override.write(edited, "not-the-current-bundle", Instant.now());
        overrideRepository.save(override);

        backfill.run();

        CuratedPracticeOverride saved =
                overrideRepository.findBySlug(SHIPPED_SLUG).orElseThrow();
        assertThat(saved.getAdoptedBase()).isEqualTo(edited);
        assertThat(saved.getAdoptedBaseSource()).isEqualTo(AdoptedBaseSource.CURRENT_DEFINITION);

        var proposal = releases.practiceRelease(SHIPPED_SLUG);
        releases.accept(
                SHIPPED_SLUG,
                EntityTagPrecondition.parse('"' + proposal.etag() + '"'),
                Map.of(
                        PracticeDefinitionField.CRITERIA, PracticeReleaseChoice.CURRENT,
                        PracticeDefinitionField.DELIVERY_BEHAVIOR, PracticeReleaseChoice.OFFERED));
        CuratedPracticeOverride kept =
                overrideRepository.findBySlug(SHIPPED_SLUG).orElseThrow();
        assertThat(kept.getAdoptedBase()).isEqualTo(bundled);
        assertThat(kept.getAdoptedBaseSource()).isEqualTo(AdoptedBaseSource.EXACT_ADOPTION);
    }

    @Test
    void shouldPreserveLegacyInstanceBaseAndDigestWhenEditedBeforeRepair() {
        PracticeDefinition bundled = shipped();
        PracticeDefinition old = withCriteria(bundled, "Old instance criteria");
        CuratedPracticeOverride override = new CuratedPracticeOverride(SHIPPED_SLUG, Instant.now());
        String oldDigest = "practice:v2:" + "a".repeat(64);
        override.write(old, oldDigest, Instant.now());
        overrideRepository.save(override);

        var entry = catalogService.practice(SHIPPED_SLUG);
        catalogService.writePractice(
                SHIPPED_SLUG,
                EntityTagPrecondition.parse('"' + entry.etag() + '"'),
                withCriteria(bundled, "New instance criteria"),
                null);

        CuratedPracticeOverride saved =
                overrideRepository.findBySlug(SHIPPED_SLUG).orElseThrow();
        assertThat(saved.getAdoptedBase()).isEqualTo(old);
        assertThat(saved.getAdoptedBaseSource()).isEqualTo(AdoptedBaseSource.CURRENT_DEFINITION);
        assertThat(saved.getAcceptedBundledDigest()).isEqualTo(oldDigest);
    }

    @Test
    void shouldKeepInstanceBaseThroughLaterCustomization() {
        PracticeDefinition bundled = shipped();
        var entry = catalogService.practice(SHIPPED_SLUG);
        PracticeDefinition first = withCriteria(bundled, "First instance criteria");
        catalogService.writePractice(SHIPPED_SLUG, EntityTagPrecondition.parse('"' + entry.etag() + '"'), first, null);
        var firstOverride = overrideRepository.findBySlug(SHIPPED_SLUG).orElseThrow();
        assertThat(firstOverride.getAdoptedBase()).isEqualTo(bundled);
        assertThat(firstOverride.getAdoptedBaseSource()).isEqualTo(AdoptedBaseSource.EXACT_ADOPTION);
        assertThat(firstOverride.getAcceptedBundledDigest())
                .isEqualTo(CuratedDefinitionDigest.of(SHIPPED_SLUG, bundled));

        var changed = catalogService.practice(SHIPPED_SLUG);
        catalogService.writePractice(
                SHIPPED_SLUG,
                EntityTagPrecondition.parse('"' + changed.etag() + '"'),
                withCriteria(bundled, "Second instance criteria"),
                null);

        assertThat(overrideRepository.findBySlug(SHIPPED_SLUG).orElseThrow().getAdoptedBase())
                .isEqualTo(bundled);
    }

    private PracticeDefinition withCriteria(PracticeDefinition definition, String criteria) {
        return new PracticeDefinition(
                definition.name(),
                definition.signals(),
                definition.evidenceRequirements(),
                definition.reviewWhen(),
                definition.subject(),
                definition.precondition(),
                criteria,
                definition.precomputeScript(),
                definition.automatedReviewPolicy(),
                definition.whyItMatters(),
                definition.whatGoodLooksLike(),
                definition.groupSlug());
    }

    private String baseSource(Workspace workspace) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT adopted_base_source FROM practice WHERE workspace_id = ?", String.class, workspace.getId()));
    }

    private String baseCriteria(Workspace workspace) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT adopted_base ->> 'criteria' FROM practice WHERE workspace_id = ?",
                String.class,
                workspace.getId()));
    }

    private PracticeDefinition shipped() {
        return shipped(SHIPPED_SLUG);
    }

    private PracticeDefinition shipped(String slug) {
        return catalogService.catalog().practice(slug).orElseThrow().effective();
    }

    @Test
    void preservesUnknownHistoricalReviewFingerprint() {
        seedLegacyWorkspace(matching, shipped().criteria(), true);

        backfill.run();

        assertThat(unfingerprintedRevisions()).isOne();
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
        seedLegacyWorkspace(workspace, SHIPPED_SLUG, criteria, provenancePending, evidence, fingerprint);
    }

    private void seedLegacyWorkspace(
            Workspace workspace,
            String slug,
            String criteria,
            boolean provenancePending,
            @Nullable PracticeAutomatedReviewPolicy evidence,
            @Nullable String fingerprint) {
        PracticeDefinition shipped = shipped(slug);
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
                    workspace_id, practice_group_id, slug, name, applies_to, display_order, signals, evidence_requirements, review_when, subject, precondition,
                    criteria, automated_review_policy, delivery_behavior, why_it_matters, source_curated_slug,
                    source_curated_fingerprint, autonomy, created_at
                ) VALUES (?, ?, ?, ?, ?, 0, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, '{"summaryOnly":true}'::jsonb, 'Reviewers need context', ?, ?, 'AUTOMATIC', now())
                RETURNING id
                """,
                    Long.class,
                    workspace.getId(),
                    groupId,
                    slug,
                    shipped.name(),
                    shipped.artifactKind().value(),
                    objectMapper.valueToTree(shipped.signals()).toString(),
                    objectMapper.valueToTree(shipped.evidenceRequirements()).toString(),
                    objectMapper.valueToTree(shipped.reviewWhen()).toString(),
                    shipped.subject().name(),
                    shipped.precondition() == null
                            ? null
                            : objectMapper.valueToTree(shipped.precondition()).toString(),
                    criteria,
                    evidenceJson(evidence),
                    fingerprint == null ? null : slug,
                    fingerprint);
            Long revisionId = jdbcTemplate.queryForObject(
                    """
                INSERT INTO practice_revision (
                    practice_id, revision_number, slug, name, applies_to, signals, evidence_requirements, review_when, subject, precondition, criteria,
                    automated_review_policy, delivery_behavior, why_it_matters, group_slug, review_rule_fingerprint, created_at
                ) VALUES (?, 1, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, '{"summaryOnly":true}'::jsonb, 'Reviewers need context', ?, ?, now())
                RETURNING id
                """,
                    Long.class,
                    practiceId,
                    slug,
                    shipped.name(),
                    shipped.artifactKind().value(),
                    objectMapper.valueToTree(shipped.signals()).toString(),
                    objectMapper.valueToTree(shipped.evidenceRequirements()).toString(),
                    objectMapper.valueToTree(shipped.reviewWhen()).toString(),
                    shipped.subject().name(),
                    shipped.precondition() == null
                            ? null
                            : objectMapper.valueToTree(shipped.precondition()).toString(),
                    criteria,
                    evidenceJson(evidence),
                    shipped.groupSlug(),
                    fingerprint);
            if (fingerprint != null) {
                revisionId = jdbcTemplate.queryForObject(
                        """
                    INSERT INTO practice_revision (
                        practice_id, revision_number, slug, name, applies_to, signals, evidence_requirements, review_when, subject, precondition, criteria,
                        automated_review_policy, delivery_behavior, why_it_matters, group_slug, review_rule_fingerprint, created_at
                    ) VALUES (?, 2, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, '{"summaryOnly":true}'::jsonb, 'Reviewers need context', ?, NULL, now())
                    RETURNING id
                    """,
                        Long.class,
                        practiceId,
                        slug,
                        shipped.name(),
                        shipped.artifactKind().value(),
                        objectMapper.valueToTree(shipped.signals()).toString(),
                        objectMapper.valueToTree(shipped.evidenceRequirements()).toString(),
                        objectMapper.valueToTree(shipped.reviewWhen()).toString(),
                        shipped.subject().name(),
                        shipped.precondition() == null
                                ? null
                                : objectMapper
                                        .valueToTree(shipped.precondition())
                                        .toString(),
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
