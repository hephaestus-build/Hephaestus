package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.BearerTokenReplacement;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceFeaturesRequestDTO;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkspaceSettingsService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSettingsService.class);

    private final WorkspaceRepository workspaceRepository;
    private final ConfigAuditPort configAudit;
    private final ConnectionService connectionService;
    private final Clock clock;

    /**
     * Update the personal access token for a workspace. Rotates the bearer credential on
     * whichever SCM Connection (GitHub PAT or GitLab) is currently active — the caller
     * controls which workspace this hits via {@code workspaceId}, the kind is resolved
     * from the active Connection.
     */
    @Transactional
    public Workspace updateToken(Long workspaceId, String token) {
        Workspace workspace = requireWorkspace(workspaceId);
        IntegrationKind kind = connectionService
                .findActiveProviderKind(workspaceId)
                .filter(k -> k == IntegrationKind.GITHUB || k == IntegrationKind.GITLAB)
                .orElseThrow(() -> new IllegalStateException("Cannot rotate PAT for workspace " + workspaceId
                        + ": no active GitHub or GitLab Connection. Bind a provider first."));
        // The write says whether a token was there to replace; it never reads the old one, since this
        // is the way out for a token the server can no longer read, and it refuses a row whose mode
        // takes no token.
        BearerTokenReplacement rotation = connectionService
                .rotateBearerToken(workspaceId, kind, new BearerToken(token, null))
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot rotate PAT for workspace " + workspaceId + ": no active " + kind + " Connection."));
        boolean hadToken = rotation.replacedExisting();
        // rotatedAt is what makes this row exist at all: rotating an already-set token leaves every
        // other component identical, and ConfigAuditRecorder drops an UPDATE whose diff is empty — so
        // a presence-only snapshot would silently record nothing for the most sensitive change here.
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_TOKEN,
                workspaceId,
                workspaceId,
                new WorkspaceAuditSnapshots.TokenSnapshot(hadToken, kind.name(), null),
                new WorkspaceAuditSnapshots.TokenSnapshot(true, kind.name(), clock.instant())));
        log.info("Updated workspace PAT: workspaceId={}, kind={}", workspaceId, kind);
        return workspace;
    }

    /**
     * Update public visibility for a workspace.
     *
     * @param workspaceId the workspace ID
     * @param isPubliclyViewable whether the workspace is publicly viewable
     * @return the updated workspace
     */
    @Transactional
    public Workspace updatePublicVisibility(Long workspaceId, Boolean isPubliclyViewable) {
        Workspace workspace = requireWorkspace(workspaceId);
        var beforeVis = new WorkspaceAuditSnapshots.VisibilitySnapshot(workspace.getIsPubliclyViewable());
        workspace.setIsPubliclyViewable(isPubliclyViewable);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_VISIBILITY,
                workspaceId,
                workspaceId,
                beforeVis,
                new WorkspaceAuditSnapshots.VisibilitySnapshot(isPubliclyViewable)));
        log.info("Updated workspace visibility: workspaceId={}, isPublic={}", workspaceId, isPubliclyViewable);
        return workspaceRepository.save(workspace);
    }

    /**
     * Turn practice reviews and their triggers on or off.
     * Null fields in the request DTO are ignored (PATCH semantics).
     *
     * @param workspaceId the workspace ID
     * @param request the feature flags to update (null fields are left unchanged)
     * @return the updated workspace
     */
    @Transactional
    public Workspace updateFeatures(Long workspaceId, UpdateWorkspaceFeaturesRequestDTO request) {
        Workspace workspace = requireWorkspace(workspaceId);
        var before = WorkspaceAuditSnapshots.FeaturesSnapshot.of(workspace.getFeatures());
        workspace.getFeatures().applyPatch(request);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_FEATURES,
                workspaceId,
                workspaceId,
                before,
                WorkspaceAuditSnapshots.FeaturesSnapshot.of(workspace.getFeatures())));

        log.info(
                "Updated workspace features: workspaceId={}, practices={}, autoTrigger={}, manualTrigger={}",
                workspaceId,
                request.practicesEnabled(),
                request.practiceReviewAutoTriggerEnabled(),
                request.practiceReviewManualTriggerEnabled());
        return workspaceRepository.save(workspace);
    }

    private Workspace requireWorkspace(Long workspaceId) {
        return workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));
    }
}
