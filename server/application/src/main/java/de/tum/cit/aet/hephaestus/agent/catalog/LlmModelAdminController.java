package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Instance-admin management of the LLM catalog model list: CRUD plus pricing and sharing.
 *
 * <p>Deliberately no class-level {@code @WorkspaceAgnostic} — a controller-wide tenancy bypass would
 * excuse every statement any handler ever reaches; the cross-tenant repository queries carry it.
 */
@RestController
@RequestMapping("/admin/llm")
@Tag(name = "Admin LLM", description = "Instance-admin LLM connection and settings management")
@RecentSignInExempt(reason = "curates the model catalog and its prices; the credentials live on the connection")
@PreAuthorize("hasAuthority('app_admin')")
@ConditionalOnServerRole
@RequiredArgsConstructor
@Validated
public class LlmModelAdminController {

    private final LlmModelService modelService;

    @PostMapping("/connections/{connectionId}/models")
    @Operation(summary = "Create a model on an LLM connection", operationId = "adminCreateLlmModel")
    @ApiResponse(responseCode = "201", description = "Model created; URL in the Location header")
    @ApiResponse(
            responseCode = "404",
            description = "LLM connection not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "A model with this slug or upstream model id already exists on the connection",
            content = @Content(schema = @Schema(hidden = true)))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LLM_MODEL_CREATED")
    public ResponseEntity<LlmModelDTO> create(
            @PathVariable Long connectionId, @Valid @RequestBody CreateLlmModelRequestDTO request) {
        LlmModel created = modelService.create(connectionId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/admin/llm/models/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(toDTO(created));
    }

    @GetMapping("/models")
    @Operation(summary = "List LLM catalog models", operationId = "adminListLlmModels")
    public ResponseEntity<List<LlmModelDTO>> list() {
        List<LlmModel> models = modelService.list();
        List<Long> modelIds = models.stream().map(LlmModel::getId).toList();
        var currentPrices = modelService.currentPricesByModelId(modelIds);
        var grantedWorkspaceIds = modelService.grantedWorkspaceIdsByModelId(modelIds);
        return ResponseEntity.ok(models.stream()
                .map(model -> LlmModelDTO.from(
                        model,
                        currentPrices.get(model.getId()),
                        grantedWorkspaceIds.getOrDefault(model.getId(), List.of())))
                .toList());
    }

    @GetMapping("/models/{id}")
    @Operation(summary = "Get an LLM catalog model", operationId = "adminGetLlmModel")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(schema = @Schema(implementation = LlmModelDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "LLM model not found",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<LlmModelDTO> get(@PathVariable Long id) {
        return ResponseEntity.ok(toDTO(modelService.get(id)));
    }

    @PatchMapping("/models/{id}")
    @Operation(summary = "Update a model's metadata", operationId = "adminUpdateLlmModel")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(schema = @Schema(implementation = LlmModelDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "LLM model not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "Another model on the connection already uses this upstream model id",
            content = @Content(schema = @Schema(hidden = true)))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LLM_MODEL_UPDATED")
    public ResponseEntity<LlmModelDTO> update(
            @PathVariable Long id, @Valid @RequestBody UpdateLlmModelRequestDTO request) {
        return ResponseEntity.ok(toDTO(modelService.update(id, request)));
    }

    @DeleteMapping("/models/{id}")
    @Operation(summary = "Delete an LLM catalog model", operationId = "adminDeleteLlmModel")
    @ApiResponse(responseCode = "204", description = "Model deleted")
    @ApiResponse(
            responseCode = "404",
            description = "LLM model not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "Cannot delete a model still bound to an agent configuration",
            content = @Content(schema = @Schema(hidden = true)))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LLM_MODEL_DELETED")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        modelService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/models/{id}/price")
    @Operation(summary = "Reprice a model", operationId = "adminUpdateLlmModelPrice")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(schema = @Schema(implementation = LlmModelDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "LLM model not found",
            content = @Content(schema = @Schema(hidden = true)))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LLM_MODEL_PRICE_CHANGED")
    public ResponseEntity<LlmModelDTO> updatePrice(
            @PathVariable Long id, @Valid @RequestBody UpdateLlmModelPriceRequestDTO request) {
        modelService.updatePrice(id, request);
        return ResponseEntity.ok(toDTO(modelService.get(id)));
    }

    @PutMapping("/models/{id}/sharing")
    @Operation(summary = "Share a model with all or selected workspaces", operationId = "adminUpdateLlmModelSharing")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(schema = @Schema(implementation = LlmModelDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "LLM model not found",
            content = @Content(schema = @Schema(hidden = true)))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LLM_MODEL_SHARING_CHANGED")
    public ResponseEntity<LlmModelDTO> updateSharing(
            @PathVariable Long id, @Valid @RequestBody UpdateLlmModelSharingRequestDTO request) {
        return ResponseEntity.ok(toDTO(modelService.updateSharing(id, request)));
    }

    private LlmModelDTO toDTO(LlmModel model) {
        return LlmModelDTO.from(
                model, modelService.currentPrice(model.getId()), modelService.grantedWorkspaceIds(model.getId()));
    }
}
