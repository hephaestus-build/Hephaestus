package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tools.jackson.databind.JsonNode;

@RawObservationAccess
public interface ProblemRepository extends JpaRepository<Problem, UUID> {
    Optional<Problem> findByIdAndWorkspaceId(UUID id, long workspaceId);

    List<Problem> findByWorkspaceIdAndResultIdOrderByPositionAsc(long workspaceId, UUID resultId);

    List<Problem> findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(
            long workspaceId, Collection<UUID> resultIds);

    /** The citations of each problem of the results, without the rest of the problem. */
    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.practices.result.ProblemRepository$Citations(p.resultId, p.citations)
        FROM Problem p WHERE p.workspaceId = :workspaceId AND p.resultId IN :resultIds
        ORDER BY p.resultId, p.position
        """)
    List<Citations> findCitations(
            @Param("workspaceId") long workspaceId, @Param("resultIds") Collection<UUID> resultIds);

    record Citations(UUID resultId, JsonNode citations) {}

    List<Problem> findByWorkspaceIdAndIdIn(long workspaceId, Collection<UUID> ids);

    /** Shares the key lock of each problem in id order, so an advice source never names a problem being deleted. */
    @Query(
            value = "SELECT p.id FROM problem p WHERE p.workspace_id=:workspaceId AND p.id=ANY(CAST(:ids AS uuid[]))"
                    + " ORDER BY p.id FOR KEY SHARE",
            nativeQuery = true)
    List<UUID> shareKeysInIdOrder(@Param("workspaceId") long workspaceId, @Param("ids") UUID[] ids);

    /** The identity of each of the problems that has one. */
    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.practices.result.ProblemRepository$Identity(p.id, p.identity)
        FROM Problem p WHERE p.workspaceId = :workspaceId AND p.id IN :ids AND p.identity IS NOT NULL
        """)
    List<Identity> findIdentities(@Param("workspaceId") long workspaceId, @Param("ids") Collection<UUID> ids);

    record Identity(UUID id, String identity) {}

    /**
     * Records the identity of a problem sealed before problems recorded their identity, once a comparison computed it.
     * A problem that has an identity keeps it. The update does not clear the persistence context: the seal still changes
     * the review item and practice it holds, and a loaded problem is immutable and its identity unread there.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        UPDATE problem SET identity = :identity
        WHERE workspace_id = :workspaceId AND id = :id AND identity IS NULL
        """, nativeQuery = true)
    int recordIdentity(
            @Param("workspaceId") long workspaceId, @Param("id") UUID id, @Param("identity") String identity);
}
