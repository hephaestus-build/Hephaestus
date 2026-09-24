package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the self-reported worker liveness registry: written by {@link WorkerLivenessReporter},
 * read by the orphan-recovery sweep to find jobs whose owning worker has gone stale.
 */
@Repository
@WorkspaceAgnostic("Fleet-wide worker coordination; not workspace-scoped.")
public interface WorkerRegistryRepository extends JpaRepository<WorkerRegistry, String> {
    @Query(
            value =
                    "SELECT worker_id FROM worker_registry WHERE last_heartbeat >= now() - make_interval(secs => :ttlSeconds)",
            nativeQuery = true)
    java.util.List<String> findLiveWorkerIds(@Param("ttlSeconds") long ttlSeconds);

    /** Upsert this worker's heartbeat on the DB clock, so every liveness comparison stays on one clock. */
    @Modifying
    @Query(
            value = "INSERT INTO worker_registry (worker_id, last_heartbeat, registered_at) "
                    + "VALUES (:workerId, now(), now()) "
                    + "ON CONFLICT (worker_id) DO UPDATE SET last_heartbeat = now()",
            nativeQuery = true)
    void heartbeat(@Param("workerId") String workerId);

    /**
     * Delete registrations whose heartbeat is older than {@code ttlSeconds} on the DB clock. The caller
     * must pass a TTL well above the orphan lease, so any job the worker owned is already requeued.
     *
     * @return number of rows deleted
     */
    @Modifying
    @Query(
            value = "DELETE FROM worker_registry WHERE last_heartbeat < now() - make_interval(secs => :ttlSeconds)",
            nativeQuery = true)
    int deleteStale(@Param("ttlSeconds") long ttlSeconds);
}
