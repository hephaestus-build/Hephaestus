package de.tum.cit.aet.hephaestus.practices.curated.adoption;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.exception.PreconditionFailedException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.PracticeSlugConflictException;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogLock;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnServerRole
public class CatalogAdoptionService {

    private final CatalogAdoptionPlanAssembler plans;
    private final CatalogLock catalogLock;
    private final WorkspaceQueries workspaceRepository;
    private final PracticeGroupService groupService;
    private final PracticeService practiceService;

    @Transactional(readOnly = true)
    public List<CatalogPracticeSummaryDTO> list(WorkspaceContext context) {
        return plans.list(context);
    }

    @Transactional(readOnly = true)
    public CatalogAdoptionPlan preview(WorkspaceContext context, String slug) {
        return plans.plan(context, slug);
    }

    @Transactional(readOnly = true)
    public CatalogGroupAdoptionPlan previewGroup(WorkspaceContext context, String slug) {
        return plans.groupPlan(context, slug);
    }

    @Transactional
    public Practice adopt(WorkspaceContext context, String slug, String ifMatch) {
        catalogLock.acquire();
        lockWorkspace(context);

        CatalogAdoptionPlan plan;
        try {
            plan = plans.plan(context, slug);
        } catch (EntityNotFoundException exception) {
            throw new PreconditionFailedException(exception);
        }
        EntityTagPrecondition.requireExact(EntityTagPrecondition.parse(ifMatch), plan.etag());
        if (plan.availability() != CatalogAdoptionAvailability.AVAILABLE) {
            throw new PracticeSlugConflictException(
                    "A practice with slug “" + slug + "” already exists in this workspace.");
        }
        if (plan.groupDisposition() == CatalogGroupDisposition.CREATE_CATALOG_GROUP) {
            groupService.adoptGroupFromCatalog(
                    context,
                    Objects.requireNonNull(plan.groupSlug()),
                    Objects.requireNonNull(plan.groupDefinition()),
                    plan.groupPosition());
        }
        return practiceService.adoptPracticeFromCatalog(context, slug, plan.document(), plan.initialFeedbackMode());
    }

    @Transactional
    public CatalogGroupAdoptionResult adoptGroup(WorkspaceContext context, String slug, String ifMatch) {
        catalogLock.acquire();
        lockWorkspace(context);

        CatalogGroupAdoptionPlan plan;
        try {
            plan = plans.groupPlan(context, slug);
        } catch (EntityNotFoundException exception) {
            throw new PreconditionFailedException(exception);
        }
        EntityTagPrecondition.requireExact(EntityTagPrecondition.parse(ifMatch), plan.etag());
        if (plan.disposition() == CatalogGroupDisposition.CREATE_CATALOG_GROUP) {
            groupService.adoptGroupFromCatalog(context, slug, plan.definition(), plan.position());
        }
        List<Practice> added = plan.practices().stream()
                .filter(practice -> practice.availability() == CatalogAdoptionAvailability.AVAILABLE)
                .map(practice -> practiceService.adoptPracticeFromCatalog(
                        context, practice.slug(), practice.document(), practice.initialFeedbackMode()))
                .toList();
        List<Practice> moved = new ArrayList<>();
        int position = 0;
        for (CatalogGroupPracticeActionDTO action : plan.actions()) {
            if (action.action() == CatalogGroupPracticeAction.MOVE_TO_GROUP) {
                practiceService.movePractice(context, action.slug(), slug, position++);
                moved.add(practiceService.getPractice(context, action.slug()));
            } else if (action.action() == CatalogGroupPracticeAction.ADD) {
                position++;
            }
        }
        return new CatalogGroupAdoptionResult(added, List.copyOf(moved));
    }

    record CatalogGroupAdoptionResult(List<Practice> added, List<Practice> moved) {}

    /** Serialises adoptions into one workspace; nothing here writes the workspace row itself. */
    private void lockWorkspace(WorkspaceContext context) {
        if (workspaceRepository.findByIdForUpdate(context.id()).isEmpty()) {
            throw new EntityNotFoundException("Workspace", context.slug());
        }
    }
}
