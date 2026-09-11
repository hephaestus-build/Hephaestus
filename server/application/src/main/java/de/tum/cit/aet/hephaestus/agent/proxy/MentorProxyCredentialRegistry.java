package de.tum.cit.aet.hephaestus.agent.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mints and validates proxy-scoped bearer tokens for the mentor's interactive sandbox, which is a
 * reused developer-attached session rather than an {@code agent_job} row and so cannot use the
 * DB-backed job token. A token grants exactly what a job token grants: the proxy will resolve ONE
 * connection's credential for the holder, nothing else. Process-local, like the interactive sandbox
 * registry it shadows.
 *
 * <p>A mentor token has no terminal event of its own, so the sandbox adapter {@link #revoke(UUID)}s it
 * on any dispose path and {@link #TTL} is the backstop for a hard crash. The TTL must therefore expire
 * on the wall clock rather than on read: a token whose sandbox never attached is never presented, so a
 * check-on-read would never fire and both index entries would leak for the worker's lifetime.
 *
 * <p>The credential is per SESSION and a session outlives many turns, so it names no billing target on
 * its own. A turn {@link #bindTurn binds} its {@link MentorTurnMeter} for the window in which it holds
 * the per-sandbox lock and {@link #unbindTurn unbinds} at the end, so at most one turn is bound at any
 * instant and {@link #validate} can report the call's billing target. A call that authenticates after
 * its turn unbound is dropped by the turn-id fence in {@link #accumulate} rather than charged to
 * whoever is bound now.
 *
 * <p>The meter is only the budget gate's read model; the billed record is the turn's
 * {@code chat_message} row, so a worker that dies mid-turn still bills what the proxy recorded.
 *
 * <p>Overshoot bound and what it does not cover:
 * {@code docs/decisions/0026-per-purpose-agent-bindings-and-llm-governance.md}.
 */
@Component
public class MentorProxyCredentialRegistry {

    private static final Logger log = LoggerFactory.getLogger(MentorProxyCredentialRegistry.class);

    private static final Duration TTL = Duration.ofHours(12);

    /** Bounds a pathological mint loop; far above any real concurrent-sandbox working set. */
    private static final int MAX_ENTRIES = 10_000;

    private final Cache<String, Entry> byTokenHash;
    private final Map<UUID, String> tokenHashBySession = new ConcurrentHashMap<>();

    /** A lookup miss here IS the fence: a call whose turn has ended is dropped, not re-targeted. */
    private final Map<UUID, MentorTurnMeter> meterByTurn = new ConcurrentHashMap<>();

    public MentorProxyCredentialRegistry() {
        this(Ticker.systemTicker());
    }

    MentorProxyCredentialRegistry(Ticker ticker) {
        this.byTokenHash = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(TTL)
                .ticker(ticker)
                // evictionListener, not removalListener: synchronous, so the reverse index can never
                // outlive the token it points at. The removes are value-matching so a re-mint for the same
                // session is left alone when the stale entry is finally evicted.
                .<String, Entry>evictionListener((hash, entry, cause) -> {
                    if (entry != null) {
                        tokenHashBySession.remove(entry.sessionId(), hash);
                        MentorTurnMeter bound = entry.currentTurn().getAndSet(null);
                        if (bound != null) {
                            meterByTurn.remove(bound.turnId(), bound);
                        }
                    }
                })
                .build();
    }

    /** Non-secret catalog route granted to one mentor sandbox. */
    public record Route(
            String apiProtocol,
            String baseUrl,
            @Nullable FundingSource connectionScope,
            @Nullable Long connectionId,
            @Nullable Long modelId,
            @Nullable Long workspaceId) {}

    /**
     * @param currentTurn the meter calls on this token are billed to right now; {@code null} between
     *     turns. Mutable because the routing is fixed for the session's life while the turn is not.
     */
    private record Entry(
            UUID sessionId,
            String apiProtocol,
            String baseUrl,
            @Nullable FundingSource connectionScope,
            @Nullable Long connectionId,
            @Nullable Long modelId,
            @Nullable Long workspaceId,
            AtomicReference<@Nullable MentorTurnMeter> currentTurn) {}

    /**
     * @param sessionId the sandbox's {@code InteractiveSandboxSpec#sessionId}, which {@link
     *     #revoke(UUID)} uses to find this token again at teardown
     */
    public String mint(UUID sessionId, Route route) {
        String token = AgentJob.generateJobToken();
        String hash = AgentJob.computeTokenHash(token);
        byTokenHash.put(
                hash,
                new Entry(
                        sessionId,
                        route.apiProtocol(),
                        route.baseUrl(),
                        route.connectionScope(),
                        route.connectionId(),
                        route.modelId(),
                        route.workspaceId(),
                        new AtomicReference<>()));
        // Drop the orphaned token now rather than leaving two live credentials for one sandbox.
        String previous = tokenHashBySession.put(sessionId, hash);
        if (previous != null && !previous.equals(hash)) {
            byTokenHash.invalidate(previous);
        }
        return token;
    }

    /**
     * Empty when the token is unknown or expired. The routing carries what the bound turn has already
     * spent, which is what makes a turn's own in-flight spend visible to {@code ProxyBudgetGate}. A
     * call arriving between turns names no turn and {@code LlmProxyController} refuses it, because
     * nothing would record its tokens.
     */
    public Optional<ProxyRouting> validate(String token) {
        Entry entry = byTokenHash.getIfPresent(AgentJob.computeTokenHash(token));
        if (entry == null) {
            return Optional.empty();
        }
        MentorTurnMeter turn = entry.currentTurn().get();
        return Optional.of(new ProxyRouting(
                // Names the session, because everything keyed on the principal — the gateway's rate
                // limit, the proxy's MDC — would otherwise treat every session in the instance as one
                // caller and let one workspace's conversation throttle another's.
                "mentor-session:" + entry.sessionId(),
                entry.apiProtocol(),
                entry.baseUrl(),
                entry.connectionScope(),
                entry.connectionId(),
                entry.modelId(),
                entry.workspaceId(),
                turn == null
                        ? null
                        : new ProxyRouting.BilledAttempt(
                                LlmUsageSourceType.MENTOR_TURN,
                                turn.turnId(),
                                // A turn never retries, so there is only ever attempt 0 of a given turn id.
                                0,
                                turn.spentUsd(),
                                null)));
    }

    /**
     * Starts billing this session's calls to {@code meter}. Replaces any previously bound turn rather
     * than refusing — the caller holds the per-sandbox lock, so a leftover binding means an earlier
     * turn failed to unbind — and drops the displaced meter from the turn index in the same step.
     *
     * @return false when the session has no live credential. The turn then has no billing target, so
     *     the proxy refuses its calls rather than serving them unbilled.
     */
    public boolean bindTurn(UUID sessionId, MentorTurnMeter meter) {
        String hash = tokenHashBySession.get(sessionId);
        Entry entry = hash == null ? null : byTokenHash.getIfPresent(hash);
        if (entry == null) {
            return false;
        }
        meterByTurn.put(meter.turnId(), meter);
        MentorTurnMeter displaced = entry.currentTurn().getAndSet(meter);
        if (displaced != null && displaced != meter) {
            meterByTurn.remove(displaced.turnId(), displaced);
            log.warn(
                    "Mentor turn {} was still bound to sandbox session {} when turn {} started — "
                            + "its later calls will go unbilled rather than being charged to the new turn",
                    displaced.turnId(),
                    sessionId,
                    meter.turnId());
        }
        return true;
    }

    /**
     * Idempotent, and value-matching on both indexes so a late unbind from an already-displaced turn
     * cannot detach the turn running now. Does not clear what the meter observed — the terminal write
     * still reads it from the reference it holds.
     */
    public void unbindTurn(UUID sessionId, MentorTurnMeter meter) {
        meterByTurn.remove(meter.turnId(), meter);
        String hash = tokenHashBySession.get(sessionId);
        Entry entry = hash == null ? null : byTokenHash.getIfPresent(hash);
        if (entry != null) {
            entry.currentTurn().compareAndSet(meter, null);
        }
    }

    /**
     * Mirrors one served call's tokens onto the turn that authenticated it, so the gate sees them on
     * the next call. Only ever call this for a call already recorded on the turn's {@code chat_message}
     * row; that row is the billing record and this is derived from it, never the reverse.
     *
     * @return false when the turn is no longer bound. Nothing is lost — the money is already on the
     *     row — but the tokens must not land on whatever turn IS bound, which would gate a different
     *     turn for spend it never incurred.
     */
    public boolean accumulate(UUID turnId, ProxyTokenUsage usage) {
        MentorTurnMeter meter = meterByTurn.get(turnId);
        if (meter == null) {
            return false;
        }
        meter.add(usage);
        return true;
    }

    /** Idempotent; a session that never minted a token (e.g. it lost the attach race) is a no-op. */
    public void revoke(UUID sessionId) {
        String hash = tokenHashBySession.remove(sessionId);
        if (hash != null) {
            Entry entry = byTokenHash.getIfPresent(hash);
            if (entry != null) {
                MentorTurnMeter bound = entry.currentTurn().getAndSet(null);
                if (bound != null) {
                    meterByTurn.remove(bound.turnId(), bound);
                }
            }
            byTokenHash.invalidate(hash);
        }
    }

    /** Test seam: Caffeine defers expiry work, so a {@link Ticker} advance alone evicts nothing. */
    void runPendingEviction() {
        byTokenHash.cleanUp();
    }

    int trackedSessions() {
        return tokenHashBySession.size();
    }

    int boundTurns() {
        return meterByTurn.size();
    }
}
