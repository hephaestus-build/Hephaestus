package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Instance-admin management of the pools of provider capacity: the limit that every workspace's calls to the models
 * in a pool share, and which models are in it.
 */
@RestController
@RequestMapping("/admin/llm/call-pools")
@Tag(name = "Admin LLM", description = "Instance-admin LLM connection and settings management")
@RecentSignInExempt(reason = "limits model calls; no credential is read or changed")
@RequireInstanceAdmin
@ConditionalOnServerRole
@RequiredArgsConstructor
@Validated
public class LlmCallPoolAdminController {

    private final LlmCallPoolService pools;

    @GetMapping
    @Operation(summary = "List the pools of provider capacity", operationId = "adminListLlmCallPools")
    public ResponseEntity<List<LlmCallPoolDTO>> list() {
        return ResponseEntity.ok(pools.list().stream().map(LlmCallPoolDTO::from).toList());
    }

    @PostMapping
    @Operation(summary = "Create a pool of provider capacity", operationId = "adminCreateLlmCallPool")
    @ApiResponse(responseCode = "201", description = "Pool created; URL in the Location header")
    @ApiResponse(
            responseCode = "409",
            description = "A pool with this slug already exists",
            content = @Content(schema = @Schema(hidden = true)))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LLM_SETTINGS_CHANGED")
    public ResponseEntity<LlmCallPoolDTO> create(@Valid @RequestBody SaveLlmCallPoolRequestDTO request) {
        LlmCallPool created = pools.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/admin/llm/call-pools/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(LlmCallPoolDTO.from(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change a pool's name and limits", operationId = "adminUpdateLlmCallPool")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(schema = @Schema(implementation = LlmCallPoolDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Pool not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "The pool still has calls, rate history, or a cooldown",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "400",
            description = "The pool slug cannot change or the limits are invalid",
            content = @Content(schema = @Schema(hidden = true)))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LLM_SETTINGS_CHANGED")
    public ResponseEntity<LlmCallPoolDTO> update(
            @PathVariable Long id, @Valid @RequestBody SaveLlmCallPoolRequestDTO request) {
        return ResponseEntity.ok(LlmCallPoolDTO.from(pools.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a pool; its models are no longer limited", operationId = "adminDeleteLlmCallPool")
    @ApiResponse(responseCode = "204", description = "Pool deleted")
    @ApiResponse(
            responseCode = "404",
            description = "Pool not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "The pool still has calls, rate history, or a cooldown",
            content = @Content(schema = @Schema(hidden = true)))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LLM_SETTINGS_CHANGED")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        pools.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/models")
    @Operation(
            summary = "List every model that can join a pool, with the pool it is in",
            operationId = "adminListLlmCallPoolModels")
    public ResponseEntity<List<LlmCallPoolModelDTO>> models() {
        return ResponseEntity.ok(pools.models());
    }

    @PutMapping("/{id}/models")
    @Operation(summary = "Replace the models in a pool", operationId = "adminUpdateLlmCallPoolModels")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = LlmCallPoolModelDTO.class))))
    @ApiResponse(
            responseCode = "404",
            description = "Pool or model not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "The pool still has calls, rate history, or a cooldown",
            content = @Content(schema = @Schema(hidden = true)))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LLM_SETTINGS_CHANGED")
    public ResponseEntity<List<LlmCallPoolModelDTO>> replaceModels(
            @PathVariable Long id, @Valid @RequestBody UpdateLlmCallPoolModelsRequestDTO request) {
        pools.replaceModels(id, request);
        return ResponseEntity.ok(pools.models());
    }
}
