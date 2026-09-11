package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.release.ReleaseCheckClient.Failed;
import de.tum.cit.aet.hephaestus.core.release.ReleaseCheckClient.Found;
import de.tum.cit.aet.hephaestus.core.release.ReleaseCheckClient.NotModified;
import de.tum.cit.aet.hephaestus.core.release.ReleaseStatusDTO.LatestReleaseDTO;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.github.resilience4j.core.IntervalFunction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.util.Version;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the last answer GitHub gave about the newest release and decides when to ask again. State is
 * per process and in memory: a restart starts from {@code NEVER_CHECKED} and the first check runs a
 * minute later, so nothing is worth persisting. Every server replica asks on its own — one request a
 * day each, against a limit of sixty an hour.
 */
@Service
@ConditionalOnServerRole
public class ReleaseCheckService {
    private static final Logger log = LoggerFactory.getLogger(ReleaseCheckService.class);

    private static final Duration CACHE = Duration.ofHours(24);
    private static final IntervalFunction BACKOFF =
            IntervalFunction.ofExponentialBackoff(Duration.ofMinutes(15), 2, CACHE);

    /** An ETag is only meaningful next to the answer it revalidates, so the two travel as one. */
    private record Answer(LatestReleaseDTO latest, @Nullable String etag) {}

    /**
     * Replaced as one value so a read never sees half an update.
     *
     * @param nextCheck           when the next automatic check is due
     * @param retryUntil          the wait GitHub named on a rate limit, which also blocks manual checks
     * @param consecutiveFailures automatic attempts in a row that did not complete; manual attempts
     *                            do not count, so they cannot escalate the backoff
     */
    private record State(
            @Nullable Instant lastAttempt,
            @Nullable Instant lastSuccess,
            @Nullable Instant nextCheck,
            @Nullable Instant retryUntil,
            @Nullable ReleaseCheckFailure failure,
            @Nullable Answer answer,
            int consecutiveFailures) {
        static final State INITIAL = new State(null, null, null, null, null, null, 0);
    }

    private final RunningRelease running;
    private final ReleaseCheckClient client;
    private final Clock clock;
    private final boolean enabled;
    private final AtomicReference<State> state = new AtomicReference<>(State.INITIAL);
    // A lock rather than a monitor: on Java 21 a monitor pins the virtual thread for the whole request.
    private final ReentrantLock checking = new ReentrantLock();

    public ReleaseCheckService(
            RunningRelease running, ReleaseCheckClient client, Clock clock, ReleaseProperties properties) {
        this.running = running;
        this.client = client;
        this.clock = clock;
        this.enabled = properties.checkEnabled();
    }

    public ReleaseStatusDTO status() {
        return status(state.get());
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    @WorkspaceAgnostic("Public release metadata is instance-wide; no tenant row is read or written")
    public void poll() {
        if (due(state.get().nextCheck())) check(false);
    }

    /**
     * An administrator's request: skips the cache, never delays the next automatic check, and only
     * waits out a rate-limit window GitHub named.
     */
    public ReleaseStatusDTO check() {
        return check(true);
    }

    private ReleaseStatusDTO check(boolean manual) {
        if (!applicable()) return status();
        checking.lock();
        try {
            // Read under the lock: the check that held it may have satisfied this tick or opened a window.
            State current = state.get();
            boolean blocked = manual ? !due(current.retryUntil()) : !due(current.nextCheck());
            if (blocked) return status(current);
            String etag = current.answer() == null ? null : current.answer().etag();
            State next = apply(current, client.fetchLatest(etag), clock.instant(), manual);
            state.set(next);
            return status(next);
        } finally {
            checking.unlock();
        }
    }

    private boolean due(@Nullable Instant at) {
        return at == null || !clock.instant().isBefore(at);
    }

    private boolean applicable() {
        return enabled && running.get().channel() == ReleaseChannel.RELEASE;
    }

    private static State apply(State current, ReleaseCheckClient.Outcome outcome, Instant now, boolean manual) {
        return switch (outcome) {
            case Found found -> completed(now, new Answer(found.release(), found.etag()));
            case NotModified ignored ->
                current.answer() == null
                        // 304 to a request that sent no ETag: GitHub answered a question nobody asked.
                        ? failed(current, now, ReleaseCheckFailure.MALFORMED, null, manual)
                        : completed(now, current.answer());
            case Failed failed -> failed(current, now, failed.reason(), failed.retryAt(), manual);
        };
    }

    private static State completed(Instant now, Answer answer) {
        return new State(now, now, now.plus(CACHE), null, null, answer, 0);
    }

    private static State failed(
            State current, Instant now, ReleaseCheckFailure reason, @Nullable Instant retryAt, boolean manual) {
        int failures = manual ? current.consecutiveFailures() : current.consecutiveFailures() + 1;
        Instant nextCheck = manual ? current.nextCheck() : now.plusMillis(BACKOFF.apply(failures));
        if (retryAt != null && (nextCheck == null || retryAt.isAfter(nextCheck))) nextCheck = retryAt;
        log.atWarn()
                .addKeyValue("event.name", "release.check.failed")
                .addKeyValue("release.check.failure", reason)
                .log("Release check did not complete: {}", reason);
        return new State(now, current.lastSuccess(), nextCheck, retryAt, reason, current.answer(), failures);
    }

    private ReleaseStatusDTO status(State current) {
        var identity = running.get();
        LatestReleaseDTO latest =
                current.answer() == null ? null : current.answer().latest();
        ReleaseCheckStatus status;
        if (!enabled) status = ReleaseCheckStatus.DISABLED;
        else if (identity.channel() != ReleaseChannel.RELEASE) status = ReleaseCheckStatus.NOT_APPLICABLE;
        else if (current.failure() != null) status = ReleaseCheckStatus.FAILED;
        else if (latest == null) status = ReleaseCheckStatus.NEVER_CHECKED;
        else if (Version.parse(latest.version()).isGreaterThan(Version.parse(identity.version()))) {
            status = ReleaseCheckStatus.UPDATE_AVAILABLE;
        } else status = ReleaseCheckStatus.CURRENT;
        return new ReleaseStatusDTO(
                identity,
                status,
                current.lastAttempt(),
                current.lastSuccess(),
                current.nextCheck(),
                current.retryUntil(),
                current.failure(),
                latest);
    }
}
