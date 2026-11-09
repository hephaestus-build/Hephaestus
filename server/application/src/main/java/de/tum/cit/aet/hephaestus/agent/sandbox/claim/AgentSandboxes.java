package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.SandboxJwt;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single owner of agent sandboxes: it requests a subject's sandbox, answers whether a sandbox's token is still
 * live, and takes a sandbox back. The row is written first and the claim after it, outside any transaction (no
 * network call holds a database connection), so a claim never exists without its row. A row whose claim was never
 * created is taken back by the reaper when the start limit passes.
 */
public class AgentSandboxes {

    private static final Logger log = LoggerFactory.getLogger(AgentSandboxes.class);

    /** The sandbox's credential, which the gateway takes ({@link AttemptTokenIssuer#issueForSandbox}). */
    static final String TOKEN_ENV = "HEPHAESTUS_SANDBOX_TOKEN";

    /** The gateway address, as an IP address: the sandbox has no DNS. */
    static final String GATEWAY_ENV = "HEPHAESTUS_GATEWAY_URL";

    private final AgentSandboxRepository sandboxes;
    private final AgentRuns runs;
    private final SandboxClaims claims;
    private final AttemptTokenIssuer tokens;
    private final TransactionTemplate transactions;
    private final String gatewayUrl;
    private final UnaryOperator<String> resolve;

    /** @param resolve the address of a host name, as the server's resolver answers it */
    AgentSandboxes(
            AgentSandboxRepository sandboxes,
            AgentRuns runs,
            SandboxClaims claims,
            AttemptTokenIssuer tokens,
            TransactionTemplate transactions,
            String gatewayUrl,
            UnaryOperator<String> resolve) {
        this.sandboxes = sandboxes;
        this.runs = runs;
        this.claims = claims;
        this.tokens = tokens;
        this.transactions = transactions;
        this.gatewayUrl = gatewayUrl;
        this.resolve = resolve;
    }

    /** A live sandbox: the caller of a gateway route, or the sandbox that serves a subject now. */
    public record LiveSandbox(UUID id, long workspaceId, String subjectKey, AgentSandboxKind kind) {}

