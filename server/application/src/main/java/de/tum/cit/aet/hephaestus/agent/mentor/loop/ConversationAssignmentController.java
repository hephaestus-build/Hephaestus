package de.tum.cit.aet.hephaestus.agent.mentor.loop;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The conversation loop's pull of its next attempt. Only the release's loop credential reaches it (the gateway's
 * security configuration); the answer carries the attempt's own credential, which every later call of the attempt uses.
 */
@RestController
@RequestMapping("/internal/llm/conversation/assignments")
@Hidden
@ConditionalOnWorkerRole
@Profile(ConversationAssignments.PROFILES)
@WorkspaceAgnostic("The release's conversation loop claims across workspaces; each attempt's token binds one")
public class ConversationAssignmentController {

    private final ConversationAssignments assignments;

    public ConversationAssignmentController(ConversationAssignments assignments) {
        this.assignments = assignments;
    }

    @PostMapping
    public ResponseEntity<ConversationAssignments.AssignmentDTO> pull() {
        return assignments
                .next()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
