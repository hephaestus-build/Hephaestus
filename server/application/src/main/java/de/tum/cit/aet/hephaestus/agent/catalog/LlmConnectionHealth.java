package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A circuit breaker for each provider connection, on this process. A connection whose calls stall or fail with a
 * server error trips it; for {@link #OPEN_FOR}, new calls and new Heph turns on that connection fail at once instead of
 * waiting for the provider. Then one call probes the provider, and its result closes the breaker or opens it again.
 * The shared registry publishes {@code resilience4j_circuitbreaker_*} for each breaker, named {@code llm-connection-*}.
 */
@Component
public class LlmConnectionHealth {

    private static final Logger log = LoggerFactory.getLogger(LlmConnectionHealth.class);

    /** How long a tripped connection refuses calls before one probes it. */
    public static final Duration OPEN_FOR = Duration.ofSeconds(60);

    /** The failures counted are those of the last 5 minutes: a stalled call takes the whole idle bound to fail. */
    private static final int WINDOW_SECONDS = 300;

    private static final String PREFIX = "llm-connection-";

    private final CircuitBreakerRegistry registry;
    private final CircuitBreakerConfig config;

    @Autowired
    public LlmConnectionHealth(CircuitBreakerRegistry registry) {
        this(registry, OPEN_FOR);
    }

    LlmConnectionHealth(CircuitBreakerRegistry registry, Duration openFor) {
        this.registry = registry;
        this.config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(WINDOW_SECONDS)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                // A model call can take minutes; only its outcome counts, never its duration.
                .slowCallDurationThreshold(Duration.ofDays(1))
                .waitDurationInOpenState(openFor)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();
        registry.getEventPublisher().onEntryAdded(added -> {
            CircuitBreaker breaker = added.getAddedEntry();
            if (breaker.getName().startsWith(PREFIX)) {
                breaker.getEventPublisher()
                        .onStateTransition(event -> log.warn(
                                "LLM connection breaker changed: name={}, transition={}",
                                event.getCircuitBreakerName(),
                                event.getStateTransition()));
            }
        });
    }

    /** A call the breaker let through; report exactly one outcome. */
    public interface Permit {
        /** The provider answered. */
        void succeeded();

        /** The provider stalled, refused the connection or failed with a server error. */
        void failed(Throwable cause);

        /** The outcome says nothing about the provider, for example a client that went away or a request error. */
        void released();
    }

    /** @return {@code null} when the connection's breaker refuses the call */
    public @Nullable Permit acquire(@Nullable FundingSource scope, @Nullable Long connectionId) {
        CircuitBreaker breaker = breaker(scope, connectionId);
        if (!breaker.tryAcquirePermission()) {
            return null;
        }
        long started = System.nanoTime();
        return new Permit() {
            @Override
            public void succeeded() {
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            }

            @Override
            public void failed(Throwable cause) {
                breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, cause);
            }

            @Override
            public void released() {
                breaker.releasePermission();
            }
        };
    }

    /** Whether the connection refuses calls now; a breaker that waits for its probe does not. */
    public boolean isOpen(@Nullable FundingSource scope, @Nullable Long connectionId) {
        return breaker(scope, connectionId).getState() == CircuitBreaker.State.OPEN;
    }

    private CircuitBreaker breaker(@Nullable FundingSource scope, @Nullable Long connectionId) {
        return registry.circuitBreaker(PREFIX + scope + "-" + connectionId, config);
    }
}