    /**
     * The subject's live sandbox, or a new one with its claim. A new sandbox and its token end at {@code shutdownAt};
     * the controller deletes it then if nothing did before. Call it outside a transaction: it creates the claim after
     * its row commits.
     */
    public LiveSandbox ensure(long workspaceId, String subjectKey, AgentSandboxKind kind, Instant shutdownAt) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "A sandbox is requested outside a transaction, before its claim is created");
        if (!shutdownAt.isAfter(Instant.now())) throw new IllegalArgumentException("shutdownAt must be in the future");
        // A token that outlived its signing key would fail on every gateway call after its worker restarted.
        if (shutdownAt.isAfter(Instant.now().plus(tokens.longestSandboxToken())))
            throw new IllegalArgumentException("shutdownAt must not be later than the longest sandbox token");
        UUID id = UUID.randomUUID();
        String claimName = "agent-" + id;
        var requested = Objects.requireNonNull(transactions.execute(status -> sandboxes
                .findLive(workspaceId, subjectKey)
                .map(live -> new Requested(live, false))
                .or(() -> sandboxes
                        .request(id, workspaceId, subjectKey, kind.name(), claimName, shutdownAt)
                        .map(row -> new Requested(row, true)))
                // Another replica requested it between the two statements.
                .or(() -> sandboxes.findLive(workspaceId, subjectKey).map(live -> new Requested(live, false)))
                .orElseThrow(
                        () -> new IllegalStateException("The subject's sandbox was taken back as it was requested"))));
        var row = requested.row();
        if (requested.created()) {
            try {
                claims.create(new SandboxClaims.Claim(
                        row.getClaimName(),
                        row.getId(),
                        kind,
                        row.getShutdownAt(),
                        Map.of(
                                TOKEN_ENV,
                                tokens.issueForSandbox(row.getId(), workspaceId, subjectKey, row.getShutdownAt()),
                                GATEWAY_ENV,
                                gatewayAddress())));
            } catch (RuntimeException e) {
                // This sandbox only: the reaper may have taken it back already, and a newer one of the subject stays.
                // The reaper deletes whatever part of the claim the API kept.
                try {
                    transactions.executeWithoutResult(status -> sandboxes.releaseSandbox(row.getId(), workspaceId));
                } catch (RuntimeException release) {
                    e.addSuppressed(release);
                }
                throw e;
            }
            log.info("Sandbox requested: sandboxId={}, kind={}, claim={}", row.getId(), kind, row.getClaimName());
        }
        return live(row);
    }

    /**
     * The sandbox that a valid token names, while it is live and before its shutdown. The gateway asks for every
     * request, so a token stops working as soon as its sandbox is taken back. The first call makes the sandbox {@code
     * READY}; a later call that leases nothing is no use of it, so a sandbox that only polls still goes idle.
     */
    public Optional<LiveSandbox> authenticate(SandboxJwt token) {
        return Optional.ofNullable(transactions.execute(status -> sandboxes
                        .markReady(token.sandboxId(), token.workspaceId(), token.subjectKey())
                        .or(() -> sandboxes.findReady(token.sandboxId(), token.workspaceId(), token.subjectKey()))
                        .orElse(null)))
                .map(AgentSandboxes::live);
    }

    /**
     * Leases the subject's next run to its sandbox, while the sandbox is live: one transaction holds the sandbox, so
     * the reaper cannot take it back between the check and the claim. A lease is a use of the sandbox. Only the run
     * kinds that the sandbox's kind drives are offered ({@link AgentSandboxKind#runs}).
     */
    public Optional<AgentRunLease> lease(LiveSandbox sandbox) {
        Set<AgentRunKind> kinds = sandbox.kind().runs();
        if (kinds.isEmpty()) return Optional.empty();
        return Objects.requireNonNull(transactions.execute(status -> {
            if (sandboxes
                    .lockLive(sandbox.id(), sandbox.workspaceId(), sandbox.subjectKey())
                    .isEmpty()) return Optional.<AgentRunLease>empty();
            var lease = runs.claimSubject(sandbox.workspaceId(), sandbox.subjectKey(), kinds);
            if (lease.isPresent()) sandboxes.used(sandbox.id(), sandbox.workspaceId());
            return lease;
        }));
    }

    /**
     * Takes back the subject's sandbox now, as an erasure or a change of access to the subject does. Its token stops
     * at once; its claim goes now, or at the reaper's next tick if the API refuses.
     */
    public void release(long workspaceId, String subjectKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("A sandbox is released outside a transaction, before its claim is deleted");
        Objects.requireNonNull(transactions.execute(status -> sandboxes.release(workspaceId, subjectKey)))
                .ifPresent(this::deleteClaim);
    }

    /** Deletes a {@code DELETING} sandbox's claim, then its row. A failure leaves both for the reaper's next tick. */
    void deleteClaim(AgentSandboxRepository.SandboxRow row) {
        claims.delete(row.getClaimName());
        transactions.executeWithoutResult(status -> sandboxes.deleteGone(row.getId(), row.getWorkspaceId()));
    }

    /** The gateway URL with its host as an address: the sandbox network policy allows no DNS. */
    private String gatewayAddress() {
        if (gatewayUrl.isEmpty())
            throw new IllegalStateException("A sandbox claim needs hephaestus.agent.sandbox.gateway-url");
        URI url = URI.create(gatewayUrl);
        String address = resolve.apply(url.getHost());
        String host = address.contains(":") ? "[" + address + "]" : address;
        return url.getScheme() + "://" + host + (url.getPort() < 0 ? "" : ":" + url.getPort());
    }

    private static LiveSandbox live(AgentSandboxRepository.SandboxRow row) {
        return new LiveSandbox(
                row.getId(), row.getWorkspaceId(), row.getSubjectKey(), AgentSandboxKind.valueOf(row.getKind()));
    }

    private record Requested(AgentSandboxRepository.SandboxRow row, boolean created) {}
}
