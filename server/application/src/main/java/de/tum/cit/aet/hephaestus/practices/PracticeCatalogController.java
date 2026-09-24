package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.practices.dto.AutonomyRollupDTO;
import de.tum.cit.aet.hephaestus.practices.dto.BindPracticeGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PlacePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDefinitionOptionsDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReorderPracticesRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReviewedPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeAutonomyRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyRollupService;
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
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@WorkspaceScopedController
@RequestMapping("/practices")
@Tag(name = "Practice Catalog", description = "Manage practice definitions")
@RequiredArgsConstructor
@Validated
public class PracticeCatalogController {

    private final PracticeService practiceService;
    private final CatalogOriginPresenter presenter;
    private final AutonomyRollupService rollupService;
    private final PracticeGroupService groupService;
    private final PracticeDefinitionOptionsService definitionOptionsService;

    @GetMapping("/definition-options")
    @Operation(
            summary = "Read practice definition options",
            description =
                    "Returns available review events, recommended requirements, and allowed evidence sources by work type",
            operationId = "getPracticeDefinitionOptions")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<PracticeDefinitionOptionsDTO> definitionOptions(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(definitionOptionsService.options());
    }

    @GetMapping
    @Operation(
            summary = "List practice definitions",
            description = "Returns this workspace's practices, each with the autonomy in force for it, "
                    + "whether that autonomy was set on the practice or inherited from its group or the workspace, and "
                    + "which level decided it. Optionally narrowed to one autonomy.")
    @ApiResponse(
            responseCode = "200",
            description = "Practices returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = PracticeDTO.class))))
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<List<PracticeDTO>> listPractices(
            WorkspaceContext workspaceContext,
            @RequestParam(name = "autonomy", required = false)
                    @Parameter(
                            description =
                                    "Keep only the practices whose autonomy IN FORCE is exactly this one, inherited or not")
                    @Nullable
                    PracticeAutonomy autonomy) {
        List<PracticeDTO> practices = presenter.presentPractices(
                workspaceContext.id(), practiceService.listPractices(workspaceContext, autonomy));
        return ResponseEntity.ok(practices);
    }

    @GetMapping("/reviewed")
    @Operation(
            summary = "List reviewed practices, developer-facing",
            description = "Returns the developer-facing name, group, rationale, and example for every practice the "
                    + "workspace reviews (any autonomy above OFF)")
    @ApiResponse(
            responseCode = "200",
            description = "Developer practices returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ReviewedPracticeDTO.class))))
    public ResponseEntity<List<ReviewedPracticeDTO>> listReviewedPractices(WorkspaceContext workspaceContext) {
        List<ReviewedPracticeDTO> practices = practiceService.listReviewedPractices(workspaceContext).stream()
                .map(ReviewedPracticeDTO::from)
                .toList();
        return ResponseEntity.ok(practices);
    }

    @GetMapping("/{practiceSlug}")
    @Operation(summary = "Get a practice definition")
    @ApiResponse(
            responseCode = "200",
            description = "Practice returned",
            content = @Content(schema = @Schema(implementation = PracticeDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Practice not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<PracticeDTO> getPractice(
            WorkspaceContext workspaceContext, @PathVariable String practiceSlug) {
        Practice practice = practiceService.getPractice(workspaceContext, practiceSlug);
        return ResponseEntity.ok(presenter.present(workspaceContext.id(), practice));
    }

    @PostMapping
    @Operation(summary = "Create a new practice definition")
    @ApiResponse(
            responseCode = "201",
            description = "Practice created",
            content = @Content(schema = @Schema(implementation = PracticeDTO.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Practice slug already exists in this workspace",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Practice group not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    public ResponseEntity<PracticeDTO> createPractice(
            WorkspaceContext workspaceContext, @Valid @RequestBody CreatePracticeRequestDTO request) {
        Practice practice = practiceService.createPractice(workspaceContext, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{slug}")
                .buildAndExpand(practice.getSlug())
                .toUri();
        return ResponseEntity.created(location).body(presenter.present(workspaceContext.id(), practice));
    }

    @PatchMapping("/reorder")
    @Operation(
            summary = "Reorder the practices within a group",
            description =
                    "Sets each practice's display order to its index in the provided slug list (one atomic write)")
    @ApiResponse(
            responseCode = "200",
            description = "Practices reordered; the full ordered practice list is returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = PracticeDTO.class))))
    @ApiResponse(
            responseCode = "400",
            description = "orderedSlugs is empty, has duplicates, or is not the group's complete set",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "catalog order affects presentation, not review execution or delivery")
    public ResponseEntity<List<PracticeDTO>> reorderPractices(
            WorkspaceContext workspaceContext, @Valid @RequestBody ReorderPracticesRequestDTO request) {
        practiceService.reorderPractices(
                workspaceContext, Objects.requireNonNull(request.groupSlug()), request.orderedSlugs());
        List<PracticeDTO> practices = presenter.presentPractices(
                workspaceContext.id(), practiceService.listPractices(workspaceContext, null));
        return ResponseEntity.ok(practices);
    }

    @PatchMapping("/{practiceSlug}")
    @Operation(summary = "Update a practice")
    @ApiResponse(
            responseCode = "200",
            description = "Practice updated",
            content = @Content(schema = @Schema(implementation = PracticeDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Practice or practice group not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    public ResponseEntity<PracticeDTO> updatePractice(
            WorkspaceContext workspaceContext,
            @PathVariable String practiceSlug,
            @Valid @RequestBody UpdatePracticeRequestDTO request) {
        Practice practice = practiceService.updatePractice(workspaceContext, practiceSlug, request);
        return ResponseEntity.ok(presenter.present(workspaceContext.id(), practice));
    }

    @PatchMapping("/{practiceSlug}/autonomy")
    @Operation(
            summary = "Set how much autonomy the system has over one practice",
            description = "OFF stops the review entirely. HUMAN_APPROVAL runs it and records every observation, holds "
                    + "feedback on the work for an authorized reviewer, and still writes the developer's practice pages "
                    + "and the mentor, which are read on request. AUTOMATIC sends feedback on the work without asking, as "
                    + "far as this workspace's reach allows. Send a null autonomy to clear the practice's own setting so "
                    + "it follows its group, and through the group the workspace default.")
    @ApiResponse(
            responseCode = "200",
            description = "Autonomy updated; the response carries the autonomy now in force and where it came from",
            content = @Content(schema = @Schema(implementation = PracticeDTO.class)))
    @ApiResponse(
            responseCode = "400",
            description = "The autonomy cannot be selected because this practice's "
                    + "review settings cannot run an automated review at any autonomy above OFF",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Practice not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_USAGE")
    public ResponseEntity<PracticeDTO> setAutonomy(
            WorkspaceContext workspaceContext,
            @PathVariable String practiceSlug,
            @Valid @RequestBody UpdatePracticeAutonomyRequestDTO request) {
        Practice practice = practiceService.setAutonomy(workspaceContext, practiceSlug, request.autonomy());
        return ResponseEntity.ok(presenter.present(workspaceContext.id(), practice));
    }

    @PutMapping("/{practiceSlug}/group")
    @Operation(
            summary = "Move a practice",
            description = "Moves the practice to the requested group, or to Unassigned when groupSlug is null")
    @ApiResponse(
            responseCode = "200",
            description = "Practice moved",
            content = @Content(schema = @Schema(implementation = PracticeDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Practice or group not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    public ResponseEntity<PracticeDTO> bindGroup(
            WorkspaceContext workspaceContext,
            @PathVariable String practiceSlug,
            @Valid @RequestBody BindPracticeGroupRequestDTO request) {
        Practice practice = groupService.bindPractice(workspaceContext, practiceSlug, request.groupSlug());
        return ResponseEntity.ok(presenter.present(workspaceContext.id(), practice));
    }

    @PutMapping("/{practiceSlug}/placement")
    @Operation(
            summary = "Place a practice in the catalog",
            description =
                    "Moves the practice and sets its exact position in one atomic write; omit groupSlug for Unassigned")
    @ApiResponse(
            responseCode = "200",
            description = "Practice placed; the full updated practice list is returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = PracticeDTO.class))))
    @ApiResponse(
            responseCode = "400",
            description = "position is missing, negative, or beyond the destination",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Practice or group not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    public ResponseEntity<List<PracticeDTO>> placePractice(
            WorkspaceContext workspaceContext,
            @PathVariable String practiceSlug,
            @Valid @RequestBody PlacePracticeRequestDTO request) {
        List<PracticeDTO> practices = presenter.presentPractices(
                workspaceContext.id(),
                practiceService.placePractice(workspaceContext, practiceSlug, request.groupSlug(), request.position()));
        return ResponseEntity.ok(practices);
    }

    @DeleteMapping("/{practiceSlug}")
    @Operation(summary = "Delete a practice definition")
    @ApiResponse(responseCode = "204", description = "Practice deleted")
    @ApiResponse(
            responseCode = "404",
            description = "Practice not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    public ResponseEntity<Void> deletePractice(WorkspaceContext workspaceContext, @PathVariable String practiceSlug) {
        practiceService.deletePractice(workspaceContext, practiceSlug);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/autonomy")
    @Operation(
            summary = "Summarise how much autonomy this workspace grants, by group",
            description = "How many practices sit at each autonomy, for the whole workspace and for each "
                    + "group, plus the workspace default and where feedback may go. The summary a hundred-practice "
                    + "catalogue is read through — answered here so a client never has to fetch every practice to "
                    + "count them.")
    @ApiResponse(
            responseCode = "200",
            description = "Rollup returned",
            content = @Content(schema = @Schema(implementation = AutonomyRollupDTO.class)))
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<AutonomyRollupDTO> autonomyRollup(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(rollupService.rollup(workspaceContext.id()));
    }
}
