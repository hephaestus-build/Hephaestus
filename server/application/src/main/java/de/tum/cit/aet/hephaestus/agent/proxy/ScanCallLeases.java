package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The lease that lets one model call of a scan review's attempt run at a time, on any server replica: the budget check
 * of a scan call must see each earlier call of its attempt settled ({@link ScanCallGuard}). The attempt's
 * {@code review_job} row holds the lease. A conditional UPDATE takes it, so no database connection waits while a call
 * runs. The replica that took a lease renews it while its call runs; the lease of a replica that stopped expires after
 * {@link #TTL}, and another replica can take it then.
 */
@Component
@ConditionalOnWorkerRole
class ScanCallLeases implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ScanCallLeases.class);

    /** How long a lease lives without a renewal. */
    static final Duration TTL = Duration.ofSeconds(60);

    /** How often this replica renews the leases it holds: well inside {@link #TTL}, so a renewal can fail once. */
    static final Duration RENEW = Duration.ofSeconds(20);

    private final ReviewJobRepository jobs;
    /** The leases that this replica's calls hold, by lease, with their job. */
    private final Map<UUID, UUID> held = new ConcurrentHashMap<>();

    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "scan-call-lease-renewal");
        thread.setDaemon(true);
        return thread;
    });

    private final ScheduledFuture<?> renewal;

    ScanCallLeases(ReviewJobRepository jobs) {
        this.jobs = jobs;
        this.renewal =
                renewals.scheduleWithFixedDelay(this::renew, RENEW.toSeconds(), RENEW.toSeconds(), TimeUnit.SECONDS);
    }

    /** @return the attempt's lease, or empty while a live lease of another call holds it */
    Optional<UUID> take(UUID jobId, long workspaceId) {
        UUID lease = UUID.randomUUID();
        if (jobs.takeScanCallLease(jobId, workspaceId, lease, TTL.toSeconds()).isEmpty()) return Optional.empty();
        held.put(lease, jobId);
        return Optional.of(lease);
    }

    /** Releases the lease, unless another replica took it after it expired. */
    void release(UUID jobId, long workspaceId, UUID lease) {
        held.remove(lease);
        jobs.releaseScanCallLease(jobId, workspaceId, lease);
    }

    void renew() {
        if (held.isEmpty()) return;
        List<UUID> leases = List.copyOf(held.keySet());
        List<UUID> jobIds =
                leases.stream().map(held::get).filter(id -> id != null).toList();
        try {
            jobs.renewScanCallLeases(jobIds, leases, TTL.toSeconds());
        } catch (RuntimeException failure) {
            // A renewal may fail once within the TTL; a call whose lease expires meanwhile may share its attempt.
            log.atWarn()
                    .addKeyValue("event.name", "agent.scan.call_lease_renewal_failed")
                    .addKeyValue("error.type", failure.getClass().getName())
                    .log("Could not renew this replica's scan call leases");
        }
    }

    @Override
    public void destroy() {
        renewal.cancel(false);
        renewals.shutdownNow();
    }
}
