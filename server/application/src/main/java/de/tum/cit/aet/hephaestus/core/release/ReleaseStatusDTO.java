package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The running release and what the last update check found. Discovery is advisory: nothing here
 * verifies an artifact or performs an upgrade.
 *
 * @param running      the identity this process reports
 * @param status       the verdict an administrator reads first
 * @param lastAttempt  when a check was last started, on any outcome
 * @param lastSuccess  when a check last completed, which is when {@code latest} was observed
 * @param nextCheck    when the next automatic check is due
 * @param retryUntil   the wait GitHub named on a rate limit; a manual check before it is refused
 * @param failure      why the last attempt did not complete, when {@code status} is {@code FAILED}
 * @param latest       the newest published release as of {@code lastSuccess}
 * @param runningSince when the newest rollout became active, absent unless this server runs it
 * @param history      the rollouts of this deployment environment, newest first
 */
public record ReleaseStatusDTO(
        @NonNull RunningReleaseDTO running,
        @NonNull ReleaseCheckStatus status,
        @Nullable Instant lastAttempt,
        @Nullable Instant lastSuccess,
        @Nullable Instant nextCheck,
        @Nullable Instant retryUntil,
        @Nullable ReleaseCheckFailure failure,
        @Nullable LatestReleaseDTO latest,
        @Nullable Instant runningSince,
        @NonNull List<ReleaseStartDTO> history) {

    /**
     * Deployment-reported identity: the values the verified lock env handed this process, not an
     * observation of the container. {@code commit} and {@code image} are absent outside a lock-driven
     * deployment.
     *
     * @param version     the version the deployment passed as {@code APP_VERSION}
     * @param channel     what kind of build that version names
     * @param environment the name the deployment gives this instance
     * @param commit      the source commit the lock names
     * @param image       the digest reference this container was started from
     * @param roles       the runtime roles this process booted with
     */
    public record RunningReleaseDTO(
            @NonNull String version,
            @NonNull ReleaseChannel channel,
            @NonNull String environment,
            @Nullable String commit,
            @Nullable String image,
            @NonNull List<RuntimeRole> roles) {}

    /**
     * A rollout of the server Deployment, as its Deployment revision numbers it, or one server start where the server
     * could not read its rollout.
     *
     * @param id        the entry's row, which identifies it among entries with the same time
     * @param revision  the Deployment revision of the rollout; absent for a server start
     * @param startedAt when the first server of the rollout recorded it, by the database clock; a restart does not
     *                  change it
     */
    public record ReleaseStartDTO(
            @NonNull Long id,
            @NonNull String version,
            @NonNull ReleaseChannel channel,
            @NonNull String environment,
            @Nullable String commit,
            @Nullable String image,
            @Nullable Long revision,
            @NonNull Instant startedAt) {
        static ReleaseStartDTO from(ReleaseActivation entry) {
            return new ReleaseStartDTO(
                    Objects.requireNonNull(entry.getId()),
                    entry.getVersion(),
                    entry.getChannel(),
                    entry.getEnvironment(),
                    entry.getCommit(),
                    entry.getImage(),
                    entry.getRevision(),
                    entry.getActivatedAt());
        }
    }

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
