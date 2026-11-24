package de.tum.cit.aet.hephaestus.integration.core.sync;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;

/** Internal state and persistence adapter behind the runner-facing {@link SyncExecutionHandle}. */
public final class SyncJobHandle implements SyncExecutionHandle {

    /**
     * Write budget: one UPDATE per job per two seconds (~0.5 QPS/job — noise for Postgres even with the
     * executor saturated). Runners deliberately call {@link #progress} far more often than this; the
     * throttle here is what makes that free, and is the reason the SPI tells runners not to self-throttle.
     *
     * <p>Two seconds rather than five because the end-to-end budget stacks: this write, then the hub's
     * 1s coalesce, then the client's 300ms debounce and refetch — ~3.6s worst case to a visible move,
     * which is inside the band where a long-running job still reads as alive. Going sub-second buys
     * nothing perceptually for a job measured in minutes and triples the DB and SSE traffic.
     */
    static final long MIN_WRITE_INTERVAL_SECONDS = 2;

    private static final Duration MIN_WRITE_INTERVAL = Duration.ofSeconds(MIN_WRITE_INTERVAL_SECONDS);

    @FunctionalInterface
    interface ProgressWriter {
        void writeProgress(
                long jobId,
                @Nullable Integer itemsProcessed,
                @Nullable Integer itemsTotal,
                Map<String, Object> progressDetail);
    }

    private final long jobId;
    private final ProgressWriter writer;

    /**
     * Source of "now" for the write-throttle window. Injected so the throttle is testable by advancing a
     * controllable clock rather than sleeping real wall-clock time; {@code Clock.systemUTC()} in production.
     */
    private final Clock clock;

    private volatile boolean cancellationRequested;
    private volatile boolean cancelledReported;
    private volatile boolean warningsReported;

    /**
     * Guards {@link #lastWriteAt}, {@link #pendingWrite} and the buffered progress triple as one unit.
     * The runner thread calls {@link #progress} while the trailing-flush sweep calls
     * {@link #flushIfDue} from the scheduler thread; without this they could interleave into a write
     * that mixes one update's counts with another's detail, or drop a pending flush.
     */
    private final Object writeLock = new Object();

    private Instant lastWriteAt = Instant.EPOCH;

    /**
     * True when the buffered state is newer than what's in the database — i.e. a {@link #progress} call
     * was throttled. The trailing-flush sweep exists for exactly this state: without it, the last
     * update before a quiet period sits in memory until the <em>next</em> call, so a runner that reports
     * a phase boundary and then goes quiet for minutes leaves the UI showing the state before it.
     */
    private boolean pendingWrite;

    @Nullable
    private Integer itemsProcessed;

    @Nullable
    private Integer itemsTotal;

    private Map<String, Object> progressDetail = Map.of();

    @Nullable
    private SyncProgress warningDetail;

    SyncJobHandle(long jobId, ProgressWriter writer, Clock clock) {
        this.jobId = jobId;
        this.writer = writer;
        this.clock = clock;
    }

    @Override
    public boolean isCancellationRequested() {
        return cancellationRequested;
    }

    void refreshCancellation(boolean requested) {
        if (requested) {
            this.cancellationRequested = true;
        }
    }

    @Override
    public void reportCancelled() {
        this.cancelledReported = true;
    }

    boolean cancelledReported() {
        return cancelledReported;
    }

    @Override
    public void reportWarnings() {
        this.warningsReported = true;
    }

    @Override
    public void reportWarnings(SyncProgress detail) {
        synchronized (writeLock) {
            if (warningDetail == null) {
                warningDetail = detail;
            }
            warningsReported = true;
        }
    }

    boolean warningsReported() {
        return warningsReported;
    }

    /**
     * Buffers every update and persists at most once per {@link #MIN_WRITE_INTERVAL_SECONDS} seconds.
     * A throttled update is not lost — it is marked pending and lands via {@link #flushIfDue} once the
     * interval elapses.
     */
    @Override
    public void progress(@Nullable Integer itemsProcessed, @Nullable Integer itemsTotal, SyncProgress progressDetail) {
        Write write = null;
        synchronized (writeLock) {
            this.itemsProcessed = itemsProcessed;
            this.itemsTotal = itemsTotal;
            // Flattened here, at the one boundary between the typed runner-facing contract and the
            // JSONB column, so no runner has to know the wire keys.
            this.progressDetail = progressDetail == null ? Map.of() : progressDetail.toDetail();

            Instant now = clock.instant();
            if (Duration.between(lastWriteAt, now).compareTo(MIN_WRITE_INTERVAL) >= 0) {
                lastWriteAt = now;
                pendingWrite = false;
                write = currentWrite();
            } else {
                pendingWrite = true;
            }
        }
        // Outside the lock: this is a database round-trip, and holding writeLock across it would block
        // the runner thread's next progress call behind the sweep's write (and vice versa).
        if (write != null) {
            persist(write);
        }
    }

    /**
     * A progress write that the database refused, as while a migration holds the job table, stays pending for the next
     * flush. It never reaches the runner: progress is a report about the sync, not part of it.
     */
    private boolean persist(Write write) {
        try {
            write.apply(writer, jobId);
            return true;
        } catch (DataAccessException refused) {
            synchronized (writeLock) {
                pendingWrite = true;
            }
            return false;
        }
    }

    /**
     * Trailing edge of the throttle: writes the buffered update if one was suppressed and the interval
     * has since elapsed. Driven by {@code SyncJobService}'s sweep over locally-owned handles.
     *
     * @return true if a write was performed
     */
    boolean flushIfDue() {
        Write write = null;
        synchronized (writeLock) {
            if (!pendingWrite) {
                return false;
            }
            Instant now = clock.instant();
            if (Duration.between(lastWriteAt, now).compareTo(MIN_WRITE_INTERVAL) < 0) {
                return false;
            }
            lastWriteAt = now;
            pendingWrite = false;
            write = currentWrite();
        }
        return persist(write);
    }

    private Write currentWrite() {
        return new Write(itemsProcessed, itemsTotal, progressDetail);
    }

    /** Immutable snapshot of the buffered triple, so the DB write happens outside {@link #writeLock}. */
    private record Write(
            @Nullable Integer itemsProcessed, @Nullable Integer itemsTotal, Map<String, Object> progressDetail) {
        void apply(ProgressWriter writer, long jobId) {
            writer.writeProgress(jobId, itemsProcessed, itemsTotal, progressDetail);
        }
    }

    @Nullable
    Integer currentItemsProcessed() {
        synchronized (writeLock) {
            return itemsProcessed;
        }
    }

    @Nullable
    Integer currentItemsTotal() {
        synchronized (writeLock) {
            return itemsTotal;
        }
    }

    Map<String, Object> currentProgressDetail() {
        synchronized (writeLock) {
            return progressDetail;
        }
    }

    Map<String, Object> terminalProgressDetail(SyncJobStatus status) {
        synchronized (writeLock) {
            return status == SyncJobStatus.SUCCEEDED_WITH_WARNINGS && warningDetail != null
                    ? warningDetail.toDetail()
                    : progressDetail;
        }
    }
}
