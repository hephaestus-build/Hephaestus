package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeGroupService {

    private static final Logger log = LoggerFactory.getLogger(PracticeGroupService.class);

    private final PracticeGroupRepository practiceGroupRepository;
    private final PracticeRepository practiceRepository;
    private final PracticeRevisionService practiceRevisionService;
    private final ConfigAuditPort configAudit;
    private final WorkspaceRepository workspaceRepository;

    @Transactional(readOnly = true)
    public List<PracticeGroup> listGroups(WorkspaceContext ctx, @Nullable Boolean visibleInPracticeDashboardsOnly) {
        return Boolean.TRUE.equals(visibleInPracticeDashboardsOnly)
                ? practiceGroupRepository
                        .findByWorkspaceIdAndVisibleInPracticeDashboardsTrueOrderByDisplayOrderAscNameAsc(ctx.id())
                : practiceGroupRepository.findByWorkspaceIdOrderByDisplayOrderAscNameAsc(ctx.id());
    }

    @Transactional
    public void reorder(WorkspaceContext ctx, List<String> orderedSlugs) {
        if (new HashSet<>(orderedSlugs).size() != orderedSlugs.size()) {
            throw new IllegalArgumentException("orderedSlugs must not contain duplicate slugs");
        }
        lockWorkspace(ctx);
        List<PracticeGroup> groups = practiceGroupRepository.findByWorkspaceIdOrderByDisplayOrderAscNameAsc(ctx.id());
        Set<String> existingSlugs = groups.stream().map(PracticeGroup::getSlug).collect(Collectors.toSet());
        Set<String> requestedSlugs = new HashSet<>(orderedSlugs);
        if (!existingSlugs.equals(requestedSlugs)) {
            String unknown = requestedSlugs.stream()
                    .filter(s -> !existingSlugs.contains(s))
                    .findFirst()
                    .orElse(null);
            if (unknown != null) {
                throw new EntityNotFoundException("PracticeGroup", unknown);
            }
            throw new IllegalArgumentException("orderedSlugs must contain every practice group in the workspace.");
        }
        Map<String, PracticeGroup> bySlug = groups.stream().collect(Collectors.toMap(PracticeGroup::getSlug, a -> a));
        int order = 0;
        for (String slug : orderedSlugs) {
            PracticeGroup group = Objects.requireNonNull(bySlug.get(slug));
            group.setDisplayOrder(order++);
            practiceGroupRepository.save(group);
        }
    }

    @Transactional(readOnly = true)
    public PracticeGroup getGroup(WorkspaceContext ctx, String slug) {
        return loadGroup(ctx, slug);
    }

    private PracticeGroup loadGroup(WorkspaceContext ctx, String slug) {
        return practiceGroupRepository
                .findByWorkspaceIdAndSlug(ctx.id(), slug)
                .orElseThrow(() -> new EntityNotFoundException("PracticeGroup", slug));
    }

    @Transactional
    public PracticeGroup createGroup(WorkspaceContext ctx, String slug, GroupAttributes attributes) {
        return createGroup(ctx, slug, attributes, true);
    }

    private PracticeGroup createGroup(
            WorkspaceContext ctx, String slug, GroupAttributes attributes, boolean recordAudit) {
        if (practiceGroupRepository.existsByWorkspaceIdAndSlug(ctx.id(), slug)) {
            throw new PracticeGroupSlugConflictException(
                    "A practice group with slug “" + slug + "” already exists in this workspace.");
        }
        Workspace workspace = lockWorkspace(ctx);

        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(workspace);
        group.setSlug(slug);
        group.setName(Objects.requireNonNull(attributes.name()));
        group.setDescription(attributes.description());
        group.setDisplayOrder(
                attributes.displayOrder() != null
                        ? attributes.displayOrder()
                        : practiceGroupRepository.findMaxDisplayOrder(ctx.id()) + 1);
        group.setIcon(attributes.icon());
        group.setColor(attributes.color());

        try {
            group = practiceGroupRepository.save(group);
        } catch (DataIntegrityViolationException ex) {
            if (!DataIntegrityViolationConstraints.hasName(ex, "uk_practice_group_workspace_slug")) {
                throw ex;
            }
            throw new PracticeGroupSlugConflictException(
                    "A practice group with slug “" + slug + "” already exists in this workspace.", ex);
        }
        if (recordAudit) {
            configAudit.record(ConfigAuditEntry.created(
                    ConfigAuditEntityType.PRACTICE_GROUP, group.getId(), ctx.id(), PracticeGroupSnapshot.of(group)));
        }
        log.info("Created practice group '{}' (slug={}) in workspace {}", group.getName(), group.getSlug(), ctx.slug());
        return group;
    }

    @Transactional
    public PracticeGroup createGroupFromCatalog(
            WorkspaceContext ctx, String slug, GroupDefinition definition, int displayOrder) {
        return createCatalogGroup(ctx, slug, definition, displayOrder);
    }

    private PracticeGroup createCatalogGroup(
            WorkspaceContext ctx, String slug, GroupDefinition definition, int displayOrder) {
        PracticeGroup group = createGroup(
                ctx,
                slug,
                new GroupAttributes(
                        definition.name(),
                        definition.description(),
                        displayOrder,
                        definition.icon(),
                        definition.color()),
                false);
        group.setSourceCuratedSlug(slug);
        group.setSourceCuratedFingerprint(definition.provenanceFingerprint(slug));
        return practiceGroupRepository.save(group);
    }

    @Transactional
    public PracticeGroup adoptGroupFromCatalog(
            WorkspaceContext ctx, String slug, GroupDefinition definition, int displayOrder) {
        PracticeGroup group = createCatalogGroup(ctx, slug, definition, displayOrder);
        configAudit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.PRACTICE_GROUP, group.getId(), ctx.id(), PracticeGroupSnapshot.of(group)));
        return group;
    }

    /**
     * Sets — or clears — the autonomy this group imposes on the practices under it that hold no autonomy of their own.
     *
     * <p>The level that makes the chain worth having. A group is the grain a team reasons in, so one write
     * here settles what would otherwise be one write per practice under it. Practices that set their own
     * autonomy are untouched: they disagreed on purpose, and a group-wide setting is not a reason to overrule
     * them.
     *
     * @param autonomy the autonomy to impose, or {@code null} to hold none and follow the workspace default
     */
    @Transactional
    public PracticeGroup setAutonomy(WorkspaceContext ctx, String slug, @Nullable PracticeAutonomy autonomy) {
        Workspace workspace = lockWorkspace(ctx);
        PracticeGroup group = loadGroup(ctx, slug);
        if (group.getAutonomy() == autonomy) {
            return group;
        }
        List<Practice> practices =
                practiceRepository.findByWorkspaceIdAndGroupIdOrderByDisplayOrderAscNameAsc(ctx.id(), group.getId());
        Map<Long, PracticeAutonomy> effectiveBefore = effectiveAutonomies(practices, workspace);
        PracticeGroupSnapshot before = PracticeGroupSnapshot.of(group);
        group.setAutonomy(autonomy);
        group = practiceGroupRepository.save(group);
        bumpRolloutRevisionIfChanged(workspace, effectiveBefore, practices);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_GROUP,
                group.getId(),
                ctx.id(),
                before,
                PracticeGroupSnapshot.of(group)));
        return group;
    }

    @Transactional
    public PracticeGroup updateGroup(WorkspaceContext ctx, String slug, GroupAttributes attributes) {
        return applyGroupUpdate(ctx, slug, attributes, null);
    }

    @Transactional
    public PracticeGroup updateGroup(
            WorkspaceContext ctx,
            String slug,
            GroupAttributes attributes,
            @Nullable Boolean visibleInPracticeDashboards) {
        return applyGroupUpdate(ctx, slug, attributes, visibleInPracticeDashboards);
    }

    private PracticeGroup applyGroupUpdate(
            WorkspaceContext ctx,
            String slug,
            GroupAttributes attributes,
            @Nullable Boolean visibleInPracticeDashboards) {
        lockWorkspace(ctx);
        PracticeGroup group = loadGroup(ctx, slug);
        PracticeGroupSnapshot before = PracticeGroupSnapshot.of(group);
        boolean snapshotChanged = (attributes.name() != null && !Objects.equals(attributes.name(), group.getName()))
                || (attributes.description() != null
                        && !Objects.equals(attributes.description(), group.getDescription()))
                || (attributes.icon() != null && !Objects.equals(attributes.icon(), group.getIcon()))
                || (attributes.color() != null && !Objects.equals(attributes.color(), group.getColor()));
        if (attributes.name() != null) {
            group.setName(attributes.name());
        }
        if (attributes.description() != null) {
            group.setDescription(attributes.description());
        }
        if (attributes.displayOrder() != null) {
            group.setDisplayOrder(attributes.displayOrder());
        }
        if (attributes.icon() != null) {
            group.setIcon(attributes.icon());
        }
        if (attributes.color() != null) {
            group.setColor(attributes.color());
        }
        if (visibleInPracticeDashboards != null) {
            group.setVisibleInPracticeDashboards(visibleInPracticeDashboards);
        }
        group = practiceGroupRepository.save(group);
        if (snapshotChanged) {
            for (Practice practice : practiceRepository.findByWorkspaceIdAndGroupIdOrderByDisplayOrderAscNameAsc(
                    ctx.id(), group.getId())) {
                practiceRevisionService.append(practice);
            }
        }
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_GROUP,
                group.getId(),
                ctx.id(),
                before,
                PracticeGroupSnapshot.of(group)));
        return group;
    }

    @Transactional
    public void deleteGroup(WorkspaceContext ctx, String slug) {
        removeGroup(ctx, slug, false);
    }

    @Transactional
    public void deleteGroup(WorkspaceContext ctx, String slug, boolean deletePractices) {
        removeGroup(ctx, slug, deletePractices);
    }

    private void removeGroup(WorkspaceContext ctx, String slug, boolean deletePractices) {
        Workspace workspace = lockWorkspace(ctx);
        PracticeGroup group = loadGroup(ctx, slug);
        PracticeGroupSnapshot groupBefore = PracticeGroupSnapshot.of(group);
        List<Practice> practices =
                practiceRepository.findByWorkspaceIdAndGroupIdOrderByDisplayOrderAscNameAsc(ctx.id(), group.getId());
        Map<Long, PracticeAutonomy> effectiveBefore = effectiveAutonomies(practices, workspace);
        int nextOrder = deletePractices ? 0 : practiceRepository.findMaxDisplayOrder(ctx.id(), null) + 1;
        for (Practice practice : practices) {
            PracticeDefinitionSnapshot before =
                    PracticeDefinitionSnapshot.of(practice, practiceRevisionService.currentRevisionNumber(practice));
            if (deletePractices) {
                practiceRepository.delete(practice);
                configAudit.record(ConfigAuditEntry.deleted(
                        ConfigAuditEntityType.PRACTICE_DEFINITION, practice.getId(), ctx.id(), before));
                continue;
            }
            practice.setGroup(null);
            practice.setDisplayOrder(nextOrder++);
            practiceRepository.save(practice);
            int revisionNumber = practiceRevisionService.append(practice).getRevisionNumber();
            recordPlacementChange(ctx, practice, before, revisionNumber);
        }
        practiceGroupRepository.delete(group);
        if (deletePractices && !practices.isEmpty()) {
            workspace.getReviewSettings().incrementRolloutRevision();
        } else if (!deletePractices) {
            bumpRolloutRevisionIfChanged(workspace, effectiveBefore, practices);
        }
        configAudit.record(
                ConfigAuditEntry.deleted(ConfigAuditEntityType.PRACTICE_GROUP, group.getId(), ctx.id(), groupBefore));
        log.info(
                "Deleted practice group (slug={}) with deletePractices={} in workspace {}",
                slug,
                deletePractices,
                ctx.slug());
    }

    @Transactional
    public Practice bindPractice(WorkspaceContext ctx, String practiceSlug, @Nullable String groupSlug) {
        Workspace workspace = lockWorkspace(ctx);
        Practice practice = practiceRepository
                .findByWorkspaceIdAndSlug(ctx.id(), practiceSlug)
                .orElseThrow(() -> new EntityNotFoundException("Practice", practiceSlug));

        PracticeDefinitionSnapshot before =
                PracticeDefinitionSnapshot.of(practice, practiceRevisionService.currentRevisionNumber(practice));
        PracticeAutonomy effectiveBefore = effectiveAutonomy(practice, workspace);
        if (!applyBinding(ctx, practice, groupSlug)) {
            return practice;
        }
        practice = practiceRepository.save(practice);
        if (effectiveBefore != effectiveAutonomy(practice, workspace)) {
            workspace.getReviewSettings().incrementRolloutRevision();
        }
        int revisionNumber = practiceRevisionService.append(practice).getRevisionNumber();
        recordPlacementChange(ctx, practice, before, revisionNumber);
        return practice;
    }

    boolean applyBinding(WorkspaceContext ctx, Practice practice, @Nullable String groupSlug) {
        PracticeGroup group = groupSlug == null
                ? null
                : practiceGroupRepository
                        .findByWorkspaceIdAndSlug(ctx.id(), groupSlug)
                        .orElseThrow(() -> new EntityNotFoundException("PracticeGroup", groupSlug));
        PracticeGroup currentGroup = practice.getGroup();
        if ((currentGroup == null && group == null)
                || (currentGroup != null && group != null && Objects.equals(currentGroup.getId(), group.getId()))) {
            return false;
        }

        int displayOrder = practiceRepository.findMaxDisplayOrder(ctx.id(), group == null ? null : group.getId()) + 1;
        practice.setGroup(group);
        practice.setDisplayOrder(displayOrder);
        return true;
    }

    private Workspace lockWorkspace(WorkspaceContext ctx) {
        return workspaceRepository
                .findByIdForUpdate(ctx.id())
                .orElseThrow(() -> new EntityNotFoundException("Workspace", ctx.slug()));
    }

    private static Map<Long, PracticeAutonomy> effectiveAutonomies(List<Practice> practices, Workspace workspace) {
        return practices.stream()
                .collect(Collectors.toMap(Practice::getId, practice -> effectiveAutonomy(practice, workspace)));
    }

    private static PracticeAutonomy effectiveAutonomy(Practice practice, Workspace workspace) {
        return AutonomyResolver.effectiveAutonomyOf(
                practice, WorkspaceReviewDefaults.of(workspace).defaultAutonomy());
    }

    private static void bumpRolloutRevisionIfChanged(
            Workspace workspace, Map<Long, PracticeAutonomy> before, List<Practice> practices) {
        boolean changed = practices.stream()
                .anyMatch(practice -> before.get(practice.getId()) != effectiveAutonomy(practice, workspace));
        if (changed) {
            workspace.getReviewSettings().incrementRolloutRevision();
        }
    }

    private void recordPlacementChange(
            WorkspaceContext ctx, Practice practice, PracticeDefinitionSnapshot before, int revisionNumber) {
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_DEFINITION,
                practice.getId(),
                ctx.id(),
                before,
                PracticeDefinitionSnapshot.of(practice, revisionNumber)));
    }
}
