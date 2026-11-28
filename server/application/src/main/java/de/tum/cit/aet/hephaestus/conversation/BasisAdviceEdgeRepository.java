package de.tum.cit.aet.hephaestus.conversation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
interface BasisAdviceEdgeRepository extends JpaRepository<BasisAdviceEdge, BasisAdviceEdge.Key> {

    /** Binds these revisions to the basis. The caller took them from {@code AdviceRevisionTexts.lockWithText}. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO basis_advice_edge (workspace_id, basis_id, revision_id)
            SELECT :workspaceId, :basisId, r FROM unnest(CAST(:ids AS uuid[])) AS r
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int bindRevisions(@Param("workspaceId") long workspaceId, @Param("basisId") UUID basisId, @Param("ids") UUID[] ids);

    /** The advice revisions that the basis read. */
    @Query(value = """
            SELECT revision_id FROM basis_advice_edge WHERE workspace_id = :workspaceId AND basis_id = :basisId
            """, nativeQuery = true)
    List<UUID> revisionsOf(@Param("workspaceId") long workspaceId, @Param("basisId") UUID basisId);

    /**
     * The entries of the workspace whose basis read one of these revisions, also the erased ones: what derives from an
     * erased entry can still hold its words.
     */
    @Query(value = """
            SELECT e.id FROM basis_advice_edge b
            JOIN conversation_entry e ON e.basis_id = b.basis_id AND e.workspace_id = b.workspace_id
            WHERE b.workspace_id = :workspaceId AND b.revision_id = ANY(CAST(:ids AS uuid[]))
            ORDER BY e.id
            """, nativeQuery = true)
    List<UUID> findEntriesThatRead(@Param("workspaceId") long workspaceId, @Param("ids") UUID[] ids);
}
