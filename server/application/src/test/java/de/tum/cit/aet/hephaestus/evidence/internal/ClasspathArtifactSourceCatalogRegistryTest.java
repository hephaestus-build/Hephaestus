package de.tum.cit.aet.hephaestus.evidence.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.PrivacyClass;
import de.tum.cit.aet.hephaestus.evidence.RequiredCaptureQuality;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUseBasis;
import de.tum.cit.aet.hephaestus.evidence.SourceUseOutcome;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ClasspathArtifactSourceCatalogRegistryTest {

    private static final String VERSION_1_CATALOG_SHA256 =
            "df44eb8884ed8bd60f60984d0d9c9c76c76543a096449530514a85d6a66e12b6";

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void shouldLoadCurrentCatalogAndGovernanceDecisions() {
        var registry = new ClasspathArtifactSourceCatalogRegistry(objectMapper, java.time.Clock.systemUTC());

        assertThat(registry.current().version()).isEqualTo(new SourceContractVersion("1.0.0"));
        assertThat(registry.catalogDigest()).isEqualTo(VERSION_1_CATALOG_SHA256);
        assertThat(registry.current().sources()).hasSize(16);
        assertThat(registry.requireSourcesFor(new SourceContractVersion("1.0.0"), "scm.pull_request"))
                .contains(new SourceKind("scm.repository.tree"), new SourceKind("scm.pull-request.diff"));
        var repositoryTree =
                registry.requireSource(new SourceContractVersion("1.0.0"), new SourceKind("scm.repository.tree"));
        assertThat(repositoryTree.displayName()).isEqualTo("Repository files");
        assertThat(repositoryTree.completenessPolicy().supportsPartial()).isTrue();
        assertThat(repositoryTree.completenessPolicy().supportsEmpty()).isTrue();
        // No pull request has zero commits, so an empty commit capture is a mirror gap and readiness
        // must refuse the practices that read it rather than let a model judge commits it cannot see.
        var commits =
                registry.requireSource(new SourceContractVersion("1.0.0"), new SourceKind("scm.pull-request.commits"));
        assertThat(commits.requiredQuality()).isEqualTo(RequiredCaptureQuality.COMPLETE_AND_NON_EMPTY);
    }

    @Test
    void shouldRejectUnknownVersionKindAndArtifactKind() {
        var registry = new ClasspathArtifactSourceCatalogRegistry(objectMapper, java.time.Clock.systemUTC());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.requireSource(
                        new SourceContractVersion("2.0.0"), new SourceKind("scm.repository.tree")));
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        registry.requireSource(new SourceContractVersion("1.0.0"), new SourceKind("scm.unknown")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.requireSourcesFor(new SourceContractVersion("1.0.0"), "scm.deployment"));
    }

    @Test
    void shouldRejectUnknownCatalogFields() throws IOException {
        JsonNode catalog =
                read(ClasspathArtifactSourceCatalogRegistry.CATALOG_RESOURCE).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) catalog).put("futureMeaning", true);

        assertThatIllegalStateException()
                .isThrownBy(() -> ClasspathArtifactSourceCatalogRegistry.parse(catalog))
                .withMessageContaining("Unknown field");
    }

    @Test
    void shouldRequireTraceableCurrentEngineeringApproval() throws IOException {
        var catalog = ClasspathArtifactSourceCatalogRegistry.parse(
                read(ClasspathArtifactSourceCatalogRegistry.CATALOG_RESOURCE));
        var decisions = ClasspathArtifactSourceCatalogRegistry.parseUseDecisions(
                read(ClasspathArtifactSourceCatalogRegistry.USE_DECISIONS_RESOURCE));
        ClasspathArtifactSourceCatalogRegistry.validateUseDecisions(catalog, decisions);
        assertThat(decisions.values()).allSatisfy(decision -> {
            assertThat(decision.reviewer()).isNotBlank();
            assertThat(decision.decidedAt()).isNotNull();
            assertThat(decision.expiresAt()).isNotNull();
            SourceUsePurpose purpose = decision.purpose();
            // Probed relative to the decision's own date rather than a fixed instant: a wall-clock
            // constant here silently becomes an assertion that no decision was ever taken after it,
            // and a source added later then fails this test for having a truthful date.
            assertThat(decision.permitsAt(decision.decidedAt().plusSeconds(1), purpose))
                    .isTrue();
        });
    }

    @Test
    void shouldRejectEngineeringBaselineWithoutApprovalMetadata() throws IOException {
        JsonNode root = read(ClasspathArtifactSourceCatalogRegistry.USE_DECISIONS_RESOURCE)
                .deepCopy();
        ((tools.jackson.databind.node.ObjectNode) root.path("decisions").get(0)).remove("reviewer");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ClasspathArtifactSourceCatalogRegistry.parseUseDecisions(root))
                .withMessageContaining("requires review metadata");
    }

    @Test
    void shouldRecheckApprovalExpiryWhenASourceIsRequested() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-08-03T12:00:00Z"));
        var registry = new ClasspathArtifactSourceCatalogRegistry(objectMapper, clock);
        when(clock.instant()).thenReturn(Instant.parse("2027-08-03T00:00:00Z"));

        assertThat(registry.isSourceUsePermitted(
                        new SourceContractVersion("1.0.0"),
                        new SourceKind("scm.pull-request.diff"),
                        SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isFalse();
    }

    @Test
    void shouldPermitNonSensitiveSourcesWithoutOperatorConfiguration() {
        var registry = new ClasspathArtifactSourceCatalogRegistry(objectMapper, Clock.systemUTC());
        var version = new SourceContractVersion("1.0.0");

        assertThat(registry.isSourceUsePermitted(
                        version, new SourceKind("scm.pull-request.diff"), SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isTrue();
        assertThat(registry.isSourceUsePermitted(
                        version, new SourceKind("scm.issue.core"), SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isTrue();
    }

    @Test
    void shouldEnforceEachApprovedPurpose() throws IOException {
        JsonNode root = read(ClasspathArtifactSourceCatalogRegistry.USE_DECISIONS_RESOURCE)
                .deepCopy();
        var decisions = ClasspathArtifactSourceCatalogRegistry.parseUseDecisions(root);
        var assessment = decisions.get("use-scm-pull-request-core-automated-review");
        var feedback = decisions.get("use-scm-pull-request-core-feedback-delivery");
        org.junit.jupiter.api.Assertions.assertNotNull(assessment);
        org.junit.jupiter.api.Assertions.assertNotNull(feedback);

        assertThat(assessment.permitsAt(
                        Instant.parse("2026-08-03T12:00:00Z"), SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isTrue();
        assertThat(assessment.permitsAt(
                        Instant.parse("2026-08-03T12:00:00Z"), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
        assertThat(feedback.permitsAt(
                        Instant.parse("2026-08-03T12:00:00Z"), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isTrue();
    }

    /** A decision stops permitting at its expiry, not after it. */
    @Test
    void shouldStopPermittingAtTheInstantOfExpiry() throws IOException {
        var decisions = ClasspathArtifactSourceCatalogRegistry.parseUseDecisions(
                read(ClasspathArtifactSourceCatalogRegistry.USE_DECISIONS_RESOURCE));
        var decision = decisions.get("use-docs-document-core-automated-review");
        org.junit.jupiter.api.Assertions.assertNotNull(decision);
        Instant expiry = Instant.parse("2027-08-07T00:00:00Z");

        assertThat(decision.permitsAt(expiry.minusMillis(1), SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isTrue();
        assertThat(decision.permitsAt(expiry, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isFalse();
    }

    /** Symmetrically, a decision permits from the instant it was taken and not before it. */
    @Test
    void shouldStartPermittingAtTheInstantItWasDecided() throws IOException {
        var decisions = ClasspathArtifactSourceCatalogRegistry.parseUseDecisions(
                read(ClasspathArtifactSourceCatalogRegistry.USE_DECISIONS_RESOURCE));
        var decision = decisions.get("use-docs-document-core-automated-review");
        org.junit.jupiter.api.Assertions.assertNotNull(decision);
        Instant decided = Instant.parse("2026-08-07T00:00:00Z");

        assertThat(decision.permitsAt(decided, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isTrue();
        assertThat(decision.permitsAt(decided.minusMillis(1), SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isFalse();
    }

    @Test
    void shouldRejectMissingUseDecision() throws IOException {
        var catalog = ClasspathArtifactSourceCatalogRegistry.parse(
                read(ClasspathArtifactSourceCatalogRegistry.CATALOG_RESOURCE));
        var decisions = new HashMap<>(ClasspathArtifactSourceCatalogRegistry.parseUseDecisions(
                read(ClasspathArtifactSourceCatalogRegistry.USE_DECISIONS_RESOURCE)));
        decisions.remove("use-scm-repository-tree-automated-review");

        assertThatIllegalStateException()
                .isThrownBy(() -> ClasspathArtifactSourceCatalogRegistry.validateUseDecisions(catalog, decisions))
                .withMessageContaining("must match the catalog exactly");
    }

    /**
     * A source whose decisions leave one product purpose unapproved is refused, even though every
     * decision it does name exists and nothing is orphaned — the catalog and the decisions agree with
     * each other but disagree with the product.
     */
    @Test
    void shouldRejectASourceThatLacksADecisionForOnePurpose() throws IOException {
        JsonNode catalogNode =
                read(ClasspathArtifactSourceCatalogRegistry.CATALOG_RESOURCE).deepCopy();
        var useDecisionIds = (tools.jackson.databind.node.ArrayNode)
                catalogNode.path("sources").get(0).path("useDecisionIds");
        String dropped = useDecisionIds.get(useDecisionIds.size() - 1).asString();
        useDecisionIds.remove(useDecisionIds.size() - 1);
        var catalog = ClasspathArtifactSourceCatalogRegistry.parse(catalogNode);
        var decisions = new HashMap<>(ClasspathArtifactSourceCatalogRegistry.parseUseDecisions(
                read(ClasspathArtifactSourceCatalogRegistry.USE_DECISIONS_RESOURCE)));
        decisions.remove(dropped);

        assertThatIllegalStateException()
                .isThrownBy(() -> ClasspathArtifactSourceCatalogRegistry.validateUseDecisions(catalog, decisions))
                .withMessageContaining("do not cover every product purpose");
    }

    @Test
    void shouldRejectOrphanUseDecision() throws IOException {
        var catalog = ClasspathArtifactSourceCatalogRegistry.parse(
                read(ClasspathArtifactSourceCatalogRegistry.CATALOG_RESOURCE));
        var decisions = new HashMap<>(ClasspathArtifactSourceCatalogRegistry.parseUseDecisions(
                read(ClasspathArtifactSourceCatalogRegistry.USE_DECISIONS_RESOURCE)));
        decisions.put("orphan", decisions.values().iterator().next());

        assertThatIllegalStateException()
                .isThrownBy(() -> ClasspathArtifactSourceCatalogRegistry.validateUseDecisions(catalog, decisions))
                .withMessageContaining("must match the catalog exactly");
    }

    @Test
    void shouldKeepMachineReadableSchemasVersionedAndClosed() throws IOException {
        for (String name : new String[] {
            "artifact-source-catalog.schema.json",
            "artifact-source-manifest.schema.json",
            "practice-automated-review-policy.schema.json",
            "automated-review-readiness-report.schema.json",
            "source-use-decisions.schema.json",
        }) {
            JsonNode schema = read("contracts/artifact-source/1.0.0/" + name);
            assertThat(schema.path("$schema").asString()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
            assertThat(schema.path("$id").asString()).contains("/1.0.0/");
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        }
    }

    @Test
    void shouldPinTheAbsenceReasonVocabularyToTheJavaEnum() throws IOException {
        // The schema restates this "closed vocabulary" by hand; nothing validates a manifest against the
        // schema in production, so the restatement is held to the enum here instead.
        JsonNode schema = read("contracts/artifact-source/1.0.0/artifact-source-manifest.schema.json");
        List<String> expected =
                Stream.of(SourceAbsenceReason.values()).map(Enum::name).toList();

        List<JsonNode> vocabularies = schema.findValues("reasonCode");
        vocabularies.addAll(schema.findValues("errorCode"));
        assertThat(vocabularies)
                .as("both the absence and the collection-error vocabularies")
                .hasSize(2);

        for (JsonNode vocabulary : vocabularies) {
            List<String> declared = vocabulary
                    .path("enum")
                    .valueStream()
                    .map(JsonNode::asString)
                    .toList();
            assertThat(declared)
                    .as("schema vocabulary vs SourceAbsenceReason.values()")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    /** The same hand-restatement problem as the absence reasons, on the three governance vocabularies. */
    @Test
    void shouldPinTheGovernanceVocabulariesToTheirJavaEnums() throws IOException {
        JsonNode catalogSchema = read("contracts/artifact-source/1.0.0/artifact-source-catalog.schema.json");
        JsonNode decisionsSchema = read("contracts/artifact-source/1.0.0/source-use-decisions.schema.json");
        JsonNode source = catalogSchema.path("$defs").path("source").path("properties");
        JsonNode decision = decisionsSchema
                .path("properties")
                .path("decisions")
                .path("items")
                .path("properties");

        assertThat(vocabularyOf(source.path("privacyClass")))
                .containsExactlyInAnyOrderElementsOf(names(PrivacyClass.values()));
        assertThat(vocabularyOf(decision.path("basis")))
                .containsExactlyInAnyOrderElementsOf(names(SourceUseBasis.values()));
        assertThat(vocabularyOf(decision.path("outcome")))
                .containsExactlyInAnyOrderElementsOf(names(SourceUseOutcome.values()));
    }

    /** A one-value vocabulary is written as {@code const}, a wider one as {@code enum}. */
    private static List<String> vocabularyOf(JsonNode property) {
        assertThat(property.isObject())
                .as("schema property to read a vocabulary from")
                .isTrue();
        if (property.has("const")) {
            return List.of(property.path("const").asString());
        }
        return property.path("enum").valueStream().map(JsonNode::asString).toList();
    }

    private static List<String> names(Enum<?>[] constants) {
        return Stream.of(constants).map(Enum::name).toList();
    }

    @Test
    void shouldAllowCaptureFactsWithoutAWatermarkOrImmutableIdentity() throws IOException {
        JsonNode factsSchema = read("contracts/artifact-source/1.0.0/artifact-source-manifest.schema.json")
                .path("$defs")
                .path("facts");

        assertThat(factsSchema.has("anyOf")).isFalse();
        assertThat(factsSchema.path("required").toString())
                .doesNotContain("sourceEffectiveAt", "observedAt", "immutableIdentity");
    }

    private JsonNode read(String resource) throws IOException {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            return objectMapper.readTree(input);
        }
    }
}
