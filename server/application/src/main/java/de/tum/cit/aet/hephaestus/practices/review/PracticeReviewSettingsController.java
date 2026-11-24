package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.dto.WorkspaceReviewScopeDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

@ConditionalOnServerRole
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/practices/review-settings")
@Tag(name = "Practice Review Settings", description = "Workspace-scoped practice-review policy")
@RequiredArgsConstructor
@Validated
public class PracticeReviewSettingsController {

    private final PracticeReviewSettingsService practiceReviewSettingsService;

    @GetMapping
    @Operation(summary = "Get the workspace's practice-review policy")
    @ApiResponse(
            responseCode = "200",
            description = "Policy returned",
            content = @Content(schema = @Schema(implementation = PracticeReviewSettingsDTO.class)))
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<PracticeReviewSettingsDTO> getPracticeReviewSettings(WorkspaceContext workspaceContext) {
        return response(practiceReviewSettingsService.getSettings(workspaceContext));
    }

    @PostMapping("/scope-preview")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "computes a read-only coverage preview; stores no configuration")
    @Operation(operationId = "previewReviewScope")
    public PracticeReviewScopePreviewDTO previewCoverage(
            WorkspaceContext workspaceContext, @Valid @RequestBody WorkspaceReviewScopeDTO proposed) {
        return practiceReviewSettingsService.previewCoverage(workspaceContext, proposed);
    }

    @PatchMapping
    @Operation(summary = "Update the workspace's practice-review policy")
    @ApiResponse(
            responseCode = "200",
            description = "Policy updated",
            content = @Content(schema = @Schema(implementation = PracticeReviewSettingsDTO.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_REVIEW_SETTINGS")
    public ResponseEntity<PracticeReviewSettingsDTO> updatePracticeReviewSettings(
            WorkspaceContext workspaceContext,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody UpdatePracticeReviewSettingsRequestDTO request) {
        return response(practiceReviewSettingsService.updatePracticeReview(
                workspaceContext, request, ifMatch == null ? null : EntityTagPrecondition.parse(ifMatch)));
    }

    private static ResponseEntity<PracticeReviewSettingsDTO> response(PracticeReviewSettingsDTO settings) {
        return ResponseEntity.ok().eTag(settings.etag()).body(settings);
    }
}
