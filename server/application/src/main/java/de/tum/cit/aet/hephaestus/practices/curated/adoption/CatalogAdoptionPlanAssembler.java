package de.tum.cit.aet.hephaestus.practices.curated.adoption;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDrafts;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnServerRole
class CatalogAdoptionPlanAssembler {
    private final CatalogService catalog;
    private final PracticeDrafts drafts;
    private final PracticeGroupRepository groups;

    List<CatalogPracticeSummaryDTO> list(WorkspaceContext ctx) {
        return catalog.effectiveCatalog().installablePractices().stream()
                .map(offer -> {
                    var document = offer.effective();
                    var definition = document.definition();
                    String group = document.groupSlug();
                    return new CatalogPracticeSummaryDTO(
                            offer.slug(),
                            definition.name(),
                            document.kind().value(),
                            definition.guidance().whyItMatters(),
                            group,
                            group == null
                                    ? null
                                    : catalog.group(group).effective().name(),
                            availability(
                                    offer.slug(),
                                    drafts.find(ctx.id(), offer.slug()).orElse(null)),
                            definition.reviewedBy());
                })
                .toList();
    }

    CatalogAdoptionPlan plan(WorkspaceContext ctx, String slug) {
        var offer = catalog.effectiveCatalog().installablePractices().stream()
                .filter(entry -> entry.slug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Offered catalog practice", slug));
        // A draft practice holds its slug too, so adopting the catalog practice would conflict with it.
        var existing = drafts.find(ctx.id(), slug).orElse(null);
        String groupSlug = offer.effective().groupSlug();
        var group = groupSlug == null
                ? null
                : groups.findByWorkspaceIdAndSlug(ctx.id(), groupSlug).orElse(null);
        var disposition = groupSlug == null
                ? CatalogGroupDisposition.UNASSIGNED
                : group == null
                        ? CatalogGroupDisposition.CREATE_CATALOG_GROUP
                        : CatalogGroupDisposition.REUSE_EXISTING_GROUP;
        GroupDefinition definition = groupSlug == null
                ? null
                : group == null
                        ? catalog.group(groupSlug).effective()
                        : new GroupDefinition(
                                group.getName(), group.getDescription(), group.getIcon(), group.getColor());
        return CatalogAdoptionPlan.create(
                slug,
                offer.effective(),
                availability(slug, existing),
                disposition,
                groupSlug,
                definition,
                group == null ? groups.findMaxPosition(ctx.id()) + 1 : group.getPosition(),
                offer.etag());
    }

    CatalogGroupAdoptionPlan groupPlan(WorkspaceContext ctx, String slug) {
        var offer = catalog.group(slug);
        if (!offer.offered()) throw new EntityNotFoundException("Offered catalog group", slug);
        var existing = groups.findByWorkspaceIdAndSlug(ctx.id(), slug).orElse(null);
        GroupDefinition definition = existing == null
                ? offer.effective()
                : new GroupDefinition(
                        existing.getName(), existing.getDescription(), existing.getIcon(), existing.getColor());
        List<CatalogAdoptionPlan> plans = catalog.effectiveCatalog().installablePractices().stream()
                .filter(practice -> slug.equals(practice.effective().groupSlug()))
                .map(practice -> plan(ctx, practice.slug()))
                .toList();
        var actions = plans.stream()
                .map(plan -> new CatalogGroupPracticeActionDTO(plan.slug(), action(ctx, slug, plan)))
                .toList();
        return CatalogGroupAdoptionPlan.create(
                slug,
                definition,
                existing == null
                        ? CatalogGroupDisposition.CREATE_CATALOG_GROUP
                        : CatalogGroupDisposition.REUSE_EXISTING_GROUP,
                existing == null ? groups.findMaxPosition(ctx.id()) + 1 : existing.getPosition(),
                plans,
                actions);
    }

    private CatalogGroupPracticeAction action(WorkspaceContext ctx, String slug, CatalogAdoptionPlan plan) {
        if (plan.availability() == CatalogAdoptionAvailability.AVAILABLE) return CatalogGroupPracticeAction.ADD;
        if (plan.availability() == CatalogAdoptionAvailability.SLUG_CONFLICT) return CatalogGroupPracticeAction.BLOCKED;
        Practice existing = drafts.find(ctx.id(), plan.slug()).orElseThrow();
        // The folder in the practice repository owns the group of a practice edited there.
        if (existing.getEditedIn() == PracticeEditedIn.REPOSITORY
                && (existing.getGroup() == null || !slug.equals(existing.getGroup().getSlug())))
            return CatalogGroupPracticeAction.BLOCKED;
        return existing.getGroup() == null
                ? CatalogGroupPracticeAction.MOVE_TO_GROUP
                : slug.equals(existing.getGroup().getSlug())
                        ? CatalogGroupPracticeAction.KEEP
                        : CatalogGroupPracticeAction.BLOCKED;
    }

    private static CatalogAdoptionAvailability availability(String slug, @Nullable Practice practice) {
        return practice == null
                ? CatalogAdoptionAvailability.AVAILABLE
                : slug.equals(practice.getCatalogSlug())
                        ? CatalogAdoptionAvailability.ADOPTED
                        : CatalogAdoptionAvailability.SLUG_CONFLICT;
    }
}
