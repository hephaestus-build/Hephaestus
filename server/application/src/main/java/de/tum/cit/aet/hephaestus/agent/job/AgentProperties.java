package de.tum.cit.aet.hephaestus.agent.job;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the PostgreSQL-backed agent job queue: the {@code review_job} table itself, claimed
 * by {@link ReviewJobExecutor}'s poll loop with {@code FOR UPDATE SKIP LOCKED}.
 *
 * @param enabled           Turns on agent jobs, which run practice reviews. The server role submits jobs and the
 *                          worker role runs them, so set it on both.
 * @param pollInterval      how long the poll loop sleeps when a poll claimed nothing
 * @param claimBatchSize    Maximum candidate batch. A blocked endpoint does not consume a local execution slot.
 * @param heartbeatInterval interval between {@code worker_registry} liveness heartbeats
 * @param payloadRetention  age from {@code completed_at} at which a TERMINAL job's {@code capture}
 *                          and {@code output} are stripped to NULL
 * @param rowRetention      Age, counted from the end of a job, at which an ended job row that waits for no delivery
 *                          is deleted. Must be at least the payload retention.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.agent")
public record AgentProperties(
        @DefaultValue("false") boolean enabled,

        @DurationUnit(ChronoUnit.SECONDS) @DefaultValue("1s") @NotNull
        Duration pollInterval,

        @DefaultValue("5") @Min(1) int claimBatchSize,

        @DurationUnit(ChronoUnit.SECONDS) @DefaultValue("25s") @NotNull
        Duration heartbeatInterval,

        @DefaultValue("P14D") @NotNull Duration payloadRetention,
        @DefaultValue("P90D") @NotNull Duration rowRetention) {
    /** Floor for {@link #pollInterval}: below this the poll loop busy-spins against the DB. */
    public static final Duration MIN_POLL_INTERVAL = Duration.ofMillis(100);

    /** Floor for {@link #heartbeatInterval}: below this the liveness signal floods {@code worker_registry}. */
    public static final Duration MIN_HEARTBEAT_INTERVAL = Duration.ofSeconds(1);

    /** A worker that did not beat for this long counts as gone, and its Heph sandboxes are removed. */
    public static final Duration WORKER_LEASE_TTL = Duration.ofMinutes(5);

    /** Ceiling for {@link #heartbeatInterval} — half the lease, so a worker survives losing one beat. */
    public static final Duration MAX_HEARTBEAT_INTERVAL = WORKER_LEASE_TTL.dividedBy(2);

    /** Bean Validation has no duration-comparison constraint, so the {@link Duration} bounds are checked here. */
    public AgentProperties {
        if (pollInterval == null || pollInterval.compareTo(MIN_POLL_INTERVAL) < 0) {
            throw new IllegalArgumentException(
                    "hephaestus.agent.poll-interval (AGENT_POLL_INTERVAL) must be >= " + MIN_POLL_INTERVAL
                            + ", got: "
                            + pollInterval);
        }
        if (heartbeatInterval == null || heartbeatInterval.compareTo(MIN_HEARTBEAT_INTERVAL) < 0) {
            throw new IllegalArgumentException(
                    "hephaestus.agent.heartbeat-interval must be >= " + MIN_HEARTBEAT_INTERVAL
                            + ", got: "
                            + heartbeatInterval);
        }
        if (heartbeatInterval.compareTo(MAX_HEARTBEAT_INTERVAL) > 0) {
            throw new IllegalArgumentException(
                    "hephaestus.agent.heartbeat-interval must be <= " + MAX_HEARTBEAT_INTERVAL
                            + " (half the "
                            + WORKER_LEASE_TTL
                            + " worker liveness window). A higher value counts every worker as gone. Got: "
                            + heartbeatInterval);
        }
        if (payloadRetention == null || payloadRetention.isNegative() || payloadRetention.isZero()) {
            throw new IllegalArgumentException(
                    "hephaestus.agent.payload-retention must be positive, got: " + payloadRetention);
        }
        if (rowRetention == null || rowRetention.compareTo(payloadRetention) < 0) {
            throw new IllegalArgumentException("hephaestus.agent.row-retention (" + rowRetention
                    + ") must be >= payload-retention ("
                    + payloadRetention
                    + ") — a row cannot be deleted before its payload would already have been stripped");
        }
    }
}
