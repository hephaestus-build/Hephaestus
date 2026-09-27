package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.ContextManifestBuilder;
import de.tum.cit.aet.hephaestus.agent.context.EvidencePlan;
import de.tum.cit.aet.hephaestus.agent.context.PracticeSubjectEvaluator;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessDecision;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceReadinessReason;
import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceDefaults;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptionsFixture;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.AutomatedReviewFence;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A failed linked-work-item capture — including a change whose own text names more issue numbers than
 * the capture holds — withholds the bundled practices that require the source before any model is
 * asked, and only those.
 */
class LinkedWorkItemCaptureFailureReadinessTest extends BaseUnitTest {

    private static final SourceKind LINKED_ITEMS = new SourceKind("scm.linked-work-items");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ClasspathArtifactSourceCatalogRegistry catalogs =
            new ClasspathArtifactSourceCatalogRegistry(mapper, Clock.systemUTC());
    private final BundledPracticeCatalogLoader loader = new BundledPracticeCatalogLoader(
            mapper,
            new PracticeDefinitionValidator(catalogs, PracticeSignalOptionsFixture.real()),
            new PracticeEvidenceDefaults(catalogs, PracticeSignalOptionsFixture.catalog()));
    private final ContextManifestBuilder builder = new ContextManifestBuilder(
            mapper,
            catalogs,
            new PracticeSubjectEvaluator(mapper),
            new AutomatedReviewFence(loader.withdrawnFromAutomatedReview()),
            Clock.systemUTC());

    @Test
    void shouldWithholdExactlyThePracticesThatRequireLinkedWorkItems() {
        ArtifactSourceManifest manifest = builder.augment(
                new LinkedHashMap<>(),
                Map.of(),
                "job-linked-work-items-failed",
                new EvidencePlan(new SourceContractVersion("1.2.0"), ArtifactKinds.PULL_REQUEST),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(
                                LINKED_ITEMS,
                                new SourceCaptureState.CollectionError(SourceAbsenceReason.PROVIDER_FAILURE)),
                        Set.of(LINKED_ITEMS)));
        List<Practice> pullRequestPractices = loader.catalog().practices().stream()
                .filter(entry -> entry.definition().artifactKind().equals(ArtifactKinds.PULL_REQUEST))
                .filter(entry -> entry.definition().automatedReviewPolicy() != null)
                .map(entry -> {
                    Practice practice = new Practice();
                    practice.setSlug(entry.slug());
                    practice.setBindings(entry.definition().bindings());
                    practice.setAutomatedReviewPolicy(entry.definition().automatedReviewPolicy());
                    return practice;
                })
                .toList();

        var readiness = builder.checkAutomatedReviewReadinessAsOfNow(manifest, pullRequestPractices);

        assertThat(readiness.decisions())
                .filteredOn(decision -> decision.sourceChecks().stream()
                        .anyMatch(check -> check.sourceKind().equals(LINKED_ITEMS)
                                && check.reasonCodes().contains(SourceReadinessReason.SOURCE_NOT_AVAILABLE)))
                .allMatch(decision -> !decision.ready())
                .extracting(AutomatedReviewReadinessDecision::practiceSlug)
                .containsExactlyInAnyOrder(
                        "honours-linked-issue-acceptance-criteria",
                        "change-keeps-linked-docs-consistent",
                        "merge-confirms-the-linked-issue-outcome",
                        "plans-the-work-in-an-issue-first");
    }
}
