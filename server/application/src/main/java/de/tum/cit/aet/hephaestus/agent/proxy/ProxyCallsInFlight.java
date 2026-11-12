package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.practice.PracticeRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounds the bodies this proxy holds for each attempt. Leases and call outcomes live in PostgreSQL. */
@Component
@ConditionalOnAgentRuntime
public class ProxyCallsInFlight {
    private static final int SESSION_HEAP_MIB = 3;
    public static final int REVIEW_CALLS = 2 * (PracticeRunnerProfile.HEAP_MIB / SESSION_HEAP_MIB);
    static final int TURN_CALLS = 2;

    private record Attempt(UUID sourceId, int number) {}

    public interface Call extends AutoCloseable {
        /** Keeps the first failure until this call writes its durable completion. */
        void failed(MentorTurn.FailedCall failure);

        MentorTurn.@Nullable FailedCall failure();

        boolean isOverCap();

        @Override
        void close();
    }

    private final Map<Attempt, Integer> calls = new HashMap<>();
    private final int reviewCalls;

    @Autowired
    ProxyCallsInFlight() {
        this(REVIEW_CALLS);
    }

    public ProxyCallsInFlight(int reviewCalls) {
        this.reviewCalls = reviewCalls;
    }

    public Call begin(ProxyRouting.BilledAttempt billed) {
        Attempt attempt = new Attempt(billed.sourceId(), billed.number());
        boolean overCap;
        synchronized (calls) {
            int open = calls.getOrDefault(attempt, 0);
            overCap = open >= cap(billed.sourceType());
            if (!overCap) calls.put(attempt, open + 1);
        }
        return new Call() {
            private final AtomicReference<MentorTurn.@Nullable FailedCall> failure = new AtomicReference<>();
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void failed(MentorTurn.FailedCall failed) {
                failure.compareAndSet(null, failed);
            }

            @Override
            public MentorTurn.@Nullable FailedCall failure() {
                return failure.get();
            }

            @Override
            public boolean isOverCap() {
                return overCap;
            }

            @Override
            public void close() {
                if (overCap || !closed.compareAndSet(false, true)) return;
                synchronized (calls) {
                    int open = calls.getOrDefault(attempt, 0);
                    if (open <= 1) calls.remove(attempt);
                    else calls.put(attempt, open - 1);
                }
            }
        };
    }

    private int cap(UsageSourceType sourceType) {
        return switch (sourceType) {
            case MENTOR_TURN -> TURN_CALLS;
            case PRODUCT_FEEDBACK_DRAFT -> 1;
            default -> reviewCalls;
        };
    }
}
