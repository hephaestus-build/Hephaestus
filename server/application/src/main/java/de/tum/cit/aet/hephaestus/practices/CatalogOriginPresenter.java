package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.practices.curated.CuratedCatalogService;
import de.tum.cit.aet.hephaestus.practices.curated.EffectiveCatalog;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.review.AutomatedReviewFence;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns practices and groups into their response shapes, adding the two things neither entity knows on its
 * own: where it came from in the instance catalogue, and what autonomy is actually in force for it.
 *
 * <p>Every method takes the workspace id because the autonomy reported is the <em>effective</em> one, resolved
 * once per response rather than once per row.
 */
@Component
@RequiredArgsConstructor
public class CatalogOriginPresenter {

    private final CuratedCatalogService catalogService;
    private final WorkspaceReviewDefaultsProvider workspaceDefaults;
    private final AutomatedReviewFence fence;

    public PracticeDTO present(Long workspaceId, Practice practice) {
        return PracticeDTO.from(
                practice,
                withdrawal(practice),
                CatalogOrigin.of(practice, catalogService.catalog()),
                defaultAutonomy(workspaceId));
    }

    public PracticeGroupDTO present(Long workspaceId, PracticeGroup group) {
        return PracticeGroupDTO.from(
                group, CatalogOrigin.of(group, catalogService.catalog()), defaultAutonomy(workspaceId));
    }

    public List<PracticeDTO> presentPractices(Long workspaceId, List<Practice> practices) {
        EffectiveCatalog catalog = catalogService.catalog();
        PracticeAutonomy workspaceDefault = defaultAutonomy(workspaceId);
        return practices.stream()
                .map(practice -> PracticeDTO.from(
                        practice, withdrawal(practice), CatalogOrigin.of(practice, catalog), workspaceDefault))
                .toList();
    }

    public List<PracticeGroupDTO> presentGroups(Long workspaceId, List<PracticeGroup> groups) {
        EffectiveCatalog catalog = catalogService.catalog();
        PracticeAutonomy workspaceDefault = defaultAutonomy(workspaceId);
        return groups.stream()
                .map(group -> PracticeGroupDTO.from(group, CatalogOrigin.of(group, catalog), workspaceDefault))
                .toList();
    }

    private @Nullable PracticeEvidenceLimitation withdrawal(Practice practice) {
        return fence.withdrawal(practice)
                .map(shipped -> shipped.automatedReviewPolicy().insufficiencyReason())
                .orElse(null);
    }

    private PracticeAutonomy defaultAutonomy(Long workspaceId) {
        return workspaceDefaults.forWorkspace(workspaceId).defaultAutonomy();
    }
}
