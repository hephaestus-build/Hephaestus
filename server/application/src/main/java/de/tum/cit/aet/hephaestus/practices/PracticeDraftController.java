package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.dto.ChangePracticeStateRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDraftDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileCheckRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.PracticeState;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** Draft practices and versions to try: workspace admins only (ADR 0118 decisions 7 and 10). */
@ConditionalOnServerRole
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/practice-drafts")
@RequiredArgsConstructor
@RequireAtLeastWorkspaceAdmin
public class PracticeDraftController {
    private final PracticeDraftService drafts;

    @GetMapping
    @Operation(operationId = "listPracticeDrafts", summary = "List the draft practices")
    public List<PracticeDraftDTO> list(WorkspaceContext ctx) {
        return drafts.list(ctx);
    }

    @PutMapping("/{practiceSlug}")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(
            operationId = "savePracticeDraft",
            summary = "Save a practice folder to try",
            description = "A slug that the workspace lacks becomes a draft practice. A draft takes the folder as its"
                    + " next version. A practice in use keeps its current version, and the folder becomes a version"
                    + " beside it to try. Nobody is reviewed, and no model is called.")
    public PracticeDraftDTO save(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestBody @Valid PracticeFileCheckRequestDTO request) {
        return drafts.save(ctx, practiceSlug, request.files());
    }

    @PatchMapping("/{practiceSlug}/state")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(
            operationId = "activatePracticeDraft",
            summary = "Put a draft practice in use",
            description = "The practice starts in feedback mode Off.")
    @ApiResponse(responseCode = "204", description = "The practice is in use")
    public ResponseEntity<Void> activate(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestBody @Valid ChangePracticeStateRequestDTO request) {
        if (request.state() != PracticeState.ACTIVE)
            throw new InvalidInputException("A practice in use cannot become a draft again.");
        drafts.activate(ctx, practiceSlug);
        return ResponseEntity.noContent().build();
    }

    @RecentSignInExempt(reason = "a draft practice has no observation, so deleting it loses no history")
    @DeleteMapping("/{practiceSlug}")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "deletePracticeDraft", summary = "Delete a draft practice and its trials")
    @ApiResponse(responseCode = "204", description = "The draft practice is deleted")
    public ResponseEntity<Void> delete(WorkspaceContext ctx, @PathVariable String practiceSlug) {
        drafts.delete(ctx, practiceSlug);
        return ResponseEntity.noContent().build();
    }
}
