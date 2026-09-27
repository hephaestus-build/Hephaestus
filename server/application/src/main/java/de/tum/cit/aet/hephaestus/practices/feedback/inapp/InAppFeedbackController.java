package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import de.tum.cit.aet.hephaestus.practices.feedback.inapp.dto.InAppFeedbackDTO;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The developer's own practice pages: the practice feedback prepared for them, readable by nobody else.
 *
 * <p>Workspace membership is the only requirement ({@link WorkspaceScopedController}); there is no admin
 * route here and there is <b>no user parameter, on any endpoint, ever</b>. A caller can read their own
 * feedback and nobody else's, and that is what makes the surface safe to write private text onto.
 * Instructors and workspace admins reach the operator surfaces instead, which show that a in-app unit
 * exists but never its text.
 */
@WorkspaceScopedController
@RequestMapping("/practices/feedback/in-app")
@Tag(name = "In-App Feedback", description = "The developer's own prepared practice feedback")
@RequiredArgsConstructor
public class InAppFeedbackController {

    private final InAppFeedbackService inAppFeedbackService;

    @GetMapping
    @Operation(
            summary = "The current developer's in-app feedback",
            description = "Process-level messages prepared for the authenticated developer: for each way of working "
                    + "that recurs in their work, what the pattern is, the pieces of work it was observed on, and "
                    + "one thing to try next. Distinct from in-context notes (which say what is wrong in one diff) "
                    + "and from the mentor conversation (which asks rather than tells). Reading a message is what "
                    + "delivers it, so this GET records the delivery.")
    @ApiResponse(
            responseCode = "200",
            description = "In-app messages returned, newest first",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = InAppFeedbackDTO.class))))
    public ResponseEntity<List<InAppFeedbackDTO>> getInAppFeedback(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(inAppFeedbackService.getInAppFeedback(workspaceContext.id()));
    }
}
