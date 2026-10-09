package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.EvidencePlan;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndexBuilder;
import de.tum.cit.aet.hephaestus.agent.context.PracticePreconditionEvaluator;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessDecision;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IssueDiscussionCaptureReadinessTest extends BaseUnitTest {

    private static final SourceKind CORE = new SourceKind("scm.issue.core");
    private static final SourceKind COMMENTS = new SourceKind("scm.issue.comments");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ClasspathArtifactSourceCatalogRegistry catalogs =
            new ClasspathArtifactSourceCatalogRegistry(mapper, Clock.systemUTC());
    private final BundledPracticeCatalogLoader loader = new BundledPracticeCatalogLoader(
            mapper,
            new PracticeDefinitionValidator(catalogs, PracticeSignalOptionsFixture.real()),
            new PracticeEvidenceDefaults(catalogs, PracticeSignalOptionsFixture.catalog()));
    private final JobFolderIndexBuilder builder = new JobFolderIndexBuilder(
            mapper,
            catalogs,
            new PracticePreconditionEvaluator(mapper),
            new AutomatedReviewFence(loader.withdrawnFromAutomatedReview()),
            Clock.systemUTC());

    @Test
    void shouldReviewRelevantContextWhenTheCompleteDiscussionIsEmpty() {
        assertThat(decide(SourceCompleteness.COMPLETE, true).ready()).isTrue();
    }

    @Test
    void shouldNotReviewRelevantContextWhenTheDiscussionIsIncomplete() {
        assertDiscussionRefusal(decide(SourceCompleteness.PARTIAL, true), SourceReadinessReason.SOURCE_INCOMPLETE);
    }

    @Test
    void shouldNotReviewRelevantContextWhenTheDiscussionCaptureFailed() {
        assertDiscussionRefusal(decide(SourceCompleteness.COMPLETE, false), SourceReadinessReason.SOURCE_NOT_AVAILABLE);
    }

    private AutomatedReviewReadinessDecision decide(SourceCompleteness discussionCompleteness, boolean captured) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(
                "context/metadata.json",
                "{\"title\":\"Apply the documented migration\"}".getBytes(StandardCharsets.UTF_8));
        files.put("context/description.md", "Apply the agreed data model.\n".getBytes(StandardCharsets.UTF_8));
        Map<String, SourceKind> kinds = new LinkedHashMap<>(Map.of(
                "context/metadata.json", CORE,
                "context/description.md", CORE));
        Map<SourceKind, SourceCompleteness> completeness =
                new LinkedHashMap<>(Map.of(CORE, SourceCompleteness.COMPLETE));
        Map<SourceKind, SourceCaptureState> overrides = new LinkedHashMap<>();
        if (captured) {
            files.put("context/comments.json", "[]".getBytes(StandardCharsets.UTF_8));
            kinds.put("context/comments.json", COMMENTS);
            completeness.put(COMMENTS, discussionCompleteness);
        } else {
            overrides.put(COMMENTS, new SourceCaptureState.CollectionError(SourceAbsenceReason.PROVIDER_FAILURE));
        }
        var manifest = builder.augment(
                files,
                kinds,
                "job-issue-discussion",
                new EvidencePlan(new SourceContractVersion("1.3.0"), ArtifactKinds.ISSUE),
                new JobFolderIndexBuilder.CaptureMetadata(
                        completeness, Map.of(), Map.of(), Map.of(), overrides, Set.of(CORE, COMMENTS)));
        var entry = loader.catalog().practices().stream()
                .filter(candidate -> candidate.slug().equals("issue-points-to-relevant-context"))
                .findFirst()
                .orElseThrow();
        var definition = entry.definition();
        Practice practice = new Practice();
        practice.setSlug(entry.slug());
        practice.setSignals(definition.signals());
        practice.setEvidenceRequirements(definition.evidenceRequirements());
        practice.setReviewWhen(definition.reviewWhen());
        practice.setSubject(definition.subject());
        practice.setPrecondition(definition.precondition());
        practice.setAutomatedReviewPolicy(definition.automatedReviewPolicy());
        return builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practice))
                .decisions()
                .getFirst();
    }

    private static void assertDiscussionRefusal(
            AutomatedReviewReadinessDecision decision, SourceReadinessReason expectedReason) {
        assertThat(decision.ready()).isFalse();
        assertThat(decision.sourceChecks())
                .filteredOn(check -> !check.meetsRequirements())
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.sourceKind()).isEqualTo(COMMENTS);
                    assertThat(check.reasonCodes()).containsExactly(expectedReason);
                });
    }
}
