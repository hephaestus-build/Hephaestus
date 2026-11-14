package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryRef;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewScopeRepositoryDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.WorkspaceReviewScopeDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspacePortFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceQueryService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceCommandsAdapter;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceQueriesAdapter;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryTarget;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeMode;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipReviewSelection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PracticeReviewCoverageServiceTest extends BaseUnitTest {
    final RepositoryToMonitorRepository monitors = mock(RepositoryToMonitorRepository.class);
    final ReviewScopeRepositoryRepository targets = mock(ReviewScopeRepositoryRepository.class);
    final MonitoredRepositoryQuery indexed = mock(MonitoredRepositoryQuery.class);
    final ProductItemRepository items = mock(ProductItemRepository.class);
    final PracticeReviewCoverageService service = new PracticeReviewCoverageService(
            new WorkspaceQueriesAdapter(
                    mock(WorkspaceRepository.class), monitors, targets, mock(WorkspaceQueryService.class)),
            new WorkspaceCommandsAdapter(mock(WorkspaceRepository.class), monitors, targets),
            items,
            indexed,
            mock(MembershipReviewSelection.class));
    Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(1L);
        lenient().when(monitors.findByWorkspaceId(1L)).thenReturn(List.of());
        lenient().when(targets.findByWorkspaceId(1L)).thenReturn(List.of());
    }

    @Test
    void emptySelectedScopeAdmitsNoRepositoryOrPerson() {
        var selected =
                new WorkspaceReviewScope(ReviewScopeMode.SELECTED, ReviewScopeMode.SELECTED, List.of(), List.of());
        assertThat(selected.admits(2L, "main", 1L)).isFalse();
    }

    @Test
    void branchRestrictionIsAppliedOnlyToBranchWork() {
        var selected = new WorkspaceReviewScope(
                ReviewScopeMode.SELECTED,
                ReviewScopeMode.ALL,
                List.of(new ReviewRepositoryTarget(2L, List.of("main"))),
                List.of());
        assertThat(selected.admitsRepository(2L, "feature", true)).isFalse();
        assertThat(selected.admitsRepository(2L, null, false)).isTrue();
    }

    @Test
    void generatedPathsRejectUnknownRepositoryWithoutChangingMonitors() {
        assertThatThrownBy(() -> service.patchGeneratedPaths(
                        WorkspacePortFixtures.reviewWorkspace(workspace), Map.of("3", List.of("dist/**"))))
                .isInstanceOf(InvalidReviewScopeException.class);
    }

    @Test
    void generatedPathsHaveAReset() {
        var monitor = new RepositoryToMonitor();
        monitor.setId(8L);
        monitor.setNameWithOwner("owner/repo");
        when(indexed.repositories(1)).thenReturn(Map.of(2L, new MonitoredRepositoryRef(2, 8, "owner/repo")));
        monitor.setGeneratedPaths(List.of("dist/**"));
        when(monitors.findByWorkspaceId(1L)).thenReturn(List.of(monitor));
        service.patchGeneratedPaths(WorkspacePortFixtures.reviewWorkspace(workspace), Map.of("2", List.of()));
        assertThat(monitor.getGeneratedPaths()).isEmpty();
    }

    @Test
    void wildcardPatternsAreNormalizedBeforePersistence() {
        var monitor = new RepositoryToMonitor();
        monitor.setNativeId(1L);
        monitor.setId(8L);
        monitor.setNameWithOwner("owner/repo");
        when(indexed.repositories(1)).thenReturn(Map.of(2L, new MonitoredRepositoryRef(2, 8, "owner/repo")));
        when(monitors.findByWorkspaceId(1L)).thenReturn(List.of(monitor));
        service.patchGeneratedPaths(
                WorkspacePortFixtures.reviewWorkspace(workspace), Map.of("2", List.of("dist/**", "dist/**")));
        assertThat(monitor.getGeneratedPaths()).containsExactly("dist/**");
    }

    @Test
    void equalProviderRepositoryNamesRemainDifferentNativeSelections() {
        when(indexed.repositories(1))
                .thenReturn(Map.of(
                        2L,
                        new MonitoredRepositoryRef(2, 8, "owner/repo"),
                        3L,
                        new MonitoredRepositoryRef(3, 9, "owner/repo")));
        var selection = service.fromWire(
                1,
                new WorkspaceReviewScopeDTO(
                        ReviewScopeMode.SELECTED,
                        ReviewScopeMode.ALL,
                        List.of(new ReviewScopeRepositoryDTO("3", List.of("main"))),
                        List.of()));
        assertThat(selection.repositories()).containsExactly(new ReviewRepositoryTarget(3, List.of("main")));
        assertThat(selection.admitsRepository(3L, "main", true)).isTrue();
        assertThat(selection.admitsRepository(2L, "main", true)).isFalse();
    }

    @Test
    void anOverflowingScopeIdIsInvalidInput() {
        assertThatThrownBy(() -> service.fromWire(
                        1,
                        new WorkspaceReviewScopeDTO(
                                ReviewScopeMode.SELECTED,
                                ReviewScopeMode.ALL,
                                List.of(new ReviewScopeRepositoryDTO("999999999999999999999999", List.of())),
                                List.of())))
                .isInstanceOf(InvalidReviewScopeException.class);
    }
}
