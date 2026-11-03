package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnServerRole
@RequireInstanceAdmin
@WorkspaceAgnostic("Unassigned installation events only; workspace records have a separate scoped API")
@RequestMapping("/admin/integration/dead-letters")
@Validated
public class InstallationDeadLetterController {
    private final ConsumerDeadLetterService service;
    private final ConsumerDeadLetterReplayService replays;

    public InstallationDeadLetterController(
            ConsumerDeadLetterService service, ConsumerDeadLetterReplayService replays) {
        this.service = service;
        this.replays = replays;
    }

    @GetMapping
    @Operation(operationId = "listInstallationDeadLetters", summary = "List unassigned installation dead letters")
    public CursorPageDTO<ConsumerDeadLetterDTO> list(
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size) {
        return service.listInstallation(cursor, size);
    }

    @PostMapping("/{id}/replays")
    @Operation(operationId = "replayInstallationDeadLetter", summary = "Replay an unassigned installation dead letter")
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "INSTALLATION_EVENT_REPLAYED")
    @RequiresRecentSignIn
    public ConsumerDeadLetterDTO replay(@PathVariable Long id) {
        return replays.replayInstallation(id, actorId());
    }

    private static Long actorId() {
        return SecurityUtils.getCurrentAccountId()
                .orElseThrow(() -> new AccessDeniedException("An account is required"));
    }
}
