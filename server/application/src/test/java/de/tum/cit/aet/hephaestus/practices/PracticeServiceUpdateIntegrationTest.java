package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A practice is reviewed on one occasion — a rule about the occasion a caller submits.
 *
 * <p>Rows holding two occasions were legal until the rule landed, and nothing collapses them, so an
 * edit that does not mention occasions has to go through. The alternative is a practice that can
 * never be renamed, re-worded, re-tiered or moved again by anyone.
 */
class PracticeServiceUpdateIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String ONE_OCCASION_REFUSAL =
            "A practice is reviewed on one occasion. To read different evidence at a different moment, "
                    + "split this into two practices.";

    @Autowired
    private PracticeService practiceService;

    @Autowired
    private PracticeRepository practiceRepository;

    private Workspace workspace;
    private WorkspaceContext ctx;

    @BeforeEach
    void setUpWorkspace() {
        User owner = persistUser("two-occasion-owner");
        workspace = createWorkspace("two-occasion-ws", "Two occasion WS", "two-occasion-org", AccountType.ORG, owner);
        ctx = WorkspaceContext.fromWorkspace(workspace, Set.of(WorkspaceMembership.WorkspaceRole.ADMIN), null);
    }

    /**
     * Written through the repository on purpose: this is the shape of a row stored before the rule
     * existed, which no authoring path can produce today.
     */
    private Practice persistTwoOccasionPractice(String slug) {
        Practice practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName("Before the rule");
        practice.setBindings(List.of(
                PracticeBinding.on(
                        ScmSignals.PULL_REQUEST_OPENED, PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST)),
                PracticeBinding.on(
                        ScmSignals.PULL_REQUEST_MERGED, PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST))));
        practice.setCriteria("Assess the review");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        return practiceRepository.save(practice);
    }

    private Practice persistScopedPractice(String slug) {
        PracticeSubject gate = new PracticeSubject(
                "the change has no Swift code",
                List.of(PracticeSubjectClause.changedPathMatches(List.of("**/*.swift"))));
        Practice practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName("Review Swift code");
        practice.setBindings(List.of(new PracticeBinding(
                List.of(ScmSignals.PULL_REQUEST_OPENED),
                PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST),
                false,
                ActorRole.REVIEWER,
                gate)));
        practice.setCriteria("Assess the review");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        return practiceRepository.save(practice);
    }

    @Test
    void shouldKeepTheGateAndReviewerAfterANameOnlyUpdate() {
        Practice before = persistScopedPractice("scoped-name");
        PracticeBinding binding = before.getBindings().getFirst();

        practiceService.updatePractice(
                ctx,
                before.getSlug(),
                new UpdatePracticeRequestDTO("New name", null, null, null, null, null, null, null, null, null));

        Practice reloaded = practiceService.getPractice(ctx, before.getSlug());
        assertThat(reloaded.getName()).isEqualTo("New name");
        assertThat(reloaded.getBindings().getFirst().appliesWhen()).isEqualTo(binding.appliesWhen());
        assertThat(reloaded.getBindings().getFirst().subject()).isEqualTo(ActorRole.REVIEWER);
    }

    @Test
    void shouldRefuseScopeRemovalWithoutIntentAndAllowItWithIntent() {
        Practice before = persistScopedPractice("scoped-removal");
        PracticeBinding binding = before.getBindings().getFirst();
        PracticeBinding unscoped =
                new PracticeBinding(binding.signals(), binding.needs(), binding.onDrafts(), ActorRole.AUTHOR, null);
        assertThatThrownBy(() -> practiceService.updatePractice(
                        ctx,
                        before.getSlug(),
                        new UpdatePracticeRequestDTO(
                                null, List.of(unscoped), null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPLIES_WHEN");

        practiceService.updatePractice(
                ctx,
                before.getSlug(),
                new UpdatePracticeRequestDTO(
                        null,
                        List.of(unscoped),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Set.of(BindingChange.APPLIES_WHEN, BindingChange.SUBJECT)));
        Practice reloaded = practiceService.getPractice(ctx, before.getSlug());
        assertThat(reloaded.getBindings().getFirst().appliesWhen()).isNull();
        assertThat(reloaded.getBindings().getFirst().subject()).isEqualTo(ActorRole.AUTHOR);
    }

    @Test
    @DisplayName("a stored practice holding two occasions can still be renamed")
    void renamesAPracticeStoredWithTwoOccasions() {
        persistTwoOccasionPractice("stored-with-two");

        Practice updated = practiceService.updatePractice(
                ctx,
                "stored-with-two",
                new UpdatePracticeRequestDTO("After the rule", null, null, null, null, null, null, null, null, null));

        assertThat(updated.getName()).isEqualTo("After the rule");
        // Untouched, not truncated: an edit that says nothing about occasions decides nothing about them.
        assertThat(updated.getBindings()).hasSize(2);
    }

    @Test
    @DisplayName("submitting a second occasion is still refused, with the split-it-in-two wording")
    void refusesASecondOccasionTheCallerSubmits() {
        persistTwoOccasionPractice("submits-two");

        assertThatThrownBy(() -> practiceService.updatePractice(
                        ctx,
                        "submits-two",
                        new UpdatePracticeRequestDTO(
                                null,
                                List.of(
                                        PracticeBinding.on(
                                                ScmSignals.PULL_REQUEST_OPENED,
                                                PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST)),
                                        PracticeBinding.on(
                                                ScmSignals.PULL_REQUEST_REVIEWED,
                                                PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST))),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ONE_OCCASION_REFUSAL);
    }

    @Test
    @DisplayName("submitting one occasion collapses a stored pair to it")
    void collapsesToTheOccasionTheCallerSubmits() {
        persistTwoOccasionPractice("collapses-to-one");

        Practice updated = practiceService.updatePractice(
                ctx,
                "collapses-to-one",
                new UpdatePracticeRequestDTO(
                        null,
                        List.of(PracticeBinding.on(
                                ScmSignals.PULL_REQUEST_MERGED,
                                PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST))),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertThat(updated.getBindings()).hasSize(1);
        assertThat(updated.getBindings().getFirst().signals()).containsExactly(ScmSignals.PULL_REQUEST_MERGED);
    }

    /**
     * Carried-over occasions are checked one at a time, not waved through: everything the validator
     * says about a single occasion, and about the prose around it, still applies.
     */
    @Test
    @DisplayName("an edit to a two-occasion practice is still validated")
    void stillValidatesTheRestOfAnEditToATwoOccasionPractice() {
        persistTwoOccasionPractice("still-validated");

        assertThatThrownBy(() -> practiceService.updatePractice(
                        ctx,
                        "still-validated",
                        new UpdatePracticeRequestDTO(
                                null, null, null, null, null, "Say PRESENT when it is there", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Why it matters is guidance for people and must not use detector result labels");
    }

    /** A one-occasion practice — every other practice on the instance — is unaffected. */
    @Test
    @DisplayName("a one-occasion practice is renamed exactly as before")
    void renamesAnOrdinaryPractice() {
        Practice practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug("ordinary");
        practice.setName("Ordinary");
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice.setCriteria("Assess the review");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practiceRepository.save(practice);

        assertThatCode(() -> practiceService.updatePractice(
                        ctx,
                        "ordinary",
                        new UpdatePracticeRequestDTO("Renamed", null, null, null, null, null, null, null, null, null)))
                .doesNotThrowAnyException();
    }
}
