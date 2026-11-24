package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeStandingDTO;
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
@RequestMapping("/practices/standings")
@Tag(name = "Practice Standings", description = "The current developer's standing in each practice")
@RequiredArgsConstructor
public class PracticeStandingController {

    private final PracticeStandingService practiceStandingService;

    @GetMapping
    @Operation(
            operationId = "getOwnPracticeStandings",
            summary = "List the current developer's practice standings",
            description = "Returns each practice's standing, direction, supporting results, and developer guidance.")
    @ApiResponse(
            responseCode = "200",
            description = "Practice standings returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = PracticeStandingDTO.class))))
    public ResponseEntity<List<PracticeStandingDTO>> listPracticeStandings(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(practiceStandingService.getStandings(workspaceContext.id()));
    }
}
