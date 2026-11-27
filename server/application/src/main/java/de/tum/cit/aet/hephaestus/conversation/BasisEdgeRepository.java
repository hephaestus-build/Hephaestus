package de.tum.cit.aet.hephaestus.conversation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
interface BasisEdgeRepository extends JpaRepository<BasisEdge, BasisEdge.Key> {

    /**
     * Binds the entries among {@code ids} that are not erased to the basis, at the revision they have now. An erased
     * entry releases no words, so it is no source. A source that the basis already holds keeps its first revision.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO basis_edge (workspace_id, basis_id, source_kind, source_id, source_revision)
            SELECT e.workspace_id, :basisId, 'ENTRY', e.id, e.revision
            FROM conversation_entry e
            WHERE e.workspace_id = :workspaceId AND e.id = ANY(CAST(:ids AS uuid[])) AND e.redacted_at IS NULL
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int bindEntries(@Param("workspaceId") long workspaceId, @Param("basisId") UUID basisId, @Param("ids") UUID[] ids);

    /** Whether an entry that the basis read was erased since. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM basis_edge b
                JOIN conversation_entry s ON s.id = b.source_id AND s.workspace_id = b.workspace_id
                WHERE b.workspace_id = :workspaceId AND b.basis_id = :basisId AND b.source_kind = 'ENTRY'
                  AND s.redacted_at IS NOT NULL)
            """, nativeQuery = true)
    boolean hasErasedEntry(@Param("workspaceId") long workspaceId, @Param("basisId") UUID basisId);
}
