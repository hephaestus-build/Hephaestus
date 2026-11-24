package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.practices.review.dto.StartTrialRequestDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.TrialDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** Trials of practices: workspace admins only (ADR 0118 decision 10). */
@ConditionalOnServerRole
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/practice-trials")
@Tag(name = "Practice reviews")
@RequiredArgsConstructor
@RequireAtLeastWorkspaceAdmin
public class PracticeTrialController {
    private final PracticeTrialService trials;

    @PostMapping
    @AuditExempt(reason = "A trial records its requester on its review row and changes no configuration")
    @Operation(
            operationId = "startPracticeTrial",
            summary = "Try a practice on one item",
            description = "Runs the practice on the item as a review does, but records no observation and sends no"
                    + " feedback. The trial counts against the workspace's AI budget.")
    public ResponseEntity<TrialDTO> start(WorkspaceContext context, @Valid @RequestBody StartTrialRequestDTO request) {
        var id = trials.start(
                context.id(), request, SecurityUtils.getCurrentAccountId().orElseThrow());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(trials.get(context.id(), id));
    }

    @GetMapping("/{trialId}")
    @Operation(operationId = "getPracticeTrial", summary = "Read a trial and what it found")
    public TrialDTO get(WorkspaceContext context, @PathVariable UUID trialId) {
        return trials.get(context.id(), trialId);
    }
}
