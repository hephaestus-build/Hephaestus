package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * A workspace's model assignments: what model, with what limits, serves each {@link ModelPurpose}. There is
 * one assignment per purpose and data-handling tier; {@code PUT} is idempotent within that pair.
 */
@WorkspaceScopedController
@RequestMapping("/model-assignments")
@Tag(name = "Model assignments", description = "The model that serves each purpose in a workspace")
@RequiredArgsConstructor
@Validated
public class ModelAssignmentController {

    private final ModelAssignmentService modelAssignmentService;
    private final MemberAiRoutingAdapter routing;

    @GetMapping
    @Operation(summary = "List the workspace's model assignments")
    @ApiResponse(responseCode = "200", description = "Model assignments returned")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<List<ModelAssignmentDTO>> listModelAssignments(WorkspaceContext workspaceContext) {
        var served = routing.servedTiers(workspaceContext.id());
        List<ModelAssignmentDTO> bindings = modelAssignmentService.getBindings(workspaceContext).stream()
                .map(binding -> dto(binding, served))
                .toList();
        return ResponseEntity.ok(bindings);
    }

    @PutMapping("/{purpose}")
    @Operation(summary = "Assign a model to one purpose")
    @ApiResponse(
            responseCode = "200",
            description = "Model assignment saved",
            content = @Content(schema = @Schema(implementation = ModelAssignmentDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Model not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "The model is undeclared, or declared as another tier than this slot (problem type"
                    + " model-assignment-slot-mismatch, property declaredTier)",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "MODEL_ASSIGNMENT")
    public ResponseEntity<ModelAssignmentDTO> assignModel(
            WorkspaceContext workspaceContext,
            @PathVariable ModelPurpose purpose,
            @RequestParam(defaultValue = "UNDECLARED") DataHandlingTier dataHandlingTier,
            @Valid @RequestBody ModelAssignmentRequestDTO request) {
        WorkspaceModelAssignment binding =
                modelAssignmentService.upsertBinding(workspaceContext, purpose, dataHandlingTier, request);
        return ResponseEntity.ok(dto(binding, routing.servedTiers(workspaceContext.id())));
    }

    @RecentSignInExempt(reason = "a workspace admin can bind the model again")
    @DeleteMapping("/{purpose}")
    @Operation(summary = "Remove the model assignment of one purpose (turn it off)")
    @ApiResponse(responseCode = "204", description = "Model assignment removed")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "MODEL_ASSIGNMENT")
    public ResponseEntity<Void> removeModelAssignment(
            WorkspaceContext workspaceContext,
            @PathVariable ModelPurpose purpose,
            @RequestParam(defaultValue = "UNDECLARED") DataHandlingTier dataHandlingTier) {
        modelAssignmentService.deleteBinding(workspaceContext, purpose, dataHandlingTier);
        return ResponseEntity.noContent().build();
    }

    private ModelAssignmentDTO dto(
            WorkspaceModelAssignment binding, Map<ModelPurpose, Map<DataHandlingTier, List<DataHandlingTier>>> served) {
        return ModelAssignmentDTO.from(
                binding,
                modelAssignmentService.isReady(binding),
                served.getOrDefault(binding.getPurpose(), Map.of())
                        .getOrDefault(binding.getDataHandlingTier(), List.of()));
    }
}
