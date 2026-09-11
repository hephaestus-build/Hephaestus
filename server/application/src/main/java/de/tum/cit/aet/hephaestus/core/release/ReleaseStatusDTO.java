package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The running release and what the last update check found. Discovery is advisory: nothing here
 * verifies an artifact or performs an upgrade.
 *
 * @param running     the identity this process reports
 * @param status      the verdict an administrator reads first
 * @param lastAttempt when a check was last started, on any outcome
 * @param lastSuccess when a check last completed, which is when {@code latest} was observed
 * @param nextCheck   when the next automatic check is due
 * @param retryUntil  the wait GitHub named on a rate limit; a manual check before it is refused
 * @param failure     why the last attempt did not complete, when {@code status} is {@code FAILED}
 * @param latest      the newest published release as of {@code lastSuccess}
 */
public record ReleaseStatusDTO(
        @NonNull RunningReleaseDTO running,
        @NonNull ReleaseCheckStatus status,
        @Nullable Instant lastAttempt,
        @Nullable Instant lastSuccess,
        @Nullable Instant nextCheck,
        @Nullable Instant retryUntil,
        @Nullable ReleaseCheckFailure failure,
        @Nullable LatestReleaseDTO latest) {

    /**
     * Deployment-reported identity: the values the verified lock env handed this process, not an
     * observation of the container. {@code commit} and {@code image} are absent outside a lock-driven
     * deployment.
     *
     * @param version the version the deployment passed as {@code APP_VERSION}
     * @param channel what kind of build that version names
     * @param commit  the source commit the lock names
     * @param image   the digest reference this container was started from
     * @param roles   the runtime roles this process booted with
     */
    public record RunningReleaseDTO(
            @NonNull String version,
            @NonNull ReleaseChannel channel,
            @Nullable String commit,
            @Nullable String image,
            @NonNull List<RuntimeRole> roles) {}

    /**
     * A published release. {@code schemaMigrations} is the flag the release workflow publishes for
     * that one release and is absent when the notes carry none; it says nothing about releases
     * between the running one and this one.
     */
    public record LatestReleaseDTO(
            @NonNull String version,
            @NonNull Instant publishedAt,
            @NonNull String notesUrl,
            @Nullable Boolean schemaMigrations) {}
}
