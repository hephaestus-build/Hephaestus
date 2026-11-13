package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogLock;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogService;
import de.tum.cit.aet.hephaestus.practices.catalog.EffectiveCatalog;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeReleaseFieldDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeReleaseProposalDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@ConditionalOnServerRole
public class PracticeReleaseService {
    private final CatalogService catalog;
    private final PracticeReviewDigest digest;
    private final CatalogLock catalogLock;
    private final PracticeRepository practices;
    private final PracticeGroupRepository groupRepository;
    private final PracticeGroupService groups;
    private final PracticeVersionService versions;
    private final WorkspaceQueries workspaces;
    private final JsonMapper mapper;
    private final ConfigAuditPort audit;

    public List<PracticeReleaseProposalDTO> list(WorkspaceContext ctx) {
        var effective = catalog.effectiveCatalog();
        return practices.findAllForCatalog(ctx.id()).stream()
                .filter(practice -> practice.getCatalogSlug() != null)
                .filter(practice -> effective
                        .practice(Objects.requireNonNull(practice.getCatalogSlug()))
                        .map(offer -> !Objects.equals(practice.getCatalogBase(), offer.effective()))
                        .orElse(false))
                .map(practice -> proposal(practice, effective))
                .toList();
    }

    public PracticeReleaseProposalDTO get(WorkspaceContext ctx, String slug) {
        return proposal(practice(ctx, slug));
    }

    private PracticeReleaseProposalDTO proposal(Practice practice) {
        return proposal(practice, catalog.effectiveCatalog());
    }

    private PracticeReleaseProposalDTO proposal(Practice practice, EffectiveCatalog effective) {
        var source = practice.getCatalogSlug();
        if (source == null) throw new EntityNotFoundException("Practice release", practice.getSlug());
        var offer =
                effective.practice(source).orElseThrow(() -> new EntityNotFoundException("Catalog practice", source));
        var base = practice.getCatalogBase();
        var current = versions.definitionOf(practice.getCurrentVersion());
        String tag = tag(practice, offer.etag());
        return new PracticeReleaseProposalDTO(
                practice.getSlug(),
                Objects.requireNonNull(practice.getCurrentVersion().getId()).toString(),
                current,
                base == null ? null : base.definition(),
                offer.effective().definition(),
                digest.of(offer.effective().definition()),
                tag,
                PracticeDefinitionMerge.changes(
                                base == null ? null : base.definition(),
                                current,
                                offer.effective().definition())
                        .stream()
                        .map(change -> new PracticeReleaseFieldDTO(
                                change.field(), change.changedLocally(), change.offeredChanged(), change.conflict()))
                        .toList());
    }

    @Transactional
    public Practice accept(
            WorkspaceContext ctx,
            String slug,
            @Nullable EntityTagPrecondition precondition,
            Map<PracticeDefinitionField, PracticeReleaseChoice> choices) {
        lock(ctx);
        Practice practice = practice(ctx, slug);
        var proposal = proposal(practice);
        EntityTagPrecondition.requireExact(precondition, proposal.etag());
        var before = PracticeDefinitionSnapshot.of(practice, mapper);
        var merged = PracticeDefinitionMerge.apply(proposal.base(), proposal.current(), proposal.offered(), choices);
        versions.append(practice, merged, SecurityUtils.getCurrentAccountId().orElseThrow());
        practice.setCatalogBase(catalog.practice(Objects.requireNonNull(practice.getCatalogSlug()))
                .effective());
        practices.save(practice);
        auditPractice(ctx, practice, before);
        return practice;
    }

    @Transactional
    public void decline(WorkspaceContext ctx, String slug, @Nullable EntityTagPrecondition precondition) {
        lock(ctx);
        Practice practice = practice(ctx, slug);
        EntityTagPrecondition.requireExact(precondition, proposal(practice).etag());
        var before = PracticeDefinitionSnapshot.of(practice, mapper);
        practice.setCatalogBase(catalog.practice(Objects.requireNonNull(practice.getCatalogSlug()))
                .effective());
        practices.save(practice);
        auditPractice(ctx, practice, before);
    }

