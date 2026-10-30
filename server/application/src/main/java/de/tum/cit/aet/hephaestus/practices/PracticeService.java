package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.feedbackmode.FeedbackModeResolver;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PracticeService {
    private final PracticeRepository practices;
    private final PracticeGroupRepository groups;
    private final WorkspaceRepository workspaces;
    private final PracticeVersionService versions;
    private final WorkspaceReviewDefaultsProvider defaults;
    private final ConfigAuditPort audit;
    private final JsonMapper mapper;

    public List<Practice> listPractices(WorkspaceContext ctx, @Nullable FeedbackMode feedbackMode) {
        var mode = defaults.rawDefaultFeedbackMode(ctx.id());
        return practices.findAllForCatalog(ctx.id()).stream()
                .filter(practice ->
                        feedbackMode == null || FeedbackModeResolver.effectiveModeOf(practice, mode) == feedbackMode)
                .toList();
    }

    public List<Practice> listReviewedPractices(WorkspaceContext ctx) {
        return practices.findAllForCatalog(ctx.id()).stream()
                .filter(practice -> versions.definitionOf(practice.getCurrentVersion())
                                .reviewedBy()
                                .by()
                        == ReviewedByKind.HEPHAESTUS)
                .toList();
    }

    public Practice getPractice(WorkspaceContext ctx, String slug) {
        return practices
                .findByWorkspaceIdAndSlug(ctx.id(), slug)
                .orElseThrow(() -> new EntityNotFoundException("Practice", slug));
    }

    @Transactional
    public Practice createPractice(WorkspaceContext ctx, @Valid CreatePracticeRequestDTO request) {
        return create(
                ctx,
                request.slug(),
                new ItemKind(request.kind()),
                request.definition().toDefinition(),
                request.groupSlug(),
                request.position(),
                null,
                SecurityUtils.getCurrentAccountId().orElseThrow(),
                null);
    }

    @Transactional
    public Practice createPracticeFromCatalog(WorkspaceContext ctx, String slug, CatalogPracticeDocument document) {
        return createFromCatalog(ctx, slug, document, null);
    }

    @Transactional
    public Practice adoptPracticeFromCatalog(
            WorkspaceContext ctx, String slug, CatalogPracticeDocument document, @Nullable FeedbackMode mode) {
        return createFromCatalog(ctx, slug, document, mode);
    }

    private Practice createFromCatalog(
            WorkspaceContext ctx, String slug, CatalogPracticeDocument document, @Nullable FeedbackMode mode) {
        Practice created = create(
                ctx,
                slug,
                document.kind(),
                document.definition(),
                document.groupSlug(),
                null,
                mode,
                SecurityUtils.getCurrentAccountId().orElse(null),
                document);
        return created;
    }

    private Practice create(
            WorkspaceContext ctx,
            String slug,
            ItemKind kind,
            PracticeDefinition definition,
            @Nullable String groupSlug,
            @Nullable Integer position,
            @Nullable FeedbackMode mode,
            @Nullable Long creator,
            @Nullable CatalogPracticeDocument catalogDocument) {
        Workspace workspace = lock(ctx);
        if (practices.existsByWorkspaceIdAndSlug(ctx.id(), slug))
            throw new PracticeSlugConflictException("This practice slug already exists.");
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.length() > 64)
            throw new IllegalArgumentException("Use a lowercase practice slug with single hyphens.");
        PracticeGroup group = group(ctx, groupSlug);
        Long groupId = group == null ? null : group.getId();
        int order = position == null ? practices.findMaxPosition(ctx.id(), groupId) + 1 : position;
        if (order < 0) throw new IllegalArgumentException("Use a nonnegative position.");
        Practice practice = versions.create(
                new PracticeVersionService.NewPractice(workspace, slug, kind, definition, groupId, order, creator));
        practice.setFeedbackMode(mode);
        if (catalogDocument != null) {
            practice.setCatalogSlug(slug);
            practice.setCatalogBase(catalogDocument);
        }
        practices.save(practice);
        audit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.PRACTICE_DEFINITION,
                practice.getId(),
                ctx.id(),
                PracticeDefinitionSnapshot.of(practice, mapper)));
        return practice;
    }

    @Transactional
    public Practice updatePractice(WorkspaceContext ctx, String slug, @Valid UpdatePracticeRequestDTO request) {
        lock(ctx);
        Practice practice = getPractice(ctx, slug);
        long expectedVersionId = Long.parseLong(request.expectedVersionId());
        if (expectedVersionId
                != Objects.requireNonNull(practice.getCurrentVersion().getId())) {
            throw new StalePracticeVersionException(
                    Objects.requireNonNull(practice.getCurrentVersion().getId()).toString());
        }
        var before = PracticeDefinitionSnapshot.of(practice, mapper);
        versions.append(
                practice,
                request.definition().toDefinition(),
                SecurityUtils.getCurrentAccountId().orElseThrow());
        record(ctx, practice, before);
        return practice;
    }

    @Transactional
    public Practice setFeedbackMode(WorkspaceContext ctx, String slug, @Nullable FeedbackMode mode) {
        lock(ctx);
        Practice practice = getPractice(ctx, slug);
        var before = new PracticeUsageSnapshot(practice.getFeedbackMode());
        practice.setFeedbackMode(mode);
        practices.save(practice);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_USAGE,
                practice.getId(),
                ctx.id(),
                before,
                new PracticeUsageSnapshot(practice.getFeedbackMode())));
        return practice;
    }

    @Transactional
    public List<Practice> movePractice(
            WorkspaceContext ctx, String slug, @Nullable String groupSlug, @PositiveOrZero int position) {
        lock(ctx);
        Practice practice = getPractice(ctx, slug);
        var before = PracticeDefinitionSnapshot.of(practice, mapper);
        PracticeGroup destination = group(ctx, groupSlug);
        Long oldGroup = practice.getGroup() == null ? null : practice.getGroup().getId();
        practice.setGroup(destination);
        List<Practice> peers = new ArrayList<>(practices.findAllForCatalog(ctx.id()).stream()
                .filter(peer -> !peer.getId().equals(practice.getId())
                        && Objects.equals(
                                peer.getGroup() == null ? null : peer.getGroup().getId(),
                                destination == null ? null : destination.getId()))
                .toList());
        peers.add(Math.min(position, peers.size()), practice);
        resequence(peers);
        if (!Objects.equals(oldGroup, destination == null ? null : destination.getId())) {
            resequence(practices.findAllForCatalog(ctx.id()).stream()
                    .filter(peer -> Objects.equals(
                            peer.getGroup() == null ? null : peer.getGroup().getId(), oldGroup))
                    .toList());
        }
        record(ctx, practice, before);
        return practices.findAllForCatalog(ctx.id());
    }

    private void resequence(List<Practice> rows) {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setPosition(i);
            practices.save(rows.get(i));
        }
    }

    @Transactional
    public void deletePractice(WorkspaceContext ctx, String slug) {
        lock(ctx);
        Practice practice = getPractice(ctx, slug);
        audit.record(ConfigAuditEntry.deleted(
                ConfigAuditEntityType.PRACTICE_DEFINITION,
                practice.getId(),
                ctx.id(),
                PracticeDefinitionSnapshot.of(practice, mapper)));
        try {
            practices.delete(practice);
            practices.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This practice has recorded review history and cannot be deleted.", exception);
        }
    }

    private @Nullable PracticeGroup group(WorkspaceContext ctx, @Nullable String slug) {
        return slug == null
                ? null
                : groups.findByWorkspaceIdAndSlug(ctx.id(), slug)
                        .orElseThrow(() -> new EntityNotFoundException("Practice group", slug));
    }

    private Workspace lock(WorkspaceContext ctx) {
        return workspaces
                .findByIdForUpdate(ctx.id())
                .orElseThrow(() -> new EntityNotFoundException("Workspace", ctx.slug()));
    }

    private void record(WorkspaceContext ctx, Practice practice, PracticeDefinitionSnapshot before) {
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_DEFINITION,
                practice.getId(),
                ctx.id(),
                before,
                PracticeDefinitionSnapshot.of(practice, mapper)));
    }
}
