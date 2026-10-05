package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.EvidencePlan;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The commit history is staged from the same prepared range as the change, so a change that could not be
 * captured leaves no {@code commits.json} even when the pull request record itself is complete. The bundled
 * practices that judge that history are withheld then, and run when both are captured.
 */
class ChangeCaptureFailureReadinessTest extends BaseUnitTest {

    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final List<String> COMMIT_HISTORY =
            List.of("commit-subjects-explain-each-change", "commits-are-atomic-and-cohesive");

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
    void shouldWithholdTheCommitHistoryPracticesWhenTheRecordIsCompleteButTheChangeFailed() {
        Map<String, AutomatedReviewReadinessDecision> decisions = decide(false);

        assertThat(COMMIT_HISTORY)
                .allSatisfy(slug -> assertThat(decisions).hasEntrySatisfying(slug, decision -> {
                    assertThat(decision.ready()).as(slug).isFalse();
                    assertThat(decision.sourceChecks())
                            .filteredOn(check -> !check.meetsRequirements())
                            .singleElement()
                            .satisfies(check -> {
                                assertThat(check.sourceKind()).isEqualTo(DIFF);
                                assertThat(check.reasonCodes())
                                        .containsExactly(SourceReadinessReason.SOURCE_NOT_AVAILABLE);
                            });
                }));
        assertThat(decisions)
                .as("a practice that reads only the record still runs")
                .hasEntrySatisfying(
                        "describe-what-and-why",
                        decision -> assertThat(decision.ready()).isTrue());
    }

    @Test
    void shouldReviewTheCommitHistoryWhenTheRecordAndTheChangeWereCaptured() {
        Map<String, AutomatedReviewReadinessDecision> decisions = decide(true);

        assertThat(COMMIT_HISTORY)
                .allSatisfy(slug -> assertThat(decisions)
                        .hasEntrySatisfying(
                                slug,
                                decision ->
                                        assertThat(decision.ready()).as(slug).isTrue()));
    }

    private Map<String, AutomatedReviewReadinessDecision> decide(boolean changeCaptured) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, SourceKind> kinds = new HashMap<>();
        stage(files, kinds, "context/metadata.json", "{\"title\":\"Add the plant list\"}", CORE);
        stage(files, kinds, "context/description.md", "Adds the plant list.\n", CORE);
        Map<SourceKind, SourceCompleteness> completeness = new HashMap<>(Map.of(CORE, SourceCompleteness.COMPLETE));
        Map<SourceKind, String> identities = new HashMap<>();
        Map<SourceKind, SourceCaptureState> overrides = new HashMap<>();
        if (changeCaptured) {
            stage(files, kinds, "context/change.json", "{\"base_sha\":\"base\",\"head_sha\":\"head\"}", DIFF);
            stage(files, kinds, "context/commits.json", "{\"truncated\":false,\"commits\":[]}", CORE);
            completeness.put(DIFF, SourceCompleteness.COMPLETE);
            identities.put(DIFF, "base:head");
        } else {
            overrides.put(DIFF, new SourceCaptureState.CollectionError(SourceAbsenceReason.PROVIDER_FAILURE));
        }
        JobFolderIndex manifest = builder.augment(
                files,
                kinds,
                "job-change-capture",
                new EvidencePlan(new SourceContractVersion("1.3.0"), ArtifactKinds.PULL_REQUEST),
                new JobFolderIndexBuilder.CaptureMetadata(
                        completeness, identities, Map.of(), Map.of(), overrides, Set.of(CORE, DIFF)));
        List<Practice> pullRequestPractices = loader.catalog().practices().stream()
                .filter(entry -> entry.definition().artifactKind().equals(ArtifactKinds.PULL_REQUEST))
                .filter(entry -> entry.definition().automatedReviewPolicy() != null)
                .map(entry -> {
                    Practice practice = new Practice();
                    practice.setSlug(entry.slug());
                    practice.setSignals(entry.definition().signals());
                    practice.setEvidenceRequirements(entry.definition().evidenceRequirements());
                    practice.setReviewWhen(entry.definition().reviewWhen());
                    practice.setSubject(entry.definition().subject());
                    practice.setPrecondition(entry.definition().precondition());
                    practice.setAutomatedReviewPolicy(entry.definition().automatedReviewPolicy());
                    return practice;
                })
                .toList();
        return builder.checkAutomatedReviewReadinessAsOfNow(manifest, pullRequestPractices).decisions().stream()
                .collect(Collectors.toMap(AutomatedReviewReadinessDecision::practiceSlug, Function.identity()));
    }

    private static void stage(
            Map<String, byte[]> files, Map<String, SourceKind> kinds, String path, String content, SourceKind kind) {
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
        kinds.put(path, kind);
    }
}
