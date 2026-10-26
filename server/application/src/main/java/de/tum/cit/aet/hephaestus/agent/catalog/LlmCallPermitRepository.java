package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** The permits of the calls in flight. Every time is the database's, so the workers' clocks never meet. */
@Repository
@WorkspaceAgnostic("Fleet-wide model call permits; a pool is shared across workspaces.")
public interface LlmCallPermitRepository extends JpaRepository<LlmCallPermit, UUID> {

    /** The caller holds the pool row lock. */
    @Modifying
    @Query(
            value = "DELETE FROM llm_call_permit WHERE pool_id = :poolId"
                    + " AND renewed_at < now() - make_interval(secs => :staleSeconds)",
            nativeQuery = true)
    int reclaimStale(@Param("poolId") long poolId, @Param("staleSeconds") long staleSeconds);

    long countByPoolId(Long poolId);

    /** The caller holds the pool row lock and counted fewer permits than the pool allows. */
    @Modifying
    @Query(
            value = "INSERT INTO llm_call_permit (id, pool_id, worker_id, call_class, acquired_at, renewed_at)"
                    + " VALUES (:id, :poolId, :workerId, :callClass, now(), now())",
            nativeQuery = true)
    void insert(
            @Param("id") UUID id,
            @Param("poolId") long poolId,
            @Param("workerId") String workerId,
            @Param("callClass") String callClass);

    @Modifying
    @Query(
            value = "UPDATE llm_call_permit SET renewed_at = now() WHERE worker_id = :workerId AND id IN (:ids)",
            nativeQuery = true)
    int renew(@Param("workerId") String workerId, @Param("ids") Collection<UUID> ids);

    @Query(value = "SELECT id FROM llm_call_permit WHERE worker_id = :workerId AND id IN (:ids)", nativeQuery = true)
    List<UUID> findHeld(@Param("workerId") String workerId, @Param("ids") Collection<UUID> ids);

    @Modifying
    @Query(value = "DELETE FROM llm_call_permit WHERE id = :id", nativeQuery = true)
    int release(@Param("id") UUID id);
}
