package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewPersonTarget;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewPersonTargetRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewRepositoryTarget;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewRepositoryTargetRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewPersonMode;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryMode;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryTarget;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class PracticeReviewCoverageServiceTest extends BaseUnitTest {

    @Mock
    private RepositoryToMonitorRepository monitors;

    @Mock
    private WorkspaceMembershipService membershipService;

    @Mock
    private PracticeReviewRepositoryTargetRepository repositoryTargets;

    @Mock
    private PracticeReviewPersonTargetRepository people;

    private PracticeReviewCoverageService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new PracticeReviewCoverageService(monitors, membershipService, repositoryTargets, people);
        workspace = new Workspace();
        workspace.setId(1L);
    }

    @Test
    void selectedRepositoryFromAnotherTenantIsRejected() {
        when(monitors.findByWorkspaceId(1L)).thenReturn(List.of(monitor(11L, "owner/local")));
        WorkspaceReviewScope crossTenant = new WorkspaceReviewScope(
                ReviewRepositoryMode.SELECTED,
                ReviewPersonMode.ALL_ELIGIBLE,
                List.of(new ReviewRepositoryTarget("other/tenant", List.of())),
                List.of());

        assertThatThrownBy(() -> service.replace(workspace, crossTenant))
                .isInstanceOf(InvalidReviewCoverageException.class)
                .hasMessageContaining("not monitored by this workspace");
    }

    @Test
    void selectedPersonWithoutThisWorkspaceMembershipIsRejected() {
        when(monitors.findByWorkspaceId(1L)).thenReturn(List.of());
        when(membershipService.practiceReviewEligibleUserIds(1L)).thenReturn(Set.of(7L));
        WorkspaceReviewScope crossTenant = new WorkspaceReviewScope(
                ReviewRepositoryMode.ALL_MONITORED, ReviewPersonMode.SELECTED, List.of(), List.of(8L));

        assertThatThrownBy(() -> service.replace(workspace, crossTenant))
                .isInstanceOf(InvalidReviewCoverageException.class)
                .hasMessageContaining("eligible linked workspace member");
    }

    @Test
    void missingBotAndNonMemberSubjectsFailClosed() {
        ReviewSubject human = new ReviewSubject(7L, true);
        ReviewSubject bot = new ReviewSubject(8L, false);

        assertThat(service.admits(workspace, "owner/repo", "main", null)).isFalse();
        assertThat(service.admits(workspace, "owner/repo", "main", bot)).isFalse();
        assertThat(service.admits(workspace, "owner/repo", "main", human)).isFalse();
    }

    @Test
    void repositorylessWorkRequiresAllRepositoriesAndASelectedLinkedPerson() {
        workspace.getReviewSettings().applyRollout(ReviewRepositoryMode.ALL_MONITORED, ReviewPersonMode.SELECTED, null);
        when(people.findByWorkspaceId(1L)).thenReturn(List.of(new PracticeReviewPersonTarget(1L, 7L)));
        when(membershipService.isPracticeReviewEligible(1L, 7L)).thenReturn(true);

        var assessment = service.assessRepositoryless(workspace, new ReviewSubject(7L, true));

        assertThat(assessment.admitted()).isTrue();
        assertThat(assessment.repositoryMatched()).isTrue();
        assertThat(assessment.personMatched()).isTrue();
    }

    @Test
    void selectedRepositoriesFailClosedForRepositorylessWork() {
        workspace.getReviewSettings().applyRollout(ReviewRepositoryMode.SELECTED, ReviewPersonMode.ALL_ELIGIBLE, null);
        when(membershipService.isPracticeReviewEligible(1L, 7L)).thenReturn(true);

        var assessment = service.assessRepositoryless(workspace, new ReviewSubject(7L, true));

        assertThat(assessment.admitted()).isFalse();
        assertThat(assessment.repositoryMatched()).isFalse();
        assertThat(assessment.personMatched()).isTrue();
    }

    @Test
    void previewDistinguishesWideningFromNarrowingAcrossBothAxes() {
        workspace.getReviewSettings().applyRollout(ReviewRepositoryMode.SELECTED, ReviewPersonMode.SELECTED, null);
        when(monitors.findByWorkspaceId(1L))
                .thenReturn(List.of(monitor(11L, "owner/first"), monitor(12L, "owner/second")));
        when(membershipService.practiceReviewEligibleUserIds(1L)).thenReturn(Set.of(7L, 8L));
        when(repositoryTargets.findByWorkspaceId(1L))
                .thenReturn(List.of(new PracticeReviewRepositoryTarget(1L, 11L, List.of("main"))));
        when(people.findByWorkspaceId(1L)).thenReturn(List.of(new PracticeReviewPersonTarget(1L, 7L)));

        var addRepository = scope(
                List.of(
                        new ReviewRepositoryTarget("owner/first", List.of("main")),
                        new ReviewRepositoryTarget("owner/second", List.of())),
                List.of(7L));
        var addBranch =
                scope(List.of(new ReviewRepositoryTarget("owner/first", List.of("main", "develop"))), List.of(7L));
        var addPerson = scope(List.of(new ReviewRepositoryTarget("owner/first", List.of("main"))), List.of(7L, 8L));
        var narrow = scope(List.of(), List.of());

        assertThat(service.preview(workspace, addRepository, 12).widens()).isTrue();
        assertThat(service.preview(workspace, addBranch, 12).widens()).isTrue();
        assertThat(service.preview(workspace, addPerson, 12).widens()).isTrue();
        var narrowed = service.preview(workspace, narrow, 12);
        assertThat(narrowed.widens()).isFalse();
        assertThat(narrowed.proposed().coveredRepositories()).isZero();
        assertThat(narrowed.proposed().coveredPeople()).isZero();
    }

    private static WorkspaceReviewScope scope(List<ReviewRepositoryTarget> repositories, List<Long> people) {
        return new WorkspaceReviewScope(ReviewRepositoryMode.SELECTED, ReviewPersonMode.SELECTED, repositories, people);
    }

    private static RepositoryToMonitor monitor(long id, String name) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setId(id);
        monitor.setNameWithOwner(name);
        return monitor;
    }
}
