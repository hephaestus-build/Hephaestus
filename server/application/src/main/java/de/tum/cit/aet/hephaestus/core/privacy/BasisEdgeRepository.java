package de.tum.cit.aet.hephaestus.core.privacy;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
interface BasisEdgeRepository extends JpaRepository<BasisEdge, Long> {

    /**
     * Cites one source for {@code accountId}. The row policy refuses it unless the transaction binds that owner. The
     * {@code basis_edge_bound} trigger refuses an entry that is not the owner's in the workspace.
     */
    @Modifying
    @Query(value = """
            INSERT INTO basis_edge (workspace_id, account_id, dependent_kind, dependent_id, source_kind, source_id)
            VALUES (:workspaceId, :accountId, :dependentKind, :dependentId, :sourceKind, :sourceId)
            ON CONFLICT (workspace_id, dependent_kind, dependent_id, source_kind, source_id) DO NOTHING
            """, nativeQuery = true)
    void cite(
            @Param("workspaceId") long workspaceId,
            @Param("accountId") long accountId,
            @Param("dependentKind") String dependentKind,
            @Param("dependentId") UUID dependentId,
            @Param("sourceKind") String sourceKind,
            @Param("sourceId") UUID sourceId);

    /**
     * The dependents that reach a removed source through the provenance references that the owner can read. A
     * dependent reaches the sources it cites, and the sources of each record it cites. {@code UNION} ends the walk at a
     * reference it has already reached.
     */
    @Query(value = """
            WITH RECURSIVE reach (dependent_id, source_kind, source_id) AS (
                SELECT e.dependent_id, e.source_kind, e.source_id
                FROM basis_edge e
                WHERE e.workspace_id = :workspaceId AND e.dependent_kind = :dependentKind
                  AND e.dependent_id IN (:dependentIds)
                UNION
                SELECT r.dependent_id, e.source_kind, e.source_id
                FROM reach r
                JOIN basis_edge e ON e.workspace_id = :workspaceId
                  AND e.dependent_kind = r.source_kind AND e.dependent_id = r.source_id
            )
            SELECT DISTINCT r.dependent_id
            FROM reach r
            JOIN source_tombstone t ON t.workspace_id = :workspaceId
              AND t.source_kind = r.source_kind AND t.source_id = r.source_id
            """, nativeQuery = true)
    List<UUID> findStale(
            @Param("workspaceId") long workspaceId,
            @Param("dependentKind") String dependentKind,
            @Param("dependentIds") Collection<UUID> dependentIds);
}
