package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the conversation loop pulls the turn attempts it runs, on any worker's sandbox gateway, with the loop's own
 * credential. The loop pulls only while it has room for one more turn.
 */
@RestController
@RequestMapping("/internal/llm/turn-assignments")
@PreAuthorize("isAuthenticated()")
@ConditionalOnWorkerRole
@Profile(HephTurnConfiguration.PROFILES)
@WorkspaceAgnostic("The loop runs every workspace's turns; each assignment's credential binds its workspace")
public class HephTurnAssignmentController {

    private final HephTurns turns;

    public HephTurnAssignmentController(HephTurns turns) {
        this.turns = turns;
    }

    /**
     * The next turn attempt, after a wait of up to {@link HephTurns#PULL_WAIT}: {@code 200} with the attempt,
     * {@code 204} when none may start now, or {@code 503} when this worker stops or cannot claim, so the loop waits
     * and pulls again, maybe from another worker.
     */
    @PostMapping
    public ResponseEntity<HephTurns.Assignment> pull() {
        try {
            return turns.assign()
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.noContent().build());
        } catch (RuntimeException unavailable) {
            // HephTurns logged and counted a failed claim; a drain is no failure.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
