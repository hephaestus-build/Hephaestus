package de.tum.cit.aet.hephaestus.practices;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.curated.CuratedCatalogService;
import de.tum.cit.aet.hephaestus.practices.curated.EffectiveCatalog;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.review.AutomatedReviewFence;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogOriginPresentationTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;

    @Test
    void practiceBatchReadsTheCatalogOnce() {
        CuratedCatalogService service = mock(CuratedCatalogService.class);
        when(service.catalog()).thenReturn(new EffectiveCatalog(List.of(), List.of()));
        CatalogOriginPresenter presenter =
                new CatalogOriginPresenter(service, workspaceDefaults(), new AutomatedReviewFence(Map.of()));
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
        CatalogOriginPresenter presenter =
                new CatalogOriginPresenter(service, defaults, new AutomatedReviewFence(Map.of()));
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
        when(practice.getSignals()).thenReturn(PracticeTestEvidence.signals(ScmSignals.PULL_REQUEST_OPENED));
        when(practice.getEvidenceRequirements())
                .thenReturn(PracticeTestEvidence.needsFor(ScmSignals.PULL_REQUEST_OPENED.artifactKind()));
        when(practice.getSubject()).thenReturn(ActorRole.AUTHOR);
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
        CatalogOriginPresenter presenter =
                new CatalogOriginPresenter(service, workspaceDefaults(), new AutomatedReviewFence(Map.of()));

        presenter.presentGroups(WORKSPACE_ID, List.of(mock(PracticeGroup.class), mock(PracticeGroup.class)));

        verify(service).catalog();
    }
}
