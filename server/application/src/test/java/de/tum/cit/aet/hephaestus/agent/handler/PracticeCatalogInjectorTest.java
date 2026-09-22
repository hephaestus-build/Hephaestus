package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceLimitation;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class PracticeCatalogInjectorTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private PracticeRepository practiceRepository;

    private PracticeCatalogInjector injector;

    @BeforeEach
    void setUp() {
        injector = new PracticeCatalogInjector(objectMapper, practiceRepository, workspaceDefaults());
    }

    private Practice practice(String slug, SignalName... signals) {
        Practice p = new Practice();
        p.setSlug(slug);
        p.setName(slug);
        p.setCriteria("criteria for " + slug);
        p.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        var revision = new PracticeRevision();
        ReflectionTestUtils.setField(revision, "id", Math.abs((long) slug.hashCode()) + 1);
        p.setCurrentRevision(revision);
        p.setBindings(PracticeTestEvidence.bindings(signals));
        return p;
    }

    /**
     * A workspace that has expressed no review opinion of its own, so every practice below it resolves to
     * {@link PracticeAutonomy#DEFAULT}.
     */
    private Workspace workspace() {
        Workspace ws = new Workspace();
        ws.setId(1L);
        return ws;
    }

    private AgentJob job(@Nullable SignalName signal) {
        AgentJob j = new AgentJob();
        j.setWorkspace(workspace());
        if (signal != null) {
            var meta = objectMapper.createObjectNode();
            meta.put("signal", signal.value());
            j.setMetadata(meta);
        }
        return j;
    }

    private AgentJob reviewerJob() {
        AgentJob job = job(ScmSignals.PULL_REQUEST_REVIEWED);
        var metadata = org.junit.jupiter.api.Assertions.assertInstanceOf(ObjectNode.class, job.getMetadata());
        metadata.put("about_user_id", 42L);
        metadata.put("subject_role", ActorRole.REVIEWER.name());
        return job;
    }

    private static String md(String slug) {
        return SandboxLayout.PRACTICES_PREFIX + slug + ".md";
    }

    @Test
    @DisplayName("a trigger event materialises only the practices that declare it")
    void filtersToTriggerMatchingPractices() {
        when(practiceRepository.findByWorkspaceIdAndArtifactKind(1L, ArtifactKinds.PULL_REQUEST))
                .thenReturn(List.of(
                        practice("authoring", ScmSignals.PULL_REQUEST_OPENED),
                        practice("retrospective", ScmSignals.PULL_REQUEST_MERGED),
                        practice("reviewer", ScmSignals.PULL_REQUEST_REVIEWED)));
        Map<String, byte[]> files = new HashMap<>();

        injector.inject(files, job(ScmSignals.PULL_REQUEST_MERGED), ArtifactKinds.PULL_REQUEST);

        assertThat(files).containsKey(md("retrospective"));
        assertThat(files).doesNotContainKey(md("authoring"));
        assertThat(files).doesNotContainKey(md("reviewer"));
    }

    @Test
    @DisplayName("a review of a draft materialises only the practices that review drafts, as the gate admitted")
    void shouldSelectOnlyDraftPracticesWhenTheWorkWasADraft() {
        Practice onDrafts = practice("handoff", ScmSignals.PULL_REQUEST_OPENED);
        onDrafts.setBindings(List.of(new PracticeBinding(
                List.of(ScmSignals.PULL_REQUEST_OPENED),
                PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST),
                true,
                ActorRole.AUTHOR)));
        when(practiceRepository.findByWorkspaceIdAndArtifactKind(1L, ArtifactKinds.PULL_REQUEST))
                .thenReturn(List.of(onDrafts, practice("describe", ScmSignals.PULL_REQUEST_OPENED)));
        AgentJob job = job(ScmSignals.PULL_REQUEST_OPENED);
        org.junit.jupiter.api.Assertions.assertInstanceOf(ObjectNode.class, job.getMetadata())
                .put(PracticeCatalogInjector.DRAFT_METADATA_KEY, true);
        Map<String, byte[]> files = new HashMap<>();

        injector.inject(files, job, ArtifactKinds.PULL_REQUEST);

        assertThat(files).containsKey(md("handoff"));
        assertThat(files).doesNotContainKey(md("describe"));
    }

    @Test
    void shouldSelectOnlyReviewerPracticesWhenSubmittedReviewNamesReviewer() {
        Practice author = practice("author-engagement", ScmSignals.PULL_REQUEST_REVIEWED);
        Practice reviewer = practice("review-comment-quality", ScmSignals.PULL_REQUEST_REVIEWED);
        reviewer.setBindings(List.of(new PracticeBinding(
                List.of(ScmSignals.PULL_REQUEST_REVIEWED),
                PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST),
                false,
                ActorRole.REVIEWER)));
        when(practiceRepository.findByWorkspaceIdAndArtifactKind(1L, ArtifactKinds.PULL_REQUEST))
                .thenReturn(List.of(author, reviewer));
        Map<String, byte[]> files = new HashMap<>();

        injector.inject(files, reviewerJob(), ArtifactKinds.PULL_REQUEST);

        assertThat(files).containsKey(md("review-comment-quality"));
        assertThat(files).doesNotContainKey(md("author-engagement"));
    }

    @Test
    @DisplayName("a job with no trigger event keeps the full focus set (legacy / bot-command path)")
    void noTriggerEventKeepsFullSet() {
        when(practiceRepository.findByWorkspaceIdAndArtifactKind(1L, ArtifactKinds.PULL_REQUEST))
                .thenReturn(List.of(
                        practice("authoring", ScmSignals.PULL_REQUEST_OPENED),
                        practice("retrospective", ScmSignals.PULL_REQUEST_MERGED)));
        Map<String, byte[]> files = new HashMap<>();

        injector.inject(files, job(null), ArtifactKinds.PULL_REQUEST);

        assertThat(files).containsKey(md("authoring"));
        assertThat(files).containsKey(md("retrospective"));
    }

    @Test
    @DisplayName("a trigger event that matches nothing fails closed")
    void noMatchFailsClosed() {
        when(practiceRepository.findByWorkspaceIdAndArtifactKind(1L, ArtifactKinds.PULL_REQUEST))
                .thenReturn(List.of(
                        practice("authoring", ScmSignals.PULL_REQUEST_OPENED),
                        practice("retrospective", ScmSignals.PULL_REQUEST_MERGED)));
        Map<String, byte[]> files = new HashMap<>();

        assertThatThrownBy(() -> injector.inject(
                        files, job(SignalName.of("scm.pull_request.rebased")), ArtifactKinds.PULL_REQUEST))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining("No active scm.pull_request practices");
        assertThat(files).isEmpty();
    }

    @Test
    @DisplayName("a slug that violates the ABI pattern is rejected before it can escape the practices/ prefix")
    void abiSlugViolationThrows() {
        // Defense-in-depth: slugs are interpolated into filesystem paths, so a mis-seeded slug with a path
        // traversal must be rejected, not written to disk.
        when(practiceRepository.findByWorkspaceIdAndArtifactKind(1L, ArtifactKinds.PULL_REQUEST))
                .thenReturn(List.of(practice("../escape", ScmSignals.PULL_REQUEST_OPENED)));

        assertThatThrownBy(() -> injector.inject(new HashMap<>(), job(null), ArtifactKinds.PULL_REQUEST))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining("ABI pattern");
    }

    @Test
    @DisplayName("a job with no workspace throws JobPreparationException")
    void noWorkspaceThrows() {
        AgentJob job = new AgentJob();

        assertThatThrownBy(() -> injector.inject(new HashMap<>(), job, ArtifactKinds.PULL_REQUEST))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining("no workspace");
    }

    @Test
    @DisplayName("an empty active-practice set throws JobPreparationException")
    void emptyFocusSetThrows() {
        when(practiceRepository.findByWorkspaceIdAndArtifactKind(1L, ArtifactKinds.PULL_REQUEST))
                .thenReturn(List.of());

        assertThatThrownBy(() -> injector.inject(new HashMap<>(), job(null), ArtifactKinds.PULL_REQUEST))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining("No active");
    }

    @Test
    @DisplayName("a practice whose effective autonomy is OFF is not staged, and one that inherits its autonomy is")
    void offPracticesAreFilteredOutAfterTheTierIsResolved() {
        // The repository does not filter by autonomy: a `autonomy <> 'OFF'` predicate would answer UNKNOWN
        // for the null column that means "inherit", making the inheritance chain vanish from the
        // catalogue. The injector resolves the chain and filters here instead.
        Practice off = practice("silenced", ScmSignals.PULL_REQUEST_OPENED);
        off.setAutonomy(PracticeAutonomy.OFF);
        Practice inherits = practice("authoring", ScmSignals.PULL_REQUEST_OPENED);
        when(practiceRepository.findByWorkspaceIdAndArtifactKind(1L, ArtifactKinds.PULL_REQUEST))
                .thenReturn(List.of(off, inherits));
        Map<String, byte[]> files = new HashMap<>();

        injector.inject(files, job(null), ArtifactKinds.PULL_REQUEST);

        assertThat(files).containsKey(md("authoring"));
        assertThat(files).doesNotContainKey(md("silenced"));
    }

    @Test
    @DisplayName("inject writes index.json and the per-slug criteria, and skips blank precompute scripts")
    void injectWritesCatalogArtifactsAndSkipsBlankPrecompute() {
        Practice withScript = practice("authoring", ScmSignals.PULL_REQUEST_OPENED);
        withScript.setPrecomputeScript("export default () => ({});");
        Practice blankScript = practice("retrospective", ScmSignals.PULL_REQUEST_MERGED);
        blankScript.setPrecomputeScript("   "); // blank → no .ts written
        when(practiceRepository.findByWorkspaceIdAndArtifactKind(1L, ArtifactKinds.PULL_REQUEST))
                .thenReturn(List.of(withScript, blankScript));
        Map<String, byte[]> files = new HashMap<>();

        injector.inject(files, job(null), ArtifactKinds.PULL_REQUEST);

        // index.json lists both practices (group falls back to the slug when ungrouped).
        String index = new String(files.get(SandboxLayout.PRACTICES_PREFIX + "index.json"), StandardCharsets.UTF_8);
        assertThat(index).contains("authoring").contains("retrospective");
        // A pointer to where this practice's author expects the answer — not a fence. What may be cited
        // is what the run staged, and inputs/manifest.json is where that is stated, once.
        assertThat(index).contains("readsSources").contains("scm.pull-request.diff");
        assertThat(index).doesNotContain("allowedSources");
        // Per-slug criteria only: the model reads each practice's own file, so there is no bundle to drift.
        assertThat(files)
                .containsKey(md("authoring"))
                .containsKey(md("retrospective"))
                .doesNotContainKey(SandboxLayout.PRACTICES_PREFIX + "all-criteria.md");
        // Only the populated precompute script is written; the blank one is skipped.
        assertThat(files).containsKey(SandboxLayout.PRECOMPUTE_PREFIX + "practices/authoring.ts");
        assertThat(files).doesNotContainKey(SandboxLayout.PRECOMPUTE_PREFIX + "practices/retrospective.ts");
    }

    @Test
    @DisplayName("staged criteria tell the model which claims the evidence cannot support")
    void injectAppendsKnownLimitationsToCriteria() {
        Practice limited = practice("authoring", ScmSignals.PULL_REQUEST_OPENED);
        PracticeAutomatedReviewPolicy policy = limited.getAutomatedReviewPolicy();
        limited.setAutomatedReviewPolicy(new PracticeAutomatedReviewPolicy(
                policy.sourceContractVersion(),
                policy.automatedReview(),
                policy.whenEvidenceIsInsufficient(),
                List.of(new PracticeEvidenceLimitation(
                        "RUNTIME_BEHAVIOR_NOT_OBSERVED",
                        "Repository evidence does not establish behavior in a deployed runtime.")),
                null));
        Practice unlimited = practice("retrospective", ScmSignals.PULL_REQUEST_OPENED);
        when(practiceRepository.findByWorkspaceIdAndArtifactKind(1L, ArtifactKinds.PULL_REQUEST))
                .thenReturn(List.of(limited, unlimited));
        Map<String, byte[]> files = new HashMap<>();

        injector.inject(files, job(null), ArtifactKinds.PULL_REQUEST);

        String staged = new String(files.get(md("authoring")), StandardCharsets.UTF_8);
        assertThat(staged)
                .contains("criteria for authoring")
                .contains("## What this evidence cannot show")
                .contains("Repository evidence does not establish behavior in a deployed runtime.");
        assertThat(new String(files.get(md("retrospective")), StandardCharsets.UTF_8))
                .doesNotContain("## What this evidence cannot show");
    }

    @Test
    @DisplayName("whyBySlug keeps populated principles and omits blank ones")
    void whyBySlugOmitsBlankPrinciples() {
        Practice withWhy = practice("authoring", ScmSignals.PULL_REQUEST_OPENED);
        withWhy.setWhyItMatters("Clear descriptions help reviewers.");
        Practice blankWhy = practice("retrospective", ScmSignals.PULL_REQUEST_MERGED);
        blankWhy.setWhyItMatters("   ");
        when(practiceRepository.findByWorkspaceIdAndArtifactKind(1L, ArtifactKinds.PULL_REQUEST))
                .thenReturn(List.of(withWhy, blankWhy));

        Map<String, String> why = injector.whyBySlug(workspace(), ArtifactKinds.PULL_REQUEST);

        assertThat(why)
                .containsEntry("authoring", "Clear descriptions help reviewers.")
                .doesNotContainKey("retrospective");
    }

    /** Resolves every workspace to the unset defaults — AUTOMATIC autonomy, reach on the work. */
    private static WorkspaceReviewDefaultsProvider workspaceDefaults() {
        WorkspaceReviewDefaultsProvider provider = mock(WorkspaceReviewDefaultsProvider.class);
        lenient().when(provider.forWorkspace(anyLong())).thenReturn(WorkspaceReviewDefaults.UNSET);
        return provider;
    }
}
