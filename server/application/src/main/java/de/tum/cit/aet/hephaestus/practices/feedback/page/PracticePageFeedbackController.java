package de.tum.cit.aet.hephaestus.practices.feedback.page;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.page.dto.PracticePageFeedbackDTO;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@WorkspaceScopedController
@TeamOnly
@PreAuthorize("@workspaceSecure.isMember()")
@RequestMapping("/practices/feedback/practice-page")
@RequiredArgsConstructor
@ConditionalOnServerRole
public class PracticePageFeedbackController {

    private final PracticePageFeedbackService feedback;

    @GetMapping
    @Operation(operationId = "getOwnPracticePageFeedback", summary = "Read your practice feedback")
    public List<PracticePageFeedbackDTO> getOwnPracticePageFeedback(WorkspaceContext workspace) {
        return feedback.getOwnPracticePageFeedback(workspace.id());
    }
}
