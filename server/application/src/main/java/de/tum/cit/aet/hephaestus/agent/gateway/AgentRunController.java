package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * The routes of one run that a sandbox holds (ADR 0079 § Gateway API): its context, its lease and its end. Each takes
 * a {@link SandboxRun}, so it answers only the sandbox of the run's own subject. The sandbox is the run's only writer:
 * nothing else renews its lease.
 */
@RestController
@RequestMapping("/internal/agent/runs/{runId}")
@PreAuthorize("isAuthenticated()")
@ConditionalOnAgentRuntime
@Profile("!specs & !cds-training")
@WorkspaceAgnostic("The sandbox token and the run's own row name the workspace, and the resolver checks both")
public class AgentRunController {

    /** The end of a sandbox whose network policy did not hold, as its driver sends it. */
    static final String UNISOLATED = "unisolated";

    private final AgentRuns runs;
    private final AgentSandboxes sandboxes;
    private final TransactionTemplate transactions;
    private final Map<AgentRunKind, SandboxRunKind> kinds;

    public AgentRunController(
            AgentRuns runs, AgentSandboxes sandboxes, TransactionTemplate transactions, List<SandboxRunKind> kinds) {
        this.runs = runs;
        this.sandboxes = sandboxes;
        this.transactions = transactions;
        this.kinds = SandboxRunKind.byKind(kinds);
    }

    /** The run's context, as its kind's loop reads it. */
    @GetMapping("/context")
    public JsonNode context(SandboxRun run) {
        var kind = kind(run);
        if (!runs.isCurrent(run.fence().run(), run.fence().epoch())) throw new StaleRunException(run.fence());
        return kind.context(run);
    }

    /** Extends the run's lease; the sandbox renews it as long as it works. */
    @PostMapping("/lease")
    public Lease lease(SandboxRun run) {
        Instant leasedUntil = Objects.requireNonNull(transactions.execute(status -> runs.renew(run.fence())))
                .orElseThrow(() -> new StaleRunException(run.fence()));
        return new Lease(leasedUntil);
    }

    /**
     * Ends the run, or hands it on. A run that this claim already ended answers as ended. A sandbox whose network
     * policy did not hold ends its run as {@code unisolated}: the run is handed on and the sandbox taken back in one
     * transaction, also when the run had moved on already, because the sandbox is unsafe whatever its run.
     */
    @PostMapping("/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(SandboxRun run, @Valid @RequestBody End request) {
        var kind = kind(run);
        boolean unisolated = UNISOLATED.equals(request.outcome());
        var outcome = unisolated
                ? SandboxRunKind.Outcome.HANDED_ON
                : Arrays.stream(SandboxRunKind.Outcome.values())
                        .filter(candidate -> candidate.value().equals(request.outcome()))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown outcome"));
        boolean held = Boolean.TRUE.equals(transactions.execute(status -> {
            if (unisolated) sandboxes.revoke(run.sandbox(), AgentSandboxes.Revocation.UNISOLATED);
            return kind.end(run, outcome);
        }));
        if (!held) throw new StaleRunException(run.fence());
    }

    private SandboxRunKind kind(SandboxRun run) {
        var kind = kinds.get(run.kind());
        if (kind == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return kind;
    }

    /** @param leasedUntil when the lease ends unless the sandbox renews it again */
    public record Lease(Instant leasedUntil) {}

    /** @param outcome {@code completed}, {@code handed_on} or {@code unisolated} */
    public record End(@NotBlank String outcome) {}
}
