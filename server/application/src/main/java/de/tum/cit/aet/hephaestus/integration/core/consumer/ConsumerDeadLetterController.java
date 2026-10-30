package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@WorkspaceScopedController
@RequireAtLeastWorkspaceAdmin
@RequestMapping("/integrations/dead-letters")
@Validated
public class ConsumerDeadLetterController {
    private final ConsumerDeadLetterService service;
    private final ConsumerDeadLetterReplayService replays;

    public ConsumerDeadLetterController(ConsumerDeadLetterService service, ConsumerDeadLetterReplayService replays) {
        this.service = service;
        this.replays = replays;
    }

    @GetMapping
    @Operation(operationId = "listConsumerDeadLetters", summary = "List this workspace's dead letters")
    public List<ConsumerDeadLetterDTO> list(
            WorkspaceContext workspace, @RequestParam(defaultValue = "0") @Min(0) long after) {
        return service.listWorkspace(workspace.id(), after);
    }

    @PostMapping("/{id}/replays")
    @Operation(operationId = "replayConsumerDeadLetter", summary = "Replay a dead letter in this workspace")
    @AuditExempt(reason = "Retries an already admitted provider event through its original handler and route admission")
    public ConsumerDeadLetterDTO replay(WorkspaceContext workspace, @PathVariable Long id) {
        return replays.replayWorkspace(workspace.id(), id);
    }
}
