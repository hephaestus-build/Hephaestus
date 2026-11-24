package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@ConditionalOnServerRole
@WorkspaceScopedController
@TeamOnly
@PreAuthorize("@workspaceSecure.isMember()")
@RequestMapping("/practice-groups/standings")
@Tag(name = "Practice Group Standings", description = "The current developer's standing in each practice group")
@RequiredArgsConstructor
public class PracticeGroupStandingController {

    private final PracticeGroupService practiceGroupService;
    private final PracticeGroupStandingService practiceGroupStandingService;

    @GetMapping
    @Operation(
            operationId = "getOwnGroupStandings",
            summary = "List the current developer's practice group standings",
            description =
                    "Returns every active practice group's standing, direction, guidance, and supporting results.")
    @ApiResponse(
            responseCode = "200",
            description = "Practice group standings returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = PracticeGroupStandingDTO.class))))
    public ResponseEntity<List<PracticeGroupStandingDTO>> listPracticeGroupStandings(WorkspaceContext context) {
        List<PracticeGroup> groups = practiceGroupService.listGroups(context, true);
        return ResponseEntity.ok(practiceGroupStandingService.getGroupStandings(context.id(), groups));
    }
}
