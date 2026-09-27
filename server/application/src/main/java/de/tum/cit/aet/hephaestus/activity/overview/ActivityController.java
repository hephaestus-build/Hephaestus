package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityOverviewDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWorkPageDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.MemberActivityDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.OpenWorkDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * What members did and what is open for them. Any member reads any member's activity; provider permissions are
 * not checked (ADR 0045).
 */
@WorkspaceScopedController
@PreAuthorize("@workspaceSecure.isMember()")
@RequestMapping("/activity")
@RequiredArgsConstructor
@Validated
@Tag(name = "Activity", description = "What members did and what is open for them")
@ApiResponse(
        responseCode = "403",
        description = "The caller is not a member of the workspace",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
public class ActivityController {

    private final ActivityService activityService;
    private final OpenWorkService openWorkService;
    private final Clock clock;

    @GetMapping("/summary")
    @Operation(
            operationId = "getActivitySummary",
            summary = "Count activity in a time range, in total and over time",
            description = "One member's activity when login is given, otherwise everyone's in the workspace or"
                    + " the team. With both, the member's activity within the team's scope. The range is split"
                    + " into days, weeks or months, by its length, in the given time zone.")
    @ApiResponse(responseCode = "200", description = "Activity counted")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid range or time zone",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Member or team not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ActivityOverviewDTO> getActivitySummary(
            WorkspaceContext workspaceContext,
            @Parameter(description = "The member; omit for everyone") @RequestParam(required = false) @Nullable
                    String login,
            @Parameter(description = "A team, with its visible sub-teams; omit for the workspace")
                    @RequestParam(required = false)
                    @Nullable
                    Long teamId,
            @Valid @ParameterObject ActivityRangeFilterParams range,
            @Parameter(description = "The IANA time zone whose midnights start the buckets, such as Europe/Berlin")
                    @RequestParam(defaultValue = "UTC")
                    String zone) {
        return ResponseEntity.ok(activityService.overview(
                workspaceContext.id(), login, teamId, range.toRange(clock), ActivityBuckets.zone(zone)));
    }

    @GetMapping("/members")
    @Operation(
            operationId = "listMemberActivity",
            summary = "List members with their activity in a time range",
            description = "Members shown in workspace activity, or one team's, ordered by name.")
    @ApiResponse(responseCode = "200", description = "Members listed")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid range",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Team not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<List<MemberActivityDTO>> listMemberActivity(
            WorkspaceContext workspaceContext,
            @Parameter(description = "A team, with its visible sub-teams; omit for everyone")
                    @RequestParam(required = false)
                    @Nullable
                    Long teamId,
            @Valid @ParameterObject ActivityRangeFilterParams range) {
        return ResponseEntity.ok(activityService.members(workspaceContext.id(), teamId, range.toRange(clock)));
    }

    @GetMapping("/work")
    @Operation(
            operationId = "getActivityWork",
            summary = "List activity by the pull request or issue it happened on, one page at a time",
            description = "One member's activity when login is given, otherwise everyone's in the workspace or"
                    + " the team. With both, the member's activity within the team's scope. Each pull request or"
                    + " issue is listed once, by its latest activity in the range, newest first.")
    @ApiResponse(responseCode = "200", description = "One page of work")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid range, kind, cursor or size",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Member or team not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ActivityWorkPageDTO> getActivityWork(
            WorkspaceContext workspaceContext,
            @Parameter(description = "The member; omit for everyone") @RequestParam(required = false) @Nullable
                    String login,
            @Parameter(description = "A team, with its visible sub-teams; omit for the workspace")
                    @RequestParam(required = false)
                    @Nullable
                    Long teamId,
            @Valid @ParameterObject ActivityRangeFilterParams range,
            @Valid @ParameterObject ActivityWorkFilterParams work) {
        return ResponseEntity.ok(
                activityService.work(workspaceContext.id(), login, teamId, work.range(range, clock), work));
    }

    @GetMapping("/members/{login}/open-work")
    @Operation(
            operationId = "getOpenWork",
            summary = "List what is open for a member",
            description = "Review requests, open pull requests and assigned issues, most recently updated first."
                    + " Pull requests carry their reviewers.")
    @ApiResponse(responseCode = "200", description = "Open work listed")
    @ApiResponse(
            responseCode = "404",
            description = "Member not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<OpenWorkDTO> getOpenWork(
            WorkspaceContext workspaceContext,
            @Parameter(description = "The member's login") @PathVariable String login) {
        return ResponseEntity.ok(openWorkService.openWork(workspaceContext.id(), login));
    }
}
