package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.curated.CatalogEntry;
import de.tum.cit.aet.hephaestus.practices.curated.CuratedCatalogService;
import de.tum.cit.aet.hephaestus.practices.curated.EffectiveCatalog;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeReleaseProposalDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeReleaseService {

    private final PracticeRepository practices;
    private final WorkspaceRepository workspaces;
    private final CuratedCatalogService catalogService;
    private final PracticeGroupService groups;
    private final PracticeDefinitionValidator validator;
    private final PracticeRevisionService revisions;
    private final WorkspaceReviewDefaultsProvider workspaceDefaults;
    private final ConfigAuditPort audit;

    @Transactional(readOnly = true)
    public List<PracticeReleaseProposalDTO> list(WorkspaceContext ctx) {
        EffectiveCatalog catalog = catalogService.catalog();
        return practices.findAllForCatalog(ctx.id()).stream()
                .map(practice -> proposal(practice, catalog))
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public PracticeReleaseProposalDTO get(WorkspaceContext ctx, String slug) {
        Practice practice = practice(ctx, slug);
        return requireProposal(practice, catalogService.catalog());
    }

    @Transactional
    public Practice accept(
            WorkspaceContext ctx,
            String slug,
            @Nullable EntityTagPrecondition precondition,
            Map<PracticeDefinitionField, PracticeReleaseChoice> choices) {
        Workspace workspace = lockWorkspace(ctx);
        Practice practice = practice(ctx, slug);
        PracticeReleaseProposalDTO proposal = requireProposal(practice, catalogService.catalogForDecision());
        PracticeReleasePrecondition.requireCurrent(precondition, proposal);
        PracticeDefinition merged =
                PracticeDefinitionMerge.apply(proposal.base(), proposal.current(), proposal.offered(), choices);
        validator.validate(merged);

        Integer oldRevision = revisions.currentRevisionNumber(practice);
        PracticeDefinitionSnapshot before = PracticeDefinitionSnapshot.of(practice, oldRevision);
        PracticeAutonomy autonomyBefore = practice.getAutonomy();
        PracticeAutonomy effectiveBefore = effectiveAutonomy(practice, ctx.id());
        if (!Objects.equals(proposal.current().groupSlug(), merged.groupSlug())) {
            groups.applyBinding(ctx, practice, merged.groupSlug());
        }
        PracticeService.applyDefinition(practice, merged);
        if (!merged.automatedReviewPolicy().automatedReview().canAttemptAutomatedReview()) {
            practice.setAutonomy(PracticeAutonomy.OFF);
        }
        practice.setAdoptedBase(proposal.offered());
        practice.setAdoptedBaseSource(AdoptedBaseSource.EXACT_ADOPTION);
        practice.setSourceCuratedFingerprint(proposal.offered().provenanceFingerprint(slug));
        practice.setDeclinedOfferedDigest(null);
        practices.save(practice);
        int revision = revisions.append(practice).getRevisionNumber();
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_DEFINITION,
                practice.getId(),
                ctx.id(),
                before,
                PracticeDefinitionSnapshot.of(practice, revision)));
        if (autonomyBefore != practice.getAutonomy()) {
            audit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.PRACTICE_USAGE,
                    practice.getId(),
                    ctx.id(),
                    new PracticeUsageSnapshot(autonomyBefore),
                    new PracticeUsageSnapshot(practice.getAutonomy())));
        }
        if (effectiveBefore != effectiveAutonomy(practice, ctx.id())) {
            workspace.getReviewSettings().incrementRolloutRevision();
        }
        return practice;
    }

    @Transactional
    public void decline(WorkspaceContext ctx, String slug, @Nullable EntityTagPrecondition precondition) {
        lockWorkspace(ctx);
        Practice practice = practice(ctx, slug);
        PracticeReleaseProposalDTO proposal = requireProposal(practice, catalogService.catalogForDecision());
        PracticeReleasePrecondition.requireCurrent(precondition, proposal);
        PracticeDefinitionSnapshot before =
                PracticeDefinitionSnapshot.of(practice, revisions.currentRevisionNumber(practice));
        practice.setDeclinedOfferedDigest(proposal.offeredDigest());
        practices.save(practice);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_DEFINITION,
                practice.getId(),
                ctx.id(),
                before,
                PracticeDefinitionSnapshot.of(practice, revisions.currentRevisionNumber(practice))));
    }

    private Practice practice(WorkspaceContext ctx, String slug) {
        return practices
                .findByWorkspaceIdAndSlug(ctx.id(), slug)
                .orElseThrow(() -> new EntityNotFoundException("Practice", slug));
    }

    private Workspace lockWorkspace(WorkspaceContext ctx) {
        return workspaces
                .findByIdForUpdate(ctx.id())
                .orElseThrow(() -> new EntityNotFoundException("Workspace", ctx.slug()));
    }

    private PracticeAutonomy effectiveAutonomy(Practice practice, Long workspaceId) {
        return AutonomyResolver.effectiveAutonomyOf(
                practice, workspaceDefaults.forWorkspace(workspaceId).defaultAutonomy());
    }

    private PracticeReleaseProposalDTO requireProposal(Practice practice, EffectiveCatalog catalog) {
        PracticeReleaseProposalDTO proposal = proposal(practice, catalog);
        if (proposal == null) {
            throw new EntityNotFoundException("Practice release", practice.getSlug());
        }
        return proposal;
    }

    private static @Nullable PracticeReleaseProposalDTO proposal(Practice practice, EffectiveCatalog catalog) {
        String sourceSlug = practice.getSourceCuratedSlug();
        PracticeDefinition base = practice.getAdoptedBase();
        if (sourceSlug == null || base == null) {
            return null;
        }
        CatalogEntry<PracticeDefinition> offer = catalog.practice(sourceSlug).orElse(null);
        if (offer == null || base.equals(offer.effective())) {
            return null;
        }
        String offeredDigest = offer.effective().exactFingerprint(sourceSlug);
        if (offeredDigest.equals(practice.getDeclinedOfferedDigest())) {
            return null;
        }
        return PracticeReleaseProposalDTO.of(
                sourceSlug,
                base,
                PracticeDefinition.from(practice),
                offer.effective(),
                Objects.requireNonNull(practice.getAdoptedBaseSource()),
                practice.getCurrentRevision() == null
                        ? null
                        : practice.getCurrentRevision().getRevisionNumber());
    }
}
