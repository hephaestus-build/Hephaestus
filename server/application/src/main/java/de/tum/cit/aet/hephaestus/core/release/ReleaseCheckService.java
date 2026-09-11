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

    /** How long a completed answer stands before the scheduler asks again. */
    static final Duration CACHE = Duration.ofHours(24);

    /** Retry schedule after a failure: fifteen minutes, doubling, capped at the cache period. */
    private static final IntervalFunction BACKOFF =
            IntervalFunction.ofExponentialBackoff(Duration.ofMinutes(15), 2, CACHE);

    /**
     * Everything a check can change, replaced as one value so a read never sees half an update.
     *
     * @param consecutiveFailures how many attempts in a row did not complete, for the backoff step
     */
    private record State(
            @Nullable Instant lastAttempt,
            @Nullable Instant lastSuccess,
            @Nullable Instant nextCheck,
            @Nullable ReleaseCheckFailure failure,
            @Nullable LatestReleaseDTO latest,
            @Nullable String etag,
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

    /** The scheduled tick: cheap unless the cache or the backoff has run out. */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    @WorkspaceAgnostic("Public release metadata is instance-wide; no tenant row is read or written")
    public void poll() {
        Instant nextCheck = state.get().nextCheck();
        if (nextCheck == null || !clock.instant().isBefore(nextCheck)) check(false);
    }

    /**
     * An administrator's request. It does not wait for the cache, because sixty requests an hour
     * cover any number of clicks; it does wait out a rate-limit window, because GitHub asked.
     */
    public ReleaseStatusDTO check() {
        return check(true);
    }

    private ReleaseStatusDTO check(boolean manual) {
        if (!applicable()) return status();
        checking.lock();
        try {
            State current = state.get();
            Instant now = clock.instant();
            boolean limited = current.failure() == ReleaseCheckFailure.RATE_LIMITED
                    && current.nextCheck() != null
                    && now.isBefore(current.nextCheck());
            // A concurrent check that just finished has moved nextCheck past now; its answer stands.
            boolean fresh = !manual && current.nextCheck() != null && now.isBefore(current.nextCheck());
            if (limited || fresh) return status(current);
            State next = apply(current, client.fetchLatest(current.etag()), now);
            state.set(next);
            return status(next);
        } finally {
            checking.unlock();
        }
    }

    private boolean applicable() {
        return enabled && running.get().channel() == ReleaseChannel.RELEASE;
    }

    private static State apply(State current, ReleaseCheckClient.Outcome outcome, Instant now) {
        return switch (outcome) {
            case Found found -> completed(now, found.release(), found.etag());
            case NotModified ignored ->
                current.latest() == null
                        // Nothing to revalidate against: the ETag came from a release this process no longer holds.
                        ? failed(current, now, ReleaseCheckFailure.MALFORMED, null)
                        : completed(now, current.latest(), current.etag());
            case Failed failed -> failed(current, now, failed.reason(), failed.retryAt());
        };
    }

    private static State completed(Instant now, LatestReleaseDTO latest, @Nullable String etag) {
        return new State(now, now, now.plus(CACHE), null, latest, etag, 0);
    }

    private static State failed(State current, Instant now, ReleaseCheckFailure reason, @Nullable Instant retryAt) {
        int failures = current.consecutiveFailures() + 1;
        Instant backoff = now.plusMillis(BACKOFF.apply(failures));
        Instant nextCheck = retryAt != null && retryAt.isAfter(backoff) ? retryAt : backoff;
        log.atWarn()
                .addKeyValue("event.name", "release.check.failed")
                .addKeyValue("release.check.failure", reason)
                .addKeyValue("release.check.next", nextCheck)
                .log("Release check did not complete: {}; next attempt at {}", reason, nextCheck);
        return new State(now, current.lastSuccess(), nextCheck, reason, current.latest(), current.etag(), failures);
    }

    private ReleaseStatusDTO status(State current) {
        var identity = running.get();
        ReleaseCheckStatus status;
        if (!enabled) status = ReleaseCheckStatus.DISABLED;
        else if (identity.channel() != ReleaseChannel.RELEASE) status = ReleaseCheckStatus.NOT_APPLICABLE;
        else if (current.failure() != null) status = ReleaseCheckStatus.FAILED;
        else if (current.latest() == null) status = ReleaseCheckStatus.NEVER_CHECKED;
        else if (Version.parse(current.latest().version()).isGreaterThan(Version.parse(identity.version()))) {
            status = ReleaseCheckStatus.UPDATE_AVAILABLE;
        } else status = ReleaseCheckStatus.CURRENT;
        return new ReleaseStatusDTO(
                identity,
                status,
                current.lastAttempt(),
                current.lastSuccess(),
                current.nextCheck(),
                current.failure(),
                current.latest());
    }
}
