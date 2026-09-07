package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Version;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnServerRole
public class ReleaseCheckService {
    private final RunningRelease running;
    private final ReleaseCheckClient client;
    private final Clock clock;
    private final boolean enabled;
    private @Nullable Instant lastAttempt;
    private @Nullable Instant lastSuccess;
    private @Nullable Instant nextCheck;
    private @Nullable String failure;
    private @Nullable String etag;
    private ReleaseStatusDTO.@Nullable AvailableReleaseDTO latest;
    private int failures;
    // Cached reads must not wait for the external request under refresh's monitor.
    private volatile ReleaseStatusDTO snapshot;

    public ReleaseCheckService(
            RunningRelease running,
            ReleaseCheckClient client,
            Clock clock,
            @Value("${hephaestus.release.check-enabled:true}") boolean enabled) {
        this.running = running;
        this.client = client;
        this.clock = clock;
        this.enabled = enabled;
        snapshot = buildStatus();
    }

    public ReleaseStatusDTO status() {
        var current = snapshot;
        if ((current.status().equals("CURRENT") || current.status().equals("UPDATE_AVAILABLE"))
                && current.nextCheck() != null
                && !clock.instant().isBefore(current.nextCheck())) {
            return new ReleaseStatusDTO(
                    current.running(),
                    "STALE",
                    current.enabled(),
                    current.lastAttempt(),
                    current.lastSuccess(),
                    current.nextCheck(),
                    current.failureReason(),
                    current.available(),
                    current.backupRestoreStatus(),
                    current.upgradeGuideUrl());
        }
        return current;
    }

    private ReleaseStatusDTO buildStatus() {
        var identity = running.get();
        String status;
        if (!enabled) status = "DISABLED";
        else if (!identity.channel().equals("stable")
                || identity.identityStatus().equals("MISMATCH")
                || identity.identityStatus().equals("INVALID")) status = "UNSUPPORTED";
        else if (failure != null) status = "CHECK_FAILED";
        else if (latest == null) status = "NEVER_CHECKED";
        else {
            int comparison = Version.parse(latest.version()).compareTo(Version.parse(identity.version()));
            status = comparison > 0 ? "UPDATE_AVAILABLE" : comparison == 0 ? "CURRENT" : "UNSUPPORTED";
        }
        return new ReleaseStatusDTO(
                identity,
                status,
                enabled,
                lastAttempt,
                lastSuccess,
                nextCheck,
                failure,
                latest,
                "UNKNOWN",
                "https://docs.hephaestus.build/admin/install#upgrades");
    }

    // Fixed-delay tasks run on SimpleAsyncTaskScheduler's shared scheduler thread; network I/O must not.
    @Scheduled(initialDelayString = "PT1M", fixedRateString = "PT1M")
    public void poll() {
        refresh();
    }

    public synchronized ReleaseStatusDTO refresh() {
        Instant now = clock.instant();
        if (!enabled
                || !running.get().channel().equals("stable")
                || running.get().identityStatus().equals("MISMATCH")
                || running.get().identityStatus().equals("INVALID")
                || (nextCheck != null && now.isBefore(nextCheck))) return status();
        lastAttempt = now;
        snapshot = buildStatus();
        try {
            var result = client.check(etag);
            if (result.status() == 200 && result.release() != null) {
                if (latest != null
                        && Version.parse(result.release().version()).isLessThan(Version.parse(latest.version()))) {
                    throw new IllegalArgumentException("Release source regressed");
                }
                latest = result.release();
                etag = result.etag();
            } else if (result.status() != 304 || latest == null) {
                fail(
                        now,
                        result.status() == 429
                                        || (result.status() == 403
                                                && (result.retryAfter() != null || result.reset() != null))
                                ? "RATE_LIMITED"
                                : "RELEASE_SOURCE_UNAVAILABLE");
                Instant retry = retryAt(result.retryAfter(), result.reset(), now);
                if (nextCheck == null || retry.isAfter(nextCheck)) nextCheck = retry;
                snapshot = buildStatus();
                return status();
            }
            lastSuccess = now;
            failure = null;
            failures = 0;
            nextCheck = now.plus(Duration.ofHours(24));
        } catch (IllegalArgumentException
                | java.time.DateTimeException
                | tools.jackson.core.JacksonException exception) {
            fail(now, "MALFORMED_RELEASE");
        } catch (RuntimeException exception) {
            // Never expose exception text: HTTP diagnostics may contain response bodies or proxy details.
            fail(now, "RELEASE_SOURCE_UNAVAILABLE");
        }
        snapshot = buildStatus();
        return status();
    }

    private void fail(Instant now, String reason) {
        failure = reason;
        failures = Math.min(failures + 1, 8);
        nextCheck = now.plus(Duration.ofMinutes(Math.min(15L << (failures - 1), 1440)));
    }

    static Instant retryAt(@Nullable String retryAfter, @Nullable String reset, Instant now) {
        Instant retry = now;
        if (retryAfter != null) {
            try {
                try {
                    retry = now.plusSeconds(Long.parseLong(retryAfter));
                } catch (NumberFormatException exception) {
                    retry = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant();
                }
            } catch (RuntimeException exception) {
                retry = now.plus(Duration.ofHours(24));
            }
        }
        if (reset != null) {
            Instant resetAt;
            try {
                resetAt = Instant.ofEpochSecond(Long.parseLong(reset));
            } catch (RuntimeException exception) {
                resetAt = now.plus(Duration.ofHours(24));
            }
            if (resetAt.isAfter(retry)) retry = resetAt;
        }
        return retry;
    }
}
