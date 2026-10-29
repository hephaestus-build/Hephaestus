package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounds loaded composition inputs independently of the total local-attempt limit. */
@Component
@ConditionalOnWorkerRole
public class CompositionCapacity {
    private final Semaphore slots;

    public CompositionCapacity(@Value("${hephaestus.agent.max-active-compositions:4}") int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("max-active-compositions must be positive");
        slots = new Semaphore(maximum, true);
    }

    Slot acquire(Instant deadline) {
        try {
            long nanos = Math.max(0, Duration.between(Instant.now(), deadline).toNanos());
            if (!slots.tryAcquire(nanos, TimeUnit.NANOSECONDS))
                throw new CompositionHandedOnException(CompositionStop.CAPACITY, null);
            return new Slot();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CompositionHandedOnException(CompositionStop.CAPACITY, interrupted);
        }
    }

    final class Slot implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) slots.release();
        }
    }
}
