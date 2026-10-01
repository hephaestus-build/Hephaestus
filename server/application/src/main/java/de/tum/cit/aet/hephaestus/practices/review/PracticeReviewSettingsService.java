package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewVolumeQuery;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewField;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewSettings;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes a workspace's practice-review policy overrides, resolving each knob against the
 * fleet default in {@link PracticeReviewProperties}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PracticeReviewSettingsService {

    private final WorkspaceRepository workspaceRepository;
    private final PracticeReviewProperties reviewProperties;
    private final ConfigAuditPort configAudit;
    private final PracticeReviewCoverageService coverageService;
    private final PracticeReviewVolumeQuery volumeQuery;

    public PracticeReviewSettingsDTO getSettings(WorkspaceContext workspaceContext) {
        return toView(requireWorkspace(workspaceContext));
    }

    public PracticeReviewCoveragePreviewDTO previewCoverage(
            WorkspaceContext workspaceContext, WorkspaceReviewScope proposed) {
        Workspace workspace = requireWorkspace(workspaceContext);
        return coverageService.preview(workspace, proposed, recentVolume(workspace));
    }

    @Transactional
    public PracticeReviewSettingsDTO updatePracticeReview(
            WorkspaceContext workspaceContext,
            UpdatePracticeReviewSettingsRequestDTO req,
            @Nullable EntityTagPrecondition precondition) {
        Workspace workspace = requireWorkspaceForUpdate(workspaceContext);
        PracticeReviewSettings settings = workspace.getReviewSettings();
        if (precondition == null) {
            throw new PracticeReviewPreconditionRequiredException();
        }
        if (!precondition.matches(Long.toString(settings.getConfigVersion()))) {
            throw new StalePracticeReviewSettingsException();
        }
        var beforeScope = coverageService.scope(workspace);
        PracticeReviewSnapshot before =
                PracticeReviewSnapshot.of(settings, beforeScope, coverageService.generatedPaths(workspace));
        // Reset-to-inherit first, then the value patch, so a field can be reset and re-set in one request.
        settings.reset(req.reset());
        settings.applyPatch(req.deliverToMerged(), req.cooldownMinutes());
        if (req.reset() != null && req.reset().contains(PracticeReviewField.REVIEW_SCOPE)) {
            coverageService.replace(workspace, WorkspaceReviewScope.ALL);
        }
        if (req.reviewScope() != null) {
            coverageService.replace(workspace, req.reviewScope());
        }
        if (req.generatedPaths() != null) {
            coverageService.patchGeneratedPaths(workspace, req.generatedPaths());
        }
        settings.applyRollout(null, null, req.deliveryStatus());
        settings.applyDefaultAutonomy(
                req.defaultAutonomy() == null ? null : req.defaultAutonomy().name());
        var afterScope = coverageService.scope(workspace);
        PracticeReviewSnapshot after =
                PracticeReviewSnapshot.of(settings, afterScope, coverageService.generatedPaths(workspace));
        if (!before.sameRolloutPolicyAs(after)) {
            settings.incrementRolloutRevision();
            after = PracticeReviewSnapshot.of(settings, afterScope, coverageService.generatedPaths(workspace));
        }
        settings.incrementConfigVersion();
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_REVIEW_SETTINGS,
                workspaceContext.id(),
                workspaceContext.id(),
                before,
                after));
        return toView(workspaceRepository.save(workspace));
    }

    private Workspace requireWorkspace(WorkspaceContext workspaceContext) {
        return workspaceRepository
                .findById(workspaceContext.id())
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceContext.slug()));
    }

    /**
     * Locking variant for the audited write: unserialized, two concurrent PATCHes snapshot the same
     * prior state and the audit trail records a transition the second write silently reverts.
     */
    private Workspace requireWorkspaceForUpdate(WorkspaceContext workspaceContext) {
        return workspaceRepository
                .findByIdForUpdate(workspaceContext.id())
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceContext.slug()));
    }

    private PracticeReviewSettingsDTO toView(Workspace workspace) {
        PracticeReviewSettings s = workspace.getReviewSettings();
        WorkspaceReviewDefaults defaults = WorkspaceReviewDefaults.of(s);
        int recentVolume = recentVolume(workspace);
        return new PracticeReviewSettingsDTO(
                EntityTagPrecondition.format(Long.toString(s.getConfigVersion())),
                s.getRolloutRevision(),
                s.resolveDeliverToMerged(reviewProperties.deliverToMerged()),
                s.resolveCooldownMinutes(reviewProperties.cooldownMinutes()),
                s.getDeliverToMerged(),
                s.getCooldownMinutes(),
                coverageService.scope(workspace),
                s.getDeliveryStatus(),
                coverageService.summary(workspace, recentVolume),
                defaults.defaultAutonomy(),
                s.getDefaultAutonomy() == null ? null : PracticeAutonomy.valueOf(s.getDefaultAutonomy()),
                coverageService.generatedPaths(workspace));
    }

    private int recentVolume(Workspace workspace) {
        return volumeQuery.countSince(
                workspace.getId(),
                Instant.now().minus(PracticeReviewCoverageService.ESTIMATE_WINDOW_DAYS, ChronoUnit.DAYS));
    }
}
