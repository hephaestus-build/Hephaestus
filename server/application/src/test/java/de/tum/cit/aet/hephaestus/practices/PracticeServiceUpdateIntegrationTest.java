package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.dto.ClearablePracticeField;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PracticeServiceUpdateIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private PracticeService practiceService;

    @Autowired
    private PracticeRepository practiceRepository;

    private Workspace workspace;
    private WorkspaceContext ctx;

    @BeforeEach
    void setUpWorkspace() {
        var owner = persistUser("practice-editor");
        workspace = createWorkspace("practice-edit", "Practice edits", "practice-edit-org", AccountType.ORG, owner);
        ctx = WorkspaceContext.fromWorkspace(workspace, Set.of(WorkspaceMembership.WorkspaceRole.ADMIN), null);
    }

    private Practice scopedPractice(String slug) {
        var practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName("Review Swift code");
        PracticeTestEvidence.configure(practice, ScmSignals.PULL_REQUEST_OPENED);
        practice.setSubject(ActorRole.REVIEWER);
        practice.setPrecondition(new PracticePrecondition(
                "the change has no Swift code",
                List.of(PracticePreconditionClause.changedPathMatches(List.of("**/*.swift")))));
        practice.setCriteria("Assess the review");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        return practiceRepository.save(practice);
    }

    @Test
    void nameOnlyUpdatePreservesAttributionAndPrecondition() {
        var before = scopedPractice("scoped-name");
        var precondition = before.getPrecondition();
        practiceService.updatePractice(
                ctx,
                before.getSlug(),
                new UpdatePracticeRequestDTO(
                        "New name", null, null, null, null, null, null, null, null, null, null, null, null, null));
        var reloaded = practiceService.getPractice(ctx, before.getSlug());
        assertThat(reloaded.getName()).isEqualTo("New name");
        assertThat(reloaded.getPrecondition()).isEqualTo(precondition);
        assertThat(reloaded.getSubject()).isEqualTo(ActorRole.REVIEWER);
    }

    @Test
    void scopeRemovalRequiresIntentAndCanBeSaved() {
        var before = scopedPractice("scoped-removal");
        assertThatThrownBy(() -> practiceService.updatePractice(ctx, before.getSlug(), removal(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("change when this practice applies");
        practiceService.updatePractice(
                ctx, before.getSlug(), removal(Set.of(DefinitionChange.PRECONDITION, DefinitionChange.SUBJECT)));
        var reloaded = practiceService.getPractice(ctx, before.getSlug());
        assertThat(reloaded.getPrecondition()).isNull();
        assertThat(reloaded.getSubject()).isEqualTo(ActorRole.AUTHOR);
    }

    private UpdatePracticeRequestDTO removal(@Nullable Set<DefinitionChange> changes) {
        return new UpdatePracticeRequestDTO(
                null,
                null,
                null,
                null,
                ActorRole.AUTHOR,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Set.of(ClearablePracticeField.PRECONDITION),
                changes);
    }
}
