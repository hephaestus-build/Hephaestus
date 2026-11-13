package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PracticeGroupService {
    private final PracticeGroupRepository groups;
    private final PracticeRepository practices;
    private final WorkspaceQueries workspaces;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final ConfigAuditPort audit;
    private final JsonMapper mapper;

    public List<PracticeGroup> listGroups(WorkspaceContext ctx, @Nullable Boolean shownOnly) {
        return Boolean.TRUE.equals(shownOnly)
                ? groups.findByWorkspaceIdAndShownToDevelopersTrueOrderByPositionAscNameAsc(ctx.id())
                : groups.findByWorkspaceIdOrderByPositionAscNameAsc(ctx.id());
    }

    public PracticeGroup getGroup(WorkspaceContext ctx, String slug) {
        return groups.findByWorkspaceIdAndSlug(ctx.id(), slug)
                .orElseThrow(() -> new EntityNotFoundException("Practice group", slug));
    }

    @Transactional
    public PracticeGroup createGroup(
            WorkspaceContext ctx, String slug, GroupAttributes attributes, boolean shownToDevelopers) {
        return create(ctx, slug, attributes, shownToDevelopers, null);
    }

    private PracticeGroup create(
            WorkspaceContext ctx,
            String slug,
            GroupAttributes attributes,
            boolean shownToDevelopers,
            @Nullable GroupDefinition catalogDefinition) {
        lock(ctx);
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.length() > 64)
            throw new IllegalArgumentException("Use a lowercase group slug with single hyphens.");
        if (groups.existsByWorkspaceIdAndSlug(ctx.id(), slug))
            throw new PracticeGroupSlugConflictException("This practice group slug already exists.");
        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(Objects.requireNonNull(entityManager).getReference(Workspace.class, ctx.id()));
        group.setSlug(slug);
        group.setShownToDevelopers(shownToDevelopers);
        apply(group, attributes);
        group.setPosition(attributes.position() == null ? groups.findMaxPosition(ctx.id()) + 1 : attributes.position());
        if (catalogDefinition != null) {
            group.setCatalogSlug(slug);
            group.setCatalogBase(catalogDefinition);
        }
        groups.save(group);
        audit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.PRACTICE_GROUP, group.getId(), ctx.id(), PracticeGroupSnapshot.of(group)));
        return group;
    }

    @Transactional
    public PracticeGroup createGroupFromCatalog(
            WorkspaceContext ctx, String slug, GroupDefinition definition, int position) {
        return createCatalogGroup(ctx, slug, definition, position);
    }

    @Transactional
    public PracticeGroup adoptGroupFromCatalog(
            WorkspaceContext ctx, String slug, GroupDefinition definition, int position) {
        return createCatalogGroup(ctx, slug, definition, position);
    }

    private PracticeGroup createCatalogGroup(
            WorkspaceContext ctx, String slug, GroupDefinition definition, int position) {
        PracticeGroup group = create(
                ctx,
                slug,
                new GroupAttributes(
                        definition.name(), definition.description(), position, definition.icon(), definition.color()),
                true,
                definition);
        return group;
    }

    @Transactional
    public PracticeGroup updateGroup(
            WorkspaceContext ctx, String slug, GroupAttributes attributes, @Nullable Boolean shownToDevelopers) {
        lock(ctx);
        PracticeGroup group = getGroup(ctx, slug);
        var before = PracticeGroupSnapshot.of(group);
        apply(group, attributes);
        if (shownToDevelopers != null) group.setShownToDevelopers(shownToDevelopers);
        groups.save(group);
        record(ctx, group, before);
        return group;
    }

    @Transactional
    public PracticeGroup setFeedbackMode(WorkspaceContext ctx, String slug, @Nullable FeedbackMode mode) {
        lock(ctx);
        PracticeGroup group = getGroup(ctx, slug);
        var before = PracticeGroupSnapshot.of(group);
        group.setFeedbackMode(mode);
        groups.save(group);
        record(ctx, group, before);
        return group;
    }

    @Transactional
    public void reorder(WorkspaceContext ctx, List<String> slugs) {
        lock(ctx);
        List<PracticeGroup> all = listGroups(ctx, null);
        if (slugs.size() != new HashSet<>(slugs).size()
                || !new HashSet<>(slugs)
                        .equals(new HashSet<>(
                                all.stream().map(PracticeGroup::getSlug).toList()))) {
            throw new IllegalArgumentException("List every practice group once in the order.");
        }
        for (int i = 0; i < slugs.size(); i++) {
            PracticeGroup group = getGroup(ctx, slugs.get(i));
            group.setPosition(i);
            groups.save(group);
        }
    }

    @Transactional
    public Practice bindPractice(WorkspaceContext ctx, String practiceSlug, @Nullable String groupSlug) {
        lock(ctx);
        Practice practice = practices
                .findByWorkspaceIdAndSlug(ctx.id(), practiceSlug)
                .orElseThrow(() -> new EntityNotFoundException("Practice", practiceSlug));
        var before = PracticeDefinitionSnapshot.of(practice, mapper);
        if (changeBinding(ctx, practice, groupSlug)) {
            practices.save(practice);
            audit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.PRACTICE_DEFINITION,
                    practice.getId(),
                    ctx.id(),
                    before,
                    PracticeDefinitionSnapshot.of(practice, mapper)));
        }
        return practice;
    }

    @Transactional
    public void restoreCatalogBinding(WorkspaceContext ctx, Practice practice, @Nullable String groupSlug) {
        lock(ctx);
        if (changeBinding(ctx, practice, groupSlug)) practices.save(practice);
    }

    private boolean changeBinding(WorkspaceContext ctx, Practice practice, @Nullable String groupSlug) {
        PracticeGroup group = groupSlug == null ? null : getGroup(ctx, groupSlug);
        if (Objects.equals(
                practice.getGroup() == null ? null : practice.getGroup().getId(), group == null ? null : group.getId()))
            return false;
        int destinationPosition = practices.findMaxPosition(ctx.id(), group == null ? null : group.getId()) + 1;
        practice.setGroup(group);
        practice.setPosition(destinationPosition);
        return true;
    }

    @Transactional
    public void deleteGroup(WorkspaceContext ctx, String slug) {
        lock(ctx);
        PracticeGroup group = getGroup(ctx, slug);
        int position = practices.findMaxPosition(ctx.id(), null) + 1;
        for (Practice practice : practices.findByWorkspaceIdAndGroupIdOrderByPositionAsc(ctx.id(), group.getId())) {
            practice.setGroup(null);
            practice.setPosition(position++);
            practices.save(practice);
        }
        audit.record(ConfigAuditEntry.deleted(
                ConfigAuditEntityType.PRACTICE_GROUP, group.getId(), ctx.id(), PracticeGroupSnapshot.of(group)));
        groups.delete(group);
    }

    private static void apply(PracticeGroup group, GroupAttributes attributes) {
        new GroupDefinition(attributes.name(), attributes.description(), attributes.icon(), attributes.color());
        group.setName(attributes.name());
        group.setDescription(attributes.description());
        group.setIcon(attributes.icon());
        group.setColor(attributes.color());
        if (attributes.position() != null) {
            if (attributes.position() < 0) throw new IllegalArgumentException("Use a nonnegative position.");
            group.setPosition(attributes.position());
        }
    }

    private void lock(WorkspaceContext ctx) {
        if (workspaces.findByIdForUpdate(ctx.id()).isEmpty())
            throw new EntityNotFoundException("Workspace", ctx.slug());
    }

    private void record(WorkspaceContext ctx, PracticeGroup group, PracticeGroupSnapshot before) {
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_GROUP,
                group.getId(),
                ctx.id(),
                before,
                PracticeGroupSnapshot.of(group)));
    }
}
