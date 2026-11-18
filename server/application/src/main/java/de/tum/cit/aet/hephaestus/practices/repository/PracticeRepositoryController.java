package de.tum.cit.aet.hephaestus.practices.repository;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.dto.ChangePracticeEditedInRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeRepositoryDTO;
import de.tum.cit.aet.hephaestus.practices.dto.SetPracticeRepositoryRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** The workspace's practice repository, and where each practice is edited: workspace admins only (ADR 0118). */
@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/practice-repository")
@RequiredArgsConstructor
@RequireAtLeastWorkspaceAdmin
public class PracticeRepositoryController {
    private final PracticeRepositoryService repositories;

    @GetMapping
    @Operation(operationId = "getPracticeRepository", summary = "Read the practice repository")
    @ApiResponse(responseCode = "200", description = "The practice repository")
    @ApiResponse(responseCode = "204", description = "The workspace has no practice repository")
    public ResponseEntity<PracticeRepositoryDTO> get(WorkspaceContext ctx) {
        return repositories
                .get(ctx)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_REPOSITORY")
    @Operation(
            operationId = "setPracticeRepository",
            summary = "Set the practice repository",
            description = "Choosing another repository or directory moves every practice edited in the old one back"
                    + " to Hephaestus.")
    public PracticeRepositoryDTO set(
            WorkspaceContext ctx, @RequestBody @Valid SetPracticeRepositoryRequestDTO request) {
        return repositories.set(ctx, request);
    }

    @DeleteMapping
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_REPOSITORY")
    @Operation(
            operationId = "unsetPracticeRepository",
            summary = "Unset the practice repository",
            description = "Every practice edited there is edited in Hephaestus again. Nothing is deleted.")
    @ApiResponse(responseCode = "204", description = "The workspace has no practice repository")
    public ResponseEntity<Void> unset(WorkspaceContext ctx) {
        repositories.unset(ctx);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/practices/{practiceSlug}/edited-in")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(
            operationId = "changePracticeEditedIn",
            summary = "Move a practice to the practice repository, or edit it in Hephaestus",
            description =
                    "Moving to the repository reads the practice's folder at the head of the default branch, and"
                            + " refuses when the folder is missing or invalid. Editing in Hephaestus keeps the current version.")
    @ApiResponse(responseCode = "204", description = "The practice is edited where the request named")
    public ResponseEntity<Void> changeEditedIn(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestBody @Valid ChangePracticeEditedInRequestDTO request) {
        if (request.editedIn() == PracticeEditedIn.REPOSITORY) repositories.moveToRepository(ctx, practiceSlug);
        else repositories.moveToHephaestus(ctx, practiceSlug);
        return ResponseEntity.noContent().build();
    }
}
