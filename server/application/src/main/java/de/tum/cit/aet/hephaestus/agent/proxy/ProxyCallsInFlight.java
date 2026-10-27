package de.tum.cit.aet.hephaestus.agent.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingLimits;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The model calls this worker's proxy has in flight for each billed attempt. The proxy records a call's usage after it
 * relayed the whole response, but a Heph host reads the end of an answer from the stream and can end its turn first.
 * The fence on the turn row then drops the call as late. Thus, a turn's end seals its attempt and waits here for its
 * calls. A turn that ends without its answer also cancels them: a provider that sends nothing would otherwise hold the
 * end. For a Heph turn, it also remembers the attempt's last model call that failed, which the turn records when it
 * fails.
 */
@Component
@ConditionalOnWorkerRole
public class ProxyCallsInFlight {

    /**
     * How long an attempt stays sealed. A call authenticated while its attempt still held the lease reaches
     * {@link #begin} within moments; later calls fail authentication, because the end released the lease.
     */
    private static final Duration SEALED_FOR = Duration.ofMinutes(10);

    /** How long an attempt's end waits for its calls to record their usage. */
    public static final Duration SETTLE_WAIT = Duration.ofSeconds(10);

    /**
     * How long an attempt's last failed call is remembered. Its end reads and forgets it; this bounds an attempt that
     * ends on another worker and so is longer than the longest turn.
     */
    private static final Duration FAILURE_KEPT_FOR =
            Duration.ofSeconds(AgentBindingLimits.MAX_TIMEOUT_SECONDS).plus(SEALED_FOR);

    private record Attempt(UUID sourceId, int number) {}

    /** One call in flight; closing it records that the call's usage is written. */
    public interface Call extends AutoCloseable {
        /** Completes when the attempt's end cancels the call. */
        Mono<Void> cancelled();

        /** Whether the attempt's end cancelled the call, or had sealed the attempt before the call began. */
        boolean isCancelled();

        /** Records that the call failed; a Heph turn's attempt remembers it as its last failed call. */
        void failed(MentorTurn.FailedCall failure);

        /** How the call failed; {@code null} when it did not. */
        MentorTurn.@Nullable FailedCall failure();

        @Override
        void close();
    }

    /** One call's cancellation: the signal the proxy's upstream read stops on, and whether it was sent. */
    private static final class Cancellation {
        final Sinks.Empty<Void> signal = Sinks.empty();
        volatile boolean sent;

        void send() {
            sent = true;
            signal.tryEmitEmpty();
        }
    }

    /** Guards {@link #sealed} as well. */
    private final Map<Attempt, List<Cancellation>> calls = new HashMap<>();

    /** Attempts whose end began: a call of one of them is cancelled before it reaches the provider. */
    private final Cache<Attempt, Boolean> sealed =
            Caffeine.newBuilder().expireAfterWrite(SEALED_FOR).build();

    /** The last failed call of each Heph turn attempt that had one. */
    private final Cache<Attempt, MentorTurn.FailedCall> lastFailed =
            Caffeine.newBuilder().expireAfterWrite(FAILURE_KEPT_FOR).build();

    public Call begin(ProxyRouting.BilledAttempt billed) {
        Attempt attempt = new Attempt(billed.sourceId(), billed.number());
        Cancellation cancel = new Cancellation();
        boolean hephTurn = billed.sourceType() == UsageSourceType.MENTOR_TURN;
        synchronized (calls) {
            if (sealed.getIfPresent(attempt) != null) {
                cancel.send();
                return call(attempt, cancel, hephTurn);
            }
            calls.computeIfAbsent(attempt, key -> new ArrayList<>()).add(cancel);
        }
        return call(attempt, cancel, hephTurn);
    }

    private Call call(Attempt attempt, Cancellation cancel, boolean hephTurn) {
        return new Call() {
            private MentorTurn.@Nullable FailedCall failure;

            @Override
            public Mono<Void> cancelled() {
                return cancel.signal.asMono();
            }

            @Override
            public boolean isCancelled() {
                return cancel.sent;
            }

            @Override
            public void failed(MentorTurn.FailedCall failed) {
                failure = failed;
                if (hephTurn) lastFailed.put(attempt, failed);
            }

            @Override
            public MentorTurn.@Nullable FailedCall failure() {
                return failure;
            }

            @Override
            public void close() {
                synchronized (calls) {
                    List<Cancellation> open = calls.get(attempt);
                    if (open != null && open.remove(cancel) && open.isEmpty()) {
                        calls.remove(attempt);
                    }
                    calls.notifyAll();
                }
            }
        };
    }

    /**
     * Seals the attempt, at once for every call: a call that begins after this is cancelled before it reaches the
     * provider. With {@code cancel}, the calls already in flight are cancelled too; each still records what it
     * received.
     */
    public void seal(UUID sourceId, int number, boolean cancel) {
        Attempt attempt = new Attempt(sourceId, number);
        List<Cancellation> open;
        synchronized (calls) {
            sealed.put(attempt, Boolean.TRUE);
            open = cancel ? List.copyOf(calls.getOrDefault(attempt, List.of())) : List.of();
        }
        open.forEach(Cancellation::send);
    }

    /**
     * The end of an attempt, whoever ends it: seals it, cancels the calls in flight when {@code cancel}, and waits up
     * to {@link #SETTLE_WAIT} for each call to record the usage it received.
     *
     * @return {@code false} when a call was still in flight after the wait; its usage may go unbilled
     */
    public boolean settle(UUID sourceId, int number, boolean cancel) {
        seal(sourceId, number, cancel);
        return awaitNone(sourceId, number, SETTLE_WAIT);
    }

    /**
     * The attempt's last model call that failed. The attempt's end reads it after {@link #settle}, and forgets it
     * once the end commits: an end that rolls back is repeated and must read it again.
     *
     * @return {@code null} when no call of the attempt failed on this worker
     */
    public MentorTurn.@Nullable FailedCall lastFailed(UUID sourceId, int number) {
        return lastFailed.getIfPresent(new Attempt(sourceId, number));
    }

    /** Forgets the attempt's last failed call once its end committed ({@link #lastFailed}). */
    public void forgetFailed(UUID sourceId, int number) {
        lastFailed.invalidate(new Attempt(sourceId, number));
    }

    /**
     * Waits until no call of the attempt is in flight.
     *
     * @return {@code false} when a call was still in flight after {@code timeout}
     */
    public boolean awaitNone(UUID sourceId, int number, Duration timeout) {
        Attempt attempt = new Attempt(sourceId, number);
        long until = System.nanoTime() + timeout.toNanos();
        synchronized (calls) {
            long remaining;
            while (calls.containsKey(attempt)) {
                remaining = until - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(calls, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }
}
