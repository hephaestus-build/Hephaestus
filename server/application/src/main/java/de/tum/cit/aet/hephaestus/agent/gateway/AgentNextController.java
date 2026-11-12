package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pull of a sandbox: it asks for its subject's next run, and the gateway leases it to the sandbox (ADR 0079 §
 * Gateway API). The sandbox is the run's only writer from here on; every later call names the run and its epoch.
 */
@RestController
@RequestMapping("/internal/agent")
@PreAuthorize("isAuthenticated()")
@ConditionalOnAgentRuntime
@Profile("!specs & !cds-training")
@WorkspaceAgnostic("The sandbox token names the workspace, and the gateway checked it against the sandbox's live row")
public class AgentNextController {

    private final AgentSandboxes sandboxes;
    private final Map<AgentRunKind, SandboxRunKind> kinds;
    private final AgentReviewDrain drain;

    public AgentNextController(AgentSandboxes sandboxes, List<SandboxRunKind> kinds, AgentReviewDrain drain) {
        this.sandboxes = sandboxes;
        this.kinds = SandboxRunKind.byKind(kinds);
        this.drain = drain;
    }

    /**
     * The subject's next run, leased to this sandbox; {@code 204} when none is due. Only the run kinds that this
     * sandbox's kind drives are offered, and each kind moves its domain row in the transaction of the lease.
     */
    @PostMapping("/next")
    public ResponseEntity<Assignment> next(@AuthenticationPrincipal AgentSandboxes.LiveSandbox sandbox) {
        Set<AgentRunKind> offered = kinds.values().stream()
                .filter(kind -> kind.sandbox() == sandbox.kind())
                .map(SandboxRunKind::kind)
                .collect(Collectors.toUnmodifiableSet());
        return drain.claim(() -> sandboxes.lease(
                        sandbox,
                        offered,
                        () -> kinds.values().stream()
                                .filter(kind -> kind.sandbox() == sandbox.kind())
                                .forEach(kind -> kind.beforeWrite(sandbox)),
                        lease -> {
                            // The token filter restores the request scope after common advice records a failure.
                            MDC.put(StructuredLogKeys.RUN_ID, lease.runId().toString());
                            MDC.put(StructuredLogKeys.RUN_EPOCH, Integer.toString(lease.epoch()));
                            return Objects.requireNonNull(kinds.get(lease.kind()))
                                    .claimed(new SandboxRun(sandbox, lease.fence(), lease.kind(), lease.attempt()));
                        }))
                .map(lease -> ResponseEntity.ok(new Assignment(
                        lease.runId(), lease.epoch(), lease.kind(), lease.leasedUntil(), lease.deadline())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * @param epoch the lease's fencing number, which every later call of the run names
     * @param leasedUntil when the lease ends unless the sandbox renews it
     * @param deadline when the run ends whatever its lease; null for none
     */
    public record Assignment(
            UUID runId,
            int epoch,
            AgentRunKind kind,
            Instant leasedUntil,
            @Nullable Instant deadline) {}
}
