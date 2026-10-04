package de.tum.cit.aet.hephaestus.integration.outline.collection;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.outline.collection.OutlineCollectionAdminService.RegistrationOutcome;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Per-workspace Outline collection mirroring control plane — the admin surface that decides which
 * collections are mirrored ({@code register → ENABLED ⇄ PAUSED → deleted}), with a PATCH to the
 * target state driving a guarded, idempotent transition. Outline-side sharing ≠ Hephaestus consent,
 * so mirroring choices live in the webapp admin plane guarded by {@link RequireAtLeastWorkspaceAdmin}.
 *
 * <p>The path variable is the Outline collection UUID — the stable natural key
 * {@code (workspace_id, connection_id, collection_id)}. Every method scopes on the
 * {@link WorkspaceContext} workspace id, so another workspace's collection resolves to 404
 * (isolation), and every method 404s when the workspace has no ACTIVE Outline connection. Outline
 * API failures on the live-proxy paths surface as 502/503 {@code ProblemDetail} through
 * {@link OutlineCollectionControllerAdvice}; not-found / validation / auth flow through the shared
 * advice chain.
 */
@WorkspaceScopedController
@RequestMapping("/outline/collections")
@RequireAtLeastWorkspaceAdmin
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
@Validated
@Tag(name = "Outline collections", description = "Per-workspace Outline collection mirroring control plane")
public class OutlineCollectionAdminController {

    private final OutlineCollectionAdminService adminService;

    public OutlineCollectionAdminController(OutlineCollectionAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    @Operation(
            operationId = "listOutlineCollections",
            summary = "List the workspace's mirrored Outline collections with their sync state")
    @ApiResponse(responseCode = "200", description = "Mirrored collections returned")
    @ApiResponse(
            responseCode = "404",
            description = "The workspace has no ACTIVE Outline connection",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<List<OutlineCollectionDTO>> listOutlineCollections(WorkspaceContext workspace) {
        return ResponseEntity.ok(adminService.listCollections(workspace.id()));
    }

    @GetMapping("/{collectionId}")
    @Operation(
            operationId = "getOutlineCollection",
            summary = "One mirrored Outline collection with its sync state and live document count")
    @ApiResponse(responseCode = "200", description = "Mirrored collection returned")
    @ApiResponse(
            responseCode = "404",
            description =
                    "The collection is not registered for this workspace, or the workspace has no ACTIVE Outline connection",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<OutlineCollectionDTO> getOutlineCollection(
            WorkspaceContext workspace, @PathVariable String collectionId) {
        return ResponseEntity.ok(adminService.getCollection(workspace.id(), collectionId));
    }

    @GetMapping("/candidates")
    @Operation(
            operationId = "listOutlineCollectionCandidates",
            summary = "List the Outline collections available to mirror (live proxy; doubles as connectivity probe)",
            description = "Proxies Outline's collections.list with the stored token under a bounded interactive page "
                    + "budget. Served with Cache-Control: no-store — the live upstream view must not be cached.")
    @ApiResponse(responseCode = "200", description = "Candidate collections returned (Cache-Control: no-store)")
    @ApiResponse(
            responseCode = "404",
            description = "The workspace has no ACTIVE Outline connection",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "502",
            description = "The Outline server could not be reached or answered with an error",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "Outline limited the request rate. The Retry-After header carries the seconds to wait before retrying.",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<List<OutlineCollectionCandidateDTO>> listOutlineCollectionCandidates(
            WorkspaceContext workspace) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(adminService.listCandidates(workspace.id()));
    }

    @PostMapping
    @Operation(
            operationId = "registerOutlineCollection",
            summary = "Register an Outline collection for mirroring (lands ENABLED + PENDING; idempotent on the id)")
    @ApiResponse(
            responseCode = "201",
            description = "Collection registered; the Location header points at the collection resource")
    @ApiResponse(responseCode = "200", description = "Collection was already registered (idempotent repeat)")
    @ApiResponse(
            responseCode = "404",
            description = "The workspace has no ACTIVE Outline connection",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "A concurrent registration of the same collection won the race",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "422",
            description = "Outline does not know the requested collection id",
            content = @Content(schema = @Schema(hidden = true)))
    @AuditExempt(
            reason =
                    "which collections are mirrored is ingestion scope, not a control that gates delivery; not recorded")
    public ResponseEntity<OutlineCollectionDTO> registerOutlineCollection(
            WorkspaceContext workspace, @Valid @RequestBody RegisterOutlineCollectionRequestDTO request) {
        RegistrationOutcome outcome = adminService.register(workspace.id(), request.collectionId());
        if (!outcome.created()) {
            return ResponseEntity.ok(outcome.collection());
        }
        URI itemLocation = URI.create("/workspaces/" + workspace.slug() + "/outline/collections/"
                + outcome.collection().collectionId());
        return ResponseEntity.created(itemLocation).body(outcome.collection());
    }

    @PatchMapping("/{collectionId}")
    @Operation(
            operationId = "updateOutlineCollectionState",
            summary = "Transition a mirrored collection to a target mirror state (pause / resume)",
            description = "Resuming (PAUSED → ENABLED) resets the sync status to PENDING and kicks a targeted sync; "
                    + "requesting the current state is an idempotent no-op.")
    @ApiResponse(responseCode = "200", description = "Collection state after the transition returned")
    @ApiResponse(
            responseCode = "404",
            description =
                    "The collection is not registered for this workspace, or the workspace has no ACTIVE Outline connection",
            content = @Content(schema = @Schema(hidden = true)))
    @AuditExempt(
            reason =
                    "which collections are mirrored is ingestion scope, not a control that gates delivery; not recorded")
    public ResponseEntity<OutlineCollectionDTO> updateOutlineCollectionState(
            WorkspaceContext workspace,
            @PathVariable String collectionId,
            @Valid @RequestBody UpdateOutlineCollectionStateRequestDTO request) {
        return ResponseEntity.ok(adminService.updateState(workspace.id(), collectionId, request.state()));
    }

    @DeleteMapping("/{collectionId}")
    @Operation(
            operationId = "deleteOutlineCollection",
            summary = "Remove a collection from the mirror and erase its mirrored documents (terminal)")
    @ApiResponse(responseCode = "204", description = "Collection removed and its mirrored documents erased")
    @ApiResponse(
            responseCode = "404",
            description =
                    "The collection is not registered for this workspace, or the workspace has no ACTIVE Outline connection",
            content = @Content(schema = @Schema(hidden = true)))
    @AuditExempt(
            reason =
                    "which collections are mirrored is ingestion scope, not a control that gates delivery; not recorded")
    public ResponseEntity<Void> deleteOutlineCollection(WorkspaceContext workspace, @PathVariable String collectionId) {
        adminService.delete(workspace.id(), collectionId);
        return ResponseEntity.noContent().build();
    }
}
