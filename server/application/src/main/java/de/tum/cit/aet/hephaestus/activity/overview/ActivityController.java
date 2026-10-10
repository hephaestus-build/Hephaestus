package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPeopleDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDetailDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWorkPageDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.OpenWorkDTO;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Repository contributions and open work. Members can read workspace activity; provider permissions are
 * not checked (ADR 0045).
 */
@WorkspaceScopedController
@PreAuthorize("isAuthenticated() && @workspaceSecure.isMember()")
@RequestMapping("/activity")
@RequiredArgsConstructor
@Validated
@Tag(name = "Activity", description = "Repository contributions and open work")
@ApiResponse(
        responseCode = "403",
        description = "The caller is not a member of the workspace",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
        responseCode = "400",
        description = "A request parameter is not valid",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
        responseCode = "404",
        description = "Workspace, contributor, team or repository not found",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
public class ActivityController {

    private final ActivityService activityService;
    private final OpenWorkService openWorkService;
    private final ActivityPeopleService peopleService;
    private final ActivityAutomationService automationService;
    private final PublicActivityObjectionService publicObjections;

    public record PublicActivityHiddenCountDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) long hiddenPeople) {}

    @GetMapping("/public-hidden-count")
    @RequireAtLeastWorkspaceAdmin
    @Operation(
            operationId = "getPublicActivityHiddenCount",
            summary = "Count hidden public contributors without identifying them")
    @ApiResponse(responseCode = "200", description = "Hidden contributors counted")
    public PublicActivityHiddenCountDTO getPublicActivityHiddenCount(WorkspaceContext workspaceContext) {
        return new PublicActivityHiddenCountDTO(publicObjections.hiddenPeople(workspaceContext.id()));
    }

    @PatchMapping("/people/{userId}/public-visibility")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_VISIBILITY")
    @Operation(
            operationId = "updatePublicActivityObjection",
            summary = "Honor a contributor publication objection, or restore workspace visibility")
    @ApiResponse(responseCode = "204", description = "Contributor visibility updated")
    public ResponseEntity<Void> updatePublicActivityObjection(
            WorkspaceContext workspaceContext, @PathVariable long userId, @RequestParam boolean hidden) {
        publicObjections.hide(workspaceContext.id(), userId, hidden);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/people/{userId}/automation")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "ACTIVITY_AUTOMATION")
    @Operation(
            operationId = "updateActivityAutomation",
            summary = "Treat a contributor as automation, or reset the classification")
    @ApiResponse(responseCode = "204", description = "Automation classification updated")
    public ResponseEntity<Void> updateActivityAutomation(
            WorkspaceContext workspaceContext, @PathVariable long userId, @RequestParam boolean treatAsAutomation) {
        automationService.classify(workspaceContext.id(), userId, treatAsAutomation);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/people")
    @Operation(
            operationId = "getActivityPeople",
            summary = "Count each contributor and automation account in one response")
    @ApiResponse(responseCode = "200", description = "Contributors counted")
    public ResponseEntity<ActivityPeopleDTO> getActivityPeople(
            WorkspaceContext workspaceContext,
            @ParameterObject ActivityPeopleRangeParams range,
            @RequestParam(required = false) @Nullable String team,
            @RequestParam(required = false) @Nullable Set<String> repo) {
        return ResponseEntity.ok(
                peopleService.people(workspaceContext.id(), range, team, repo == null ? Set.of() : repo));
    }

    @GetMapping("/people/{userId}")
    @Operation(operationId = "getActivityPerson", summary = "Count a contributor by week, type and repository")
    @ApiResponse(responseCode = "200", description = "Contributor activity counted")
    public ResponseEntity<ActivityPersonDetailDTO> getActivityPerson(
            WorkspaceContext workspaceContext,
            @PathVariable long userId,
            @ParameterObject ActivityPeopleRangeParams range,
            @RequestParam(required = false) @Nullable String team,
            @RequestParam(required = false) @Nullable Set<String> repo) {
        return ResponseEntity.ok(
                peopleService.person(workspaceContext.id(), userId, range, team, repo == null ? Set.of() : repo));
    }

    @GetMapping("/people/{userId}/work")
    @Operation(operationId = "getActivityPersonWork", summary = "List a contributor's work one page at a time")
    @ApiResponse(responseCode = "200", description = "One page of contributor work")
    public ResponseEntity<ActivityWorkPageDTO> getActivityPersonWork(
            WorkspaceContext workspaceContext,
            @PathVariable long userId,
            @ParameterObject ActivityPeopleRangeParams range,
            @RequestParam(required = false) @Nullable String team,
            @RequestParam(required = false) @Nullable Set<String> repo,
            @Valid @ParameterObject ActivityWorkFilterParams work) {
        return ResponseEntity.ok(activityService.personWork(
                workspaceContext.id(), userId, range, team, repo == null ? Set.of() : repo, work));
    }

    @GetMapping("/work")
    @Operation(
            operationId = "getActivityWork",
            summary = "List activity by the pull request or issue it happened on, one page at a time",
            description = "One member's activity when login is given, otherwise everyone's in the workspace or"
                    + " the team. With both, the member's activity within the team's scope. Each pull request or"
                    + " issue is listed once, by its latest activity in the range, newest first.")
    @ApiResponse(responseCode = "200", description = "One page of work")
    public ResponseEntity<ActivityWorkPageDTO> getActivityWork(
            WorkspaceContext workspaceContext,
            @RequestParam(required = false) @Nullable String login,
            @RequestParam(required = false) @Nullable String team,
            @RequestParam(required = false) @Nullable Set<String> repo,
            @ParameterObject ActivityPeopleRangeParams range,
            @Valid @ParameterObject ActivityWorkFilterParams work) {
        return ResponseEntity.ok(
                activityService.work(workspaceContext.id(), login, team, repo == null ? Set.of() : repo, range, work));
    }

    @GetMapping("/members/{login}/open-work")
    @Operation(
            operationId = "getOpenWork",
            summary = "List what is open for a member",
            description = "Review requests, open pull requests and assigned issues, most recently updated first."
                    + " Pull requests carry their reviewers.")
    @ApiResponse(responseCode = "200", description = "Open work listed")
    public ResponseEntity<OpenWorkDTO> getOpenWork(
            WorkspaceContext workspaceContext,
            @Parameter(description = "The member's login") @PathVariable String login) {
        return ResponseEntity.ok(openWorkService.openWork(workspaceContext.id(), login));
    }
}
