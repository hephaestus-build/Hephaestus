package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewPersonMode;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryMode;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryTarget;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Replacing review coverage after reading it in the same transaction writes exactly the requested targets. */
class PracticeReviewCoverageReplaceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private PracticeReviewCoverageService coverageService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long workspaceId;
    private Long owner;
    private Long first;
    private Long second;

    @BeforeEach
    void setUpWorkspace() {
        User ownerUser = persistUser("coverage-owner");
        Workspace workspace = createWorkspace("coverage-replace", "Coverage", "acme", AccountType.ORG, ownerUser);
        workspaceId = workspace.getId();
        for (String name : List.of("acme/a", "acme/b", "acme/c")) {
            RepositoryToMonitor monitor = new RepositoryToMonitor();
            monitor.setNameWithOwner(name);
            monitor.setWorkspace(workspace);
            monitorRepository.save(monitor);
        }
        owner = member(workspace, ownerUser, WorkspaceRole.OWNER);
        first = member(workspace, persistUser("coverage-first"), WorkspaceRole.MEMBER);
        second = member(workspace, persistUser("coverage-second"), WorkspaceRole.MEMBER);
    }

    @Test
    void shouldStoreExactlyTheRequestedTargetsWhenCoverageWasReadInTheSameTransaction() {
        WorkspaceReviewScope initial =
                selected(List.of(repository("acme/a", "main"), repository("acme/b")), List.of(owner, first));
        assertThat(replaceAfterReading(initial)).isEqualTo(initial);

        WorkspaceReviewScope mixed =
                selected(List.of(repository("acme/a", "release"), repository("acme/c")), List.of(first, second));
        assertThat(replaceAfterReading(mixed))
                .as("retain a with new branch, drop b, add c")
                .isEqualTo(mixed);
        assertThat(replaceAfterReading(mixed)).as("the same selection again").isEqualTo(mixed);

        assertThat(replaceAfterReading(WorkspaceReviewScope.ALL)).isEqualTo(WorkspaceReviewScope.ALL);

        WorkspaceReviewScope reverse = selected(List.of(repository("acme/a", "main")), List.of(owner));
        assertThat(replaceAfterReading(reverse)).isEqualTo(reverse);
    }

    /** Reads the scope, loading every stored target, then replaces it in the same transaction. */
    private WorkspaceReviewScope replaceAfterReading(WorkspaceReviewScope requested) {
        transactionTemplate.executeWithoutResult(status -> {
            Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow();
            coverageService.scope(workspace);
            coverageService.replace(workspace, requested);
        });
        return Objects.requireNonNull(transactionTemplate.execute(status ->
                coverageService.scope(workspaceRepository.findById(workspaceId).orElseThrow())));
    }

    private Long member(Workspace workspace, User user, WorkspaceRole role) {
        ensureWorkspaceMembership(workspace, user, role);
        return user.getId();
    }

    private static WorkspaceReviewScope selected(List<ReviewRepositoryTarget> repositories, List<Long> people) {
        return new WorkspaceReviewScope(ReviewRepositoryMode.SELECTED, ReviewPersonMode.SELECTED, repositories, people);
    }

    private static ReviewRepositoryTarget repository(String nameWithOwner, String... baseBranches) {
        return new ReviewRepositoryTarget(nameWithOwner, List.of(baseBranches));
    }
}
