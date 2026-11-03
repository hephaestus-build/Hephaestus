package de.tum.cit.aet.hephaestus.integration.core.webhook;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounds the heap that large deliveries hold at once, so that many of them cannot end the JVM and lose every delivery
 * in flight. It has two parts, because they differ in who can reach them. A body costs its own size while it arrives,
 * counted as its bytes arrive, so a sender that declares a large body and sends nothing holds nothing. Its JSON tree and
 * its message cost several times that, and only a delivery with a valid signature reaches them.
 *
 * <p>The parts are separate so that deliveries waiting to parse never starve on bytes that are held by other deliveries
 * waiting to parse. A small delivery is not counted: it never waits, which a provider with a short response window
 * needs, and most deliveries are small.
 */
public final class WebhookInFlightBudget {

    private static final int KIB = 1024;

    /** At or below this, a delivery costs too little to count. */
    static final long SMALL_BODY_BYTES = 256L * KIB;

    /** What a body costs after it has arrived. The tree of a body of many small objects is the largest part. */
    static final long PUBLISH_HEAP_PER_BODY_BYTE = 6;

    /** The heap that the application itself holds at rest and that small deliveries share. */
    private static final long RESERVED_HEAP_BYTES = 128L * KIB * KIB;

    /** Short, so that a delivery that waits and then publishes still answers inside the provider's window. */
    private static final Duration WAIT = Duration.ofMillis(1500);

    private final Pool arriving;
    private final Pool publishing;
    private final Duration wait;

    WebhookInFlightBudget(long arrivingBytes, long publishingBytes, Duration wait) {
        this.arriving = new Pool(arrivingBytes);
        this.publishing = new Pool(publishingBytes);
        this.wait = wait;
    }

    public static WebhookInFlightBudget forHeap(long maxHeapBytes) {
        long budget = Math.max(0, maxHeapBytes - RESERVED_HEAP_BYTES);
        return new WebhookInFlightBudget(budget * 2 / 5, budget * 3 / 5, WAIT);
    }

    /** For the bytes of one body while they arrive. */
    Holding forArriving() {
        return new Holding(arriving, wait);
    }

    /** For one delivery with a valid signature, while it is parsed and stored. */
    Holding forPublishing() {
        return new Holding(publishing, wait);
    }

    private static final class Pool {
        private final Semaphore kibibytes;
        private final int total;

        private Pool(long bytes) {
            this.total = Math.toIntExact(Math.max(1, bytes / KIB));
            this.kibibytes = new Semaphore(total);
        }
    }

    /** What one delivery holds of a pool. Closing it returns everything, whatever happened to the delivery. */
    static final class Holding implements AutoCloseable {
        private final Pool pool;
        private final Duration wait;
        private long bytes;
        private int kibibytes;
        private boolean refused;
        private long waitedNanos;

        private Holding(Pool pool, Duration wait) {
            this.pool = pool;
            this.wait = wait;
        }

        /**
         * Adds to what this delivery holds. More than the whole pool holds the whole pool, so a delivery larger than
         * the budget still proceeds alone.
         *
         * @return whether it fits within what is left of this delivery's one wait; once it did not, it is refused
         */
        boolean add(long moreBytes) {
            bytes += moreBytes;
            int needed = (int) Math.min(pool.total, Math.ceilDiv(bytes, KIB)) - kibibytes;
            if (needed <= 0) {
                return true;
            }
            long started = System.nanoTime();
            try {
                long left = Math.max(0, wait.toNanos() - waitedNanos);
                if (pool.kibibytes.tryAcquire(needed, left, TimeUnit.NANOSECONDS)) {
                    kibibytes += needed;
                    waitedNanos += System.nanoTime() - started;
                    return true;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            refused = true;
            return false;
        }

        boolean refused() {
            return refused;
        }

        @Override
        public void close() {
            pool.kibibytes.release(kibibytes);
            kibibytes = 0;
        }
    }
}
