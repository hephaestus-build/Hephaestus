package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.SandboxJwt;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    /** Keeps a dispatched sandbox's shutdown inside its token's longest life while the request reaches the server. */
    private static final Duration SHUTDOWN_MARGIN = Duration.ofMinutes(1);

    private final AgentSandboxRepository sandboxes;
    private final AgentRuns runs;
    private final SandboxClaims claims;
    private final AttemptTokenIssuer tokens;
    private final TransactionTemplate transactions;
    private final String gatewayUrl;
    private final UnaryOperator<String> resolve;
    private final MeterRegistry meters;

    /** @param resolve the address of a host name, as the server's resolver answers it */
    AgentSandboxes(
            AgentSandboxRepository sandboxes,
            AgentRuns runs,
            SandboxClaims claims,
            AttemptTokenIssuer tokens,
            TransactionTemplate transactions,
            String gatewayUrl,
            UnaryOperator<String> resolve,
            MeterRegistry meters) {
        this.sandboxes = sandboxes;
        this.runs = runs;
        this.claims = claims;
        this.tokens = tokens;
        this.transactions = transactions;
        this.gatewayUrl = gatewayUrl;
        this.resolve = resolve;
        this.meters = meters;
        // Registered at start, so an alert on a count that is still zero has a series to read.
        for (Revocation reason : Revocation.values())
            meters.counter(AgentMetrics.AGENT_SANDBOX_REVOKED, "reason", reason.value());
    }

    /** Why the server takes a live sandbox back before the reaper would. */
    public enum Revocation {
        /** Its network policy did not hold in time, so its driver ran nothing (isolation gate G6). */
        UNISOLATED;

        /** The reason as the event and the counter name it. */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A live sandbox: the caller of a gateway route, or the sandbox that serves a subject now. */
    public record LiveSandbox(UUID id, long workspaceId, String subjectKey, AgentSandboxKind kind) {
        /** The name of its claim, Sandbox and Pod, as its log records and the cluster name it. */
        public String claimName() {
            return AgentSandboxes.claimName(id);
        }

        /** The review subject's identifier; another kind has no review. */
        public @Nullable String reviewId() {
            return kind == AgentSandboxKind.REVIEW && subjectKey.startsWith("review:")
                    ? subjectKey.substring("review:".length())
                    : null;
        }
    }

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
        // A token that outlived its signing key would fail on every gateway call after its server restarted.
        if (shutdownAt.isAfter(Instant.now().plus(tokens.longestSandboxToken())))
            throw new IllegalArgumentException("shutdownAt must not be later than the longest sandbox token");
        UUID id = UUID.randomUUID();
        String claimName = claimName(id);
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
            SandboxEvents.event(log.atInfo(), "agent.sandbox.requested", row).log("Sandbox requested");
        }
        return live(row);
    }

    /**
     * The subject's live sandbox, or a new one that ends no later than its token can: the reaper takes it back once its
     * subject has no run left, long before. Call it outside a transaction.
     */
    public LiveSandbox ensure(long workspaceId, String subjectKey, AgentSandboxKind kind) {
        return ensure(
                workspaceId,
                subjectKey,
                kind,
                Instant.now().plus(tokens.longestSandboxToken()).minus(SHUTDOWN_MARGIN));
    }

    /** Physical slots of this kind in every workspace, including sandboxes awaiting deletion. */
    public int live(AgentSandboxKind kind) {
        return sandboxes.countLive(kind.name());
    }

    /** Whether the subject has a live sandbox. */
    public boolean hasLive(long workspaceId, String subjectKey) {
        return sandboxes.findLive(workspaceId, subjectKey).isPresent();
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

    /** The run's first start, and whether its current physical sandbox served a claim before this one. */
    public record RunStart(Instant startedAt, boolean cold) {}

    public Optional<RunStart> start(LiveSandbox sandbox, AgentRunFence run) {
        if (sandbox.workspaceId() != run.workspaceId()) return Optional.empty();
        return sandboxes
                .findRunStart(sandbox.id(), sandbox.workspaceId(), sandbox.subjectKey(), run.runId(), run.epoch())
                .map(start -> new RunStart(start.getStartedAt(), start.getCold()));
    }

    /** Holds a live sandbox until the current transaction commits. */
    public boolean hold(LiveSandbox sandbox) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Holding a sandbox requires a transaction");
        return sandboxes
                .lockLive(sandbox.id(), sandbox.workspaceId(), sandbox.subjectKey())
                .isPresent();
    }

    /**
     * Leases the subject's next run of these kinds to its sandbox, while the sandbox is live: one transaction holds the
     * sandbox, so the reaper cannot take it back between the check and the claim. {@code claimed} moves the run's
     * domain row in the same transaction; when it answers false, its kind ended or requeued the run, and the sandbox
     * gets none. A lease is a use of the sandbox.
     */
    public Optional<AgentRunLease> lease(
            LiveSandbox sandbox, Set<AgentRunKind> kinds, Predicate<AgentRunLease> claimed) {
        return lease(sandbox, kinds, () -> {}, claimed);
    }

    /** The domain fence precedes the sandbox, run and domain row locks. */
    public Optional<AgentRunLease> lease(
            LiveSandbox sandbox, Set<AgentRunKind> kinds, Runnable beforeWrite, Predicate<AgentRunLease> claimed) {
        if (kinds.isEmpty()) return Optional.empty();
        return Objects.requireNonNull(transactions.execute(status -> {
            beforeWrite.run();
            if (sandboxes
                    .lockLive(sandbox.id(), sandbox.workspaceId(), sandbox.subjectKey())
                    .isEmpty()) return Optional.<AgentRunLease>empty();
            var lease = runs.claimSubject(sandbox.workspaceId(), sandbox.subjectKey(), kinds);
            if (lease.isEmpty()) return lease;
            var leased = lease.get();
            var previous = MDC.getCopyOfContextMap();
            try {
                MDC.put(StructuredLogKeys.SANDBOX_NAME, sandbox.claimName());
                MDC.put(StructuredLogKeys.SUBJECT_KEY, sandbox.subjectKey());
                MDC.put(StructuredLogKeys.WORKSPACE_ID, Long.toString(sandbox.workspaceId()));
                MDC.put(StructuredLogKeys.RUN_ID, leased.runId().toString());
                MDC.put(StructuredLogKeys.RUN_EPOCH, Integer.toString(leased.epoch()));
                MDC.remove(StructuredLogKeys.JOB_ID);
                MDC.remove(StructuredLogKeys.JOB_ATTEMPT);
                String reviewId = sandbox.reviewId();
                if (reviewId == null) MDC.remove(StructuredLogKeys.REVIEW_ID);
                else MDC.put(StructuredLogKeys.REVIEW_ID, reviewId);
                if (!claimed.test(leased)) return Optional.<AgentRunLease>empty();
                sandboxes.used(sandbox.id(), sandbox.workspaceId());
                log.atInfo()
                        .addKeyValue("event.name", "agent.sandbox.leased")
                        .addKeyValue(StructuredLogKeys.SANDBOX_NAME, sandbox.claimName())
                        .addKeyValue(StructuredLogKeys.SUBJECT_KEY, sandbox.subjectKey())
                        .addKeyValue(StructuredLogKeys.WORKSPACE_ID, sandbox.workspaceId())
                        .addKeyValue(StructuredLogKeys.RUN_ID, leased.runId())
                        .addKeyValue(StructuredLogKeys.RUN_EPOCH, leased.epoch())
                        .log("Sandbox leased its subject's next run");
                return lease;
            } finally {
                if (previous == null) MDC.clear();
                else MDC.setContextMap(previous);
            }
        }));
    }

    /** Frees one physical conversation slot for queued work, oldest idle first; failed deletion stays owned. */
    public boolean releaseIdleConversation() {
        var row = Objects.requireNonNull(transactions.execute(status -> sandboxes.retireIdleConversation()));
        if (row.isEmpty()) return false;
        meters.counter(AgentMetrics.AGENT_SANDBOX_REAPED, "reason", "idle").increment();
        SandboxEvents.event(log.atInfo(), "agent.sandbox.taken_back", row.get())
                .addKeyValue("sandbox.reason", "idle")
                .log("Idle conversation sandbox retired to make room for queued work");
        deleteClaim(row.get());
        return true;
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

    /** Deletes the physical sandbox named by a copy receipt; a newer sandbox of the subject stays. */
    public void release(long workspaceId, UUID sandboxId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("A sandbox is released outside a transaction, before its claim is deleted");
        var row = Objects.requireNonNull(transactions.execute(status -> sandboxes.releaseCopy(sandboxId, workspaceId)));
        if (row.isPresent()) {
            deleteClaim(row.get());
        } else {
            if (sandboxes.existsById(sandboxId))
                throw new IllegalArgumentException("The sandbox does not belong to the copy's workspace");
            // Workspace purge removes the row first. The copy receipt still names its physical sandbox.
            claims.delete(claimName(sandboxId));
        }
    }

    /**
     * Takes a sandbox back in the caller's transaction: its token stops when the transaction commits, and the reaper
     * deletes its claim at its next tick. Nothing here calls the API, so the transaction holds no network call.
     */
    public void revoke(LiveSandbox sandbox, Revocation reason) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("A sandbox is revoked in the transaction of the change that revokes it");
        sandboxes.releaseSandbox(sandbox.id(), sandbox.workspaceId()).ifPresent(row -> {
            meters.counter(AgentMetrics.AGENT_SANDBOX_REVOKED, "reason", reason.value())
                    .increment();
            SandboxEvents.event(log.atWarn(), "agent.sandbox.taken_back", row)
                    .addKeyValue("sandbox.reason", reason.value())
                    .log("Sandbox revoked; its claim is deleted next");
        });
    }

    /** Deletes a {@code DELETING} sandbox's claim, then its row. A failure leaves both for the reaper's next tick. */
    void deleteClaim(AgentSandboxRepository.SandboxRow row) {
        claims.delete(row.getClaimName());
        transactions.executeWithoutResult(status -> sandboxes.deleteGone(row.getId(), row.getWorkspaceId()));
    }

    /** A sandbox's claim name, which is also its Sandbox's and its Pod's. */
    static String claimName(UUID sandboxId) {
        return "agent-" + sandboxId;
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
