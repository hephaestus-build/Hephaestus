package de.tum.cit.aet.hephaestus.practices;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.curated.CuratedCatalogService;
import de.tum.cit.aet.hephaestus.practices.curated.EffectiveCatalog;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyRollupService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogOriginPresentationTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;

    private static final WorkspaceContext CTX =
            new WorkspaceContext(WORKSPACE_ID, "acme", "Acme", AccountType.ORG, null, false, false, Set.of());

    @Test
    void practiceBatchReadsTheCatalogOnce() {
        CuratedCatalogService service = mock(CuratedCatalogService.class);
        when(service.catalog()).thenReturn(new EffectiveCatalog(List.of(), List.of()));
        CatalogOriginPresenter presenter = new CatalogOriginPresenter(
                service,
                workspaceDefaults(),
                new de.tum.cit.aet.hephaestus.practices.review.AutomatedReviewFence(java.util.Map.of()));
        Practice first = mock(Practice.class);
        Practice second = mock(Practice.class);
        stubDefinition(first, "first");
        stubDefinition(second, "second");

        presenter.presentPractices(WORKSPACE_ID, List.of(first, second));

        verify(service).catalog();
    }

    /**
     * The autonomy in force is the bottom of a chain that ends at the workspace, so a hundred-row response
     * must not ask the workspace a hundred times either.
     */
    @Test
    void practiceBatchResolvesTheWorkspaceDefaultOnce() {
        CuratedCatalogService service = mock(CuratedCatalogService.class);
        when(service.catalog()).thenReturn(new EffectiveCatalog(List.of(), List.of()));
        WorkspaceReviewDefaultsProvider defaults = workspaceDefaults();
        CatalogOriginPresenter presenter = new CatalogOriginPresenter(
                service,
                defaults,
                new de.tum.cit.aet.hephaestus.practices.review.AutomatedReviewFence(java.util.Map.of()));
        Practice first = mock(Practice.class);
        Practice second = mock(Practice.class);
        stubDefinition(first, "first");
        stubDefinition(second, "second");

        presenter.presentPractices(WORKSPACE_ID, List.of(first, second));

        verify(defaults).forWorkspace(WORKSPACE_ID);
    }

    private static void stubDefinition(Practice practice, String slug) {
        when(practice.getSlug()).thenReturn(slug);
        when(practice.getName()).thenReturn(slug);
        when(practice.getArtifactKind()).thenReturn(ArtifactKinds.PULL_REQUEST);
        when(practice.getBindings()).thenReturn(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        when(practice.getCriteria()).thenReturn("Review the change");
        when(practice.getAutomatedReviewPolicy()).thenReturn(PracticeTestEvidence.pullRequest());
    }

    private static WorkspaceReviewDefaultsProvider workspaceDefaults() {
        WorkspaceReviewDefaultsProvider defaults = mock(WorkspaceReviewDefaultsProvider.class);
        when(defaults.forWorkspace(anyLong())).thenReturn(WorkspaceReviewDefaults.UNSET);
        return defaults;
    }

    @Test
    void groupBatchReadsTheCatalogOnce() {
        CuratedCatalogService service = mock(CuratedCatalogService.class);
        when(service.catalog()).thenReturn(new EffectiveCatalog(List.of(), List.of()));
        CatalogOriginPresenter presenter = new CatalogOriginPresenter(
                service,
                workspaceDefaults(),
                new de.tum.cit.aet.hephaestus.practices.review.AutomatedReviewFence(java.util.Map.of()));

        presenter.presentGroups(WORKSPACE_ID, List.of(mock(PracticeGroup.class), mock(PracticeGroup.class)));

        verify(service).catalog();
    }

    @Test
    void practiceListUsesOneCatalogSnapshotForTheWholeResponse() {
        PracticeService service = mock(PracticeService.class);
        CatalogOriginPresenter presenter = mock(CatalogOriginPresenter.class);
        List<Practice> practices = List.of(mock(Practice.class), mock(Practice.class));
        when(service.listPractices(CTX, null)).thenReturn(practices);
        PracticeCatalogController controller = new PracticeCatalogController(
                service,
                presenter,
                mock(AutonomyRollupService.class),
                mock(PracticeGroupService.class),
                mock(PracticeDefinitionOptionsService.class));

        controller.listPractices(CTX, null);

        verify(presenter).presentPractices(WORKSPACE_ID, practices);
        verify(presenter, never()).present(anyLong(), any(Practice.class));
    }

    @Test
    void groupListUsesOneCatalogSnapshotForTheWholeResponse() {
        PracticeGroupService service = mock(PracticeGroupService.class);
        CatalogOriginPresenter presenter = mock(CatalogOriginPresenter.class);
        List<PracticeGroup> groups = List.of(mock(PracticeGroup.class), mock(PracticeGroup.class));
        when(service.listGroups(CTX, null)).thenReturn(groups);
        PracticeGroupController controller = new PracticeGroupController(service, presenter);

        controller.listGroups(CTX, null);

        verify(presenter).presentGroups(WORKSPACE_ID, groups);
        verify(presenter, never()).present(anyLong(), any(PracticeGroup.class));
    }
}
