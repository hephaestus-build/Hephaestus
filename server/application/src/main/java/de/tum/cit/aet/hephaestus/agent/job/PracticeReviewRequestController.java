package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.CurrentAccountUsers;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextResolver;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The front door a developer uses to ask for a review of a piece of work, now.
 *
 * <p>Thin by design: {@link ManualReviewRequests} owns the whole sequence — standing, rate limits,
 * ledger, gate, submission — because the merge-request bot command performs the identical one.
 *
 * <p>Refusal is a 200, not a 4xx: almost every reason a review doesn't run is a workspace condition the
 * asker can neither see nor fix, and a 4xx would say the button itself is broken. 404/403 are reserved
 * for the two things actually wrong with the request: an artifact this workspace doesn't monitor, and a
 * caller with no standing on it.
 *
 * <p>{@code isMember()} is checked because {@code WorkspaceContextFilter} admits an anonymous caller on a
 * publicly viewable workspace, and a spend button must not be part of what public visibility grants; it
 * is only the outer fence — {@link ReviewRequestAuthority} owns the rule that decides standing.
 */
@WorkspaceScopedController
@RequestMapping("/practices/review-requests")
@Tag(name = "Practice review requests", description = "Asking for a review of a piece of work by hand")
@RequiredArgsConstructor
@Validated
public class PracticeReviewRequestController {

    private final ManualReviewRequests manualReviewRequests;
    private final ReviewableArtifactLoader artifactLoader;
    private final CurrentAccountUsers currentAccountUsers;
    private final WorkspaceContextResolver workspaceContextResolver;

    @PostMapping
    @PreAuthorize("@workspaceSecure.isMember()")
    @Operation(
            summary = "Ask for a review of a piece of work now",
            description = "Answers 200 both when a review starts and when one deliberately does not; the "
                    + "body says which, and why. 403 only when the caller has no standing on the artifact.",
            operationId = "requestPracticeReview")
    @ApiResponse(
            responseCode = "200",
            description = "The ask was understood: a review is running, or the body names what stopped it",
            content = @Content(schema = @Schema(implementation = ReviewRequestOutcomeDTO.class)))
    @ApiResponse(
            responseCode = "400",
            description = "The artifact kind is not one that can be asked for",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "403",
            description = "The caller is neither the work's author or assignee nor a workspace admin",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such artifact in this workspace",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReviewRequestOutcomeDTO> requestReview(
            WorkspaceContext workspaceContext, @Valid @RequestBody CreateReviewRequestDTO request) {
        Workspace workspace = workspaceContextResolver.requireWorkspace(workspaceContext);
        // Every identity of the account, not just the session's: membership is keyed on the SCM user, so
        // an admin on GitLab who signed in via GitHub would otherwise be refused as "two people".
        List<User> requesters = currentAccountUsers.resolve();
        ManualReviewOutcome outcome = requestFor(workspace, request, requesters);
        if (outcome.status() == ManualReviewOutcome.Status.FORBIDDEN) {
            // Same sentence whether there's no such requester or the requester lacks standing: telling
            // them apart would let a caller enumerate who is on a team.
            throw new AccessForbiddenException(
                    "Only the author or an assignee of the work, or a workspace admin, can request a review of it.");
        }
        return ResponseEntity.ok(ReviewRequestOutcomeDTO.from(outcome));
    }

    /**
     * The loaders join the artifact to the workspace rather than trusting the id: nothing in an artifact
     * id relates it to a workspace, so loading by id alone would let one workspace spend its budget
     * reviewing another's work — see {@link ReviewableArtifactLoader}.
     */
    private ManualReviewOutcome requestFor(Workspace workspace, CreateReviewRequestDTO request, List<User> requesters) {
        ArtifactKind kind = parseKind(request.artifactKind());
        if (ScmSignals.PULL_REQUEST.equals(kind)) {
            PullRequest pullRequest = artifactLoader
                    .findPullRequestForGate(workspace.getId(), request.artifactId())
                    .orElseThrow(() -> notFound(request));
            return manualReviewRequests.requestPullRequestReview(workspace, pullRequest, requesters);
        }
        if (ScmSignals.ISSUE.equals(kind)) {
            Issue issue = artifactLoader
                    .findIssueForGate(workspace.getId(), request.artifactId())
                    .orElseThrow(() -> notFound(request));
            return manualReviewRequests.requestIssueReview(workspace, issue, requesters);
        }
        // A kind that exists but has no front door here: a chat thread or document is reviewed on the
        // occasion its source produces, with nothing for a person to point at and ask about.
        throw new IllegalArgumentException(
                "You can request a review only for pull requests, merge requests, and issues.");
    }

    private static ArtifactKind parseKind(String raw) {
        try {
            return ArtifactKind.of(raw);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Hephaestus does not know this kind of work.", malformed);
        }
    }

    /**
     * Same answer for "no such artifact" and "not this workspace's artifact", so this endpoint can't be
     * used to probe which work another workspace monitors.
     */
    private static EntityNotFoundException notFound(CreateReviewRequestDTO request) {
        return new EntityNotFoundException(request.artifactKind(), request.artifactId());
    }
}
