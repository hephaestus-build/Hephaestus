package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Membership permits the lookup; verified account identities alone determine whose feedback is returned. */
@WorkspaceScopedController
@RequestMapping("/practices/feedback/on-work")
@Tag(name = "Practice feedback", description = "The authenticated developer's feedback")
@RequiredArgsConstructor
@Validated
public class DeliveredWorkFeedbackController {
    private final DeliveredWorkFeedbackService feedback;

    @GetMapping
    @Operation(
            operationId = "getOwnDeliveredWorkFeedback",
            summary = "The caller's recorded feedback comments on this work",
            description =
                    "Read-only metadata for up to 50 in-context pieces of feedback addressed to the caller with recorded provider comments, newest delivery first. "
                            + "Uses the exact connected-provider work lookup. Only recorded successful placements are included, even when the remaining delivery failed. Incomplete units omit draft practice metadata. No bodies, private channels or replaced feedback; "
                            + "admin status does not broaden the recipient. Permalinks are recorded provider links, not live existence checks.")
    public ResponseEntity<DeliveredWorkFeedbackDTO> getOwnDeliveredWorkFeedback(
            WorkspaceContext workspaceContext, @RequestParam @Size(max = 2048) String url) {
        if (!workspaceContext.hasMembership()) {
            throw new AccessForbiddenException("Workspace membership is required to look up reviewed work");
        }
        return ResponseEntity.ok(feedback.get(workspaceContext.id(), url));
    }
}
