package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.profile.dto.PracticeProfileOverviewDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The practice profile's overview, for the calling developer and nobody else: like the standings it is read
 * off, there is no user parameter on this route.
 */
@WorkspaceScopedController
@PreAuthorize("@workspaceSecure.isMember()")
@RequestMapping("/practice-profile")
@Tag(name = "Practice Profile", description = "The current developer's practice profile")
@RequiredArgsConstructor
public class PracticeProfileOverviewController {

    private final PracticeProfileOverviewService overviewService;

    @GetMapping("/overview")
    @Operation(
            operationId = "getPracticeProfileOverview",
            summary = "What held, what changed and which work was reviewed since the run before the latest one",
            description = "Returns the practices holding as a strength, every change to a standing, trend, group or"
                    + " piece of feedback inside the window, and the work reviewed inside it, as structured"
                    + " events. The window opens at the run before the latest one and closes now; with no such"
                    + " run it spans the standing's look-back of " + PracticeStandingService.LOOKBACK_DAYS
                    + " days.")
    @ApiResponse(responseCode = "200", description = "Practice profile overview returned")
    public ResponseEntity<PracticeProfileOverviewDTO> getOverview(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(overviewService.getOverview(workspaceContext));
    }
}
