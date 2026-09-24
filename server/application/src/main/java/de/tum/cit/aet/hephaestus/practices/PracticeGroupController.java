package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReorderPracticeGroupsRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeAutonomyRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@WorkspaceScopedController
@RequestMapping("/practice-groups")
@Tag(name = "Practice Groups", description = "Manage practice groups")
@RequiredArgsConstructor
@Validated
public class PracticeGroupController {

    private final PracticeGroupService groupService;
    private final CatalogOriginPresenter presenter;

    @GetMapping
    @Operation(summary = "List practice groups", description = "Returns the workspace's practice groups")
    @ApiResponse(
            responseCode = "200",
            description = "Groups returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = PracticeGroupDTO.class))))
    public ResponseEntity<List<PracticeGroupDTO>> listGroups(
            WorkspaceContext workspaceContext,
            @RequestParam(name = "visibleInPracticeDashboardsOnly", required = false)
                    @Parameter(description = "Return only groups shown in practice dashboards")
                    @Nullable
                    Boolean visibleInPracticeDashboardsOnly) {
        List<PracticeGroupDTO> groups = presenter.presentGroups(
                workspaceContext.id(), groupService.listGroups(workspaceContext, visibleInPracticeDashboardsOnly));
        return ResponseEntity.ok(groups);
    }

    @GetMapping("/{groupSlug}")
    @Operation(summary = "Get a practice group")
    @ApiResponse(
            responseCode = "200",
            description = "Group returned",
            content = @Content(schema = @Schema(implementation = PracticeGroupDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Group not found",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<PracticeGroupDTO> getGroup(
            WorkspaceContext workspaceContext, @PathVariable String groupSlug) {
        return ResponseEntity.ok(
                presenter.present(workspaceContext.id(), groupService.getGroup(workspaceContext, groupSlug)));
    }

    @PostMapping
    @Operation(summary = "Create a new practice group")
    @ApiResponse(
            responseCode = "201",
            description = "Group created",
            content = @Content(schema = @Schema(implementation = PracticeGroupDTO.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Group slug already exists in this workspace",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_GROUP")
    public ResponseEntity<PracticeGroupDTO> createGroup(
            WorkspaceContext workspaceContext, @Valid @RequestBody CreatePracticeGroupRequestDTO request) {
        PracticeGroup group = groupService.createGroup(
                workspaceContext,
                request.slug(),
                new GroupAttributes(
                        request.name(),
                        request.description(),
                        request.displayOrder(),
                        request.icon(),
                        request.color()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{slug}")
                .buildAndExpand(group.getSlug())
                .toUri();
        return ResponseEntity.created(location).body(presenter.present(workspaceContext.id(), group));
    }

    @PatchMapping("/{groupSlug}")
    @Operation(summary = "Update a practice group")
    @ApiResponse(
            responseCode = "200",
            description = "Group updated",
            content = @Content(schema = @Schema(implementation = PracticeGroupDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Group not found",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_GROUP")
    public ResponseEntity<PracticeGroupDTO> updateGroup(
            WorkspaceContext workspaceContext,
            @PathVariable String groupSlug,
            @Valid @RequestBody UpdatePracticeGroupRequestDTO request) {
        PracticeGroup group = groupService.updateGroup(
                workspaceContext,
                groupSlug,
                new GroupAttributes(
                        request.name(), request.description(), request.displayOrder(), request.icon(), request.color()),
                request.visibleInPracticeDashboards());
        return ResponseEntity.ok(presenter.present(workspaceContext.id(), group));
    }

    @PatchMapping("/{groupSlug}/autonomy")
    @Operation(
            summary = "Set how much autonomy the system has over one group",
            description = "Applies to every practice in the group that holds no autonomy of its own; practices that "
                    + "set their own are left alone. OFF stops their reviews entirely. HUMAN_APPROVAL runs them and records "
                    + "every observation, holds feedback on the work for an authorized reviewer, and still writes the "
                    + "developer's practice pages and the mentor, which are read on request. AUTOMATIC sends feedback on "
                    + "the work without asking. Send a null autonomy to clear the group's own setting so it follows the "
                    + "workspace default.")
    @ApiResponse(
            responseCode = "200",
            description = "Autonomy updated; the response carries the autonomy now in force and where it came from",
            content = @Content(schema = @Schema(implementation = PracticeGroupDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Group not found",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_GROUP")
    public ResponseEntity<PracticeGroupDTO> setGroupAutonomy(
            WorkspaceContext workspaceContext,
            @PathVariable String groupSlug,
            @Valid @RequestBody UpdatePracticeAutonomyRequestDTO request) {
        PracticeGroup group = groupService.setAutonomy(workspaceContext, groupSlug, request.autonomy());
        return ResponseEntity.ok(presenter.present(workspaceContext.id(), group));
    }

    @PatchMapping("/reorder")
    @Operation(
            summary = "Reorder practice groups",
            description = "Sets each group's display order to its index in the provided slug list (one atomic write)")
    @ApiResponse(
            responseCode = "200",
            description = "Groups reordered; the full ordered list is returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = PracticeGroupDTO.class))))
    @ApiResponse(
            responseCode = "400",
            description = "orderedSlugs is empty or contains duplicates",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "404",
            description = "A slug is unknown",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "catalog order affects presentation, not review execution or delivery")
    public ResponseEntity<List<PracticeGroupDTO>> reorderGroups(
            WorkspaceContext workspaceContext, @Valid @RequestBody ReorderPracticeGroupsRequestDTO request) {
        groupService.reorder(workspaceContext, request.orderedSlugs());
        List<PracticeGroupDTO> groups =
                presenter.presentGroups(workspaceContext.id(), groupService.listGroups(workspaceContext, null));
        return ResponseEntity.ok(groups);
    }

    @DeleteMapping("/{groupSlug}")
    @Operation(
            summary = "Delete a practice group",
            description =
                    "Deletes the group. By default its practices move to Unassigned; deletePractices=true deletes them too.")
    @ApiResponse(responseCode = "204", description = "Group deleted")
    @ApiResponse(
            responseCode = "404",
            description = "Group not found",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_GROUP")
    public ResponseEntity<Void> deleteGroup(
            WorkspaceContext workspaceContext,
            @PathVariable String groupSlug,
            @RequestParam(defaultValue = "false") boolean deletePractices) {
        groupService.deleteGroup(workspaceContext, groupSlug, deletePractices);
        return ResponseEntity.noContent().build();
    }
}