    @Transactional
    public Practice reset(WorkspaceContext ctx, String slug, @Nullable EntityTagPrecondition precondition) {
        lock(ctx);
        Practice practice = practice(ctx, slug);
        var proposal = proposal(practice);
        EntityTagPrecondition.requireExact(precondition, proposal.etag());
        var before = PracticeDefinitionSnapshot.of(practice, mapper);
        var document = catalog.practice(Objects.requireNonNull(practice.getCatalogSlug()))
                .effective();
        if (!practice.getKind().equals(document.kind()))
            throw new IllegalArgumentException("A catalog reset cannot change the practice's item kind.");
        groups.restoreCatalogBinding(ctx, practice, document.groupSlug());
        versions.append(
                practice,
                document.definition(),
                SecurityUtils.getCurrentAccountId().orElseThrow());
        practice.setCatalogBase(document);
        practices.save(practice);
        auditPractice(ctx, practice, before);
        return practice;
    }

    @Transactional
    public PracticeGroup resetGroup(WorkspaceContext ctx, String slug, @Nullable EntityTagPrecondition precondition) {
        lock(ctx);
        PracticeGroup group = groupRepository
                .findByWorkspaceIdAndSlug(ctx.id(), slug)
                .orElseThrow(() -> new EntityNotFoundException("Practice group", slug));
        if (group.getCatalogSlug() == null) throw new EntityNotFoundException("Catalog group", slug);
        var offer = catalog.group(group.getCatalogSlug());
        EntityTagPrecondition.requireExact(precondition, groupResetTag(ctx, slug));
        var before = PracticeGroupSnapshot.of(group);
        var definition = offer.effective();
        group.setName(definition.name());
        group.setDescription(definition.description());
        group.setIcon(definition.icon());
        group.setColor(definition.color());
        group.setCatalogBase(definition);
        groupRepository.save(group);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_GROUP,
                group.getId(),
                ctx.id(),
                before,
                PracticeGroupSnapshot.of(group)));
        return group;
    }

    public String groupResetTag(WorkspaceContext ctx, String slug) {
        var group = groups.getGroup(ctx, slug);
        var offer = group.getCatalogSlug() == null
                ? null
                : catalog.effectiveCatalog().group(group.getCatalogSlug()).orElse(null);
        return new CanonicalDigest()
                .add(group.getId().toString())
                .add(mapper.writeValueAsString(PracticeGroupSnapshot.of(group)))
                .addNullable(group.getCatalogBase() == null ? null : mapper.writeValueAsString(group.getCatalogBase()))
                .addNullable(offer == null ? null : offer.etag())
                .hex();
    }

    private void auditPractice(WorkspaceContext ctx, Practice practice, PracticeDefinitionSnapshot before) {
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_DEFINITION,
                practice.getId(),
                ctx.id(),
                before,
                PracticeDefinitionSnapshot.of(practice, mapper)));
    }

    private String tag(Practice practice, String offeredTag) {
        return new CanonicalDigest()
                .add(practice.getId().toString())
                .add(Objects.requireNonNull(practice.getCurrentVersion().getId())
                        .toString())
                .addNullable(
                        practice.getCatalogBase() == null ? null : mapper.writeValueAsString(practice.getCatalogBase()))
                .add(offeredTag)
                .hex();
    }

    private Practice practice(WorkspaceContext ctx, String slug) {
        return practices
                .findByWorkspaceIdAndSlug(ctx.id(), slug)
                .orElseThrow(() -> new EntityNotFoundException("Practice", slug));
    }

    private void lock(WorkspaceContext ctx) {
        catalogLock.acquire();
        if (workspaces.findByIdForUpdate(ctx.id()).isEmpty())
            throw new EntityNotFoundException("Workspace", ctx.slug());
    }
}
