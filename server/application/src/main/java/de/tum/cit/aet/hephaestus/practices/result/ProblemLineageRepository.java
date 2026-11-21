package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.ProblemLineage;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProblemLineageRepository extends JpaRepository<ProblemLineage, UUID> {
    /** The lineage that a result recorded against its previous comparable result. */
    List<ProblemLineage> findByWorkspaceIdAndResultId(long workspaceId, UUID resultId);

    /**
     * Of {@code problemIds}, the problems that are No longer present now. A problem takes its identity from a lineage
     * row that names it. It is gone when the newest row with that identity, in the same practice, item and developer,
     * is {@code ABSENT}. So a problem that came back as New, or that a later review still finds, is present again, and
     * a problem that no row names is never gone.
     */
    @Query(value = """
        WITH cited AS (
          SELECT p.id AS problem_id, p.result_id, r.practice_id, r.item_id, r.developer_id
          FROM problem p JOIN result r ON r.id = p.result_id AND r.workspace_id = p.workspace_id
          WHERE p.workspace_id = :workspaceId AND p.id IN (:problemIds)),
        named AS (
          SELECT c.*, l.identity FROM cited c JOIN problem_lineage l ON l.workspace_id = :workspaceId
            AND l.result_id = c.result_id AND l.problem_id = c.problem_id
          UNION
          SELECT c.*, l.identity FROM cited c JOIN problem_lineage l ON l.workspace_id = :workspaceId
            AND l.prior_result_id = c.result_id AND l.prior_problem_id = c.problem_id),
        newest AS (
          SELECT DISTINCT ON (n.problem_id) n.problem_id, l.comparison
          FROM named n
          JOIN problem_lineage l ON l.workspace_id = :workspaceId AND l.identity = n.identity
          JOIN result r ON r.id = l.result_id AND r.workspace_id = l.workspace_id AND r.practice_id = n.practice_id
            AND r.item_id = n.item_id AND r.developer_id IS NOT DISTINCT FROM n.developer_id
          JOIN review v ON v.id = r.review_id AND v.workspace_id = r.workspace_id
          ORDER BY n.problem_id, v.ordered_at DESC, r.recorded_at DESC, l.id DESC)
        SELECT problem_id FROM newest WHERE comparison = 'ABSENT'
        """, nativeQuery = true)
    List<UUID> findGone(@Param("workspaceId") long workspaceId, @Param("problemIds") Collection<UUID> problemIds);
}
