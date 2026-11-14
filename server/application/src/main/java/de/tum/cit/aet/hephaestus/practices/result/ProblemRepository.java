package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.Problem;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tools.jackson.databind.JsonNode;

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
}
