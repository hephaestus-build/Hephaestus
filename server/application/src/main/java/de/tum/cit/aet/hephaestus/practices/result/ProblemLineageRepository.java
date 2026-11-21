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

    /** Where one problem stands now, as {@link #findStanding} reads it. */
    interface ProblemStanding {
        UUID getProblemId();

        /** The comparison of the newest lineage row with the problem's identity. */
        String getComparison();

        /** The result that wrote that row. */
        UUID getResultId();

        /**
         * Whether a newer result of the same practice, item and developer compared with nothing: it was measured with
         * another practice version or model method, or it ended as it was written. A problem it found has no row, so
         * the newest row proves nothing about now.
         */
        boolean getUnchained();
    }

    /**
     * Where each of {@code problemIds} stands now. A problem takes its identity from a lineage row that names it. The
     * newest row with that identity, in the same practice, item and developer, says where it stands. Only rows of
     * results that a comparison may use count: an item review that is no trial, a result that was not outranked, and
     * one that no person marked incorrect. A problem that no row names, or whose own result a person marked incorrect,
     * has no standing.
     */
    @Query(value = """
        WITH cited AS (
          SELECT p.id AS problem_id, p.result_id, r.practice_id, r.item_id, r.developer_id
          FROM problem p JOIN result r ON r.id = p.result_id AND r.workspace_id = p.workspace_id
          WHERE p.workspace_id = :workspaceId AND p.id IN (:problemIds)
            AND NOT EXISTS (SELECT 1 FROM result_mark k WHERE k.result_id = r.id AND k.workspace_id = r.workspace_id
              AND k.kind = 'INCORRECT' AND k.removed_at IS NULL)),
        named AS (
          SELECT c.*, l.identity FROM cited c JOIN problem_lineage l ON l.workspace_id = :workspaceId
            AND l.result_id = c.result_id AND l.problem_id = c.problem_id
          UNION
          SELECT c.*, l.identity FROM cited c JOIN problem_lineage l ON l.workspace_id = :workspaceId
            AND l.prior_result_id = c.result_id AND l.prior_problem_id = c.problem_id),
        usable AS (
          SELECT r.id, r.practice_id, r.item_id, r.developer_id, r.practice_version_id, r.recorded_at, v.ordered_at,
            COALESCE(r.ended_at <= r.recorded_at, false) AS ended_at_once,
            m.method->>'modelDigest' AS digest, m.method->>'epoch' AS epoch
          FROM result r
          JOIN review v ON v.id = r.review_id AND v.workspace_id = r.workspace_id
          JOIN review_practice m ON m.review_id = r.review_id AND m.practice_id = r.practice_id
            AND m.workspace_id = r.workspace_id
          WHERE r.workspace_id = :workspaceId AND v.kind = 'ITEM' AND v.origin <> 'TRIAL'
            AND r.end_reason IS DISTINCT FROM 'OUTRANKED'
            AND r.item_id IN (SELECT item_id FROM cited)
            AND NOT EXISTS (SELECT 1 FROM result_mark k WHERE k.result_id = r.id AND k.workspace_id = r.workspace_id
              AND k.kind = 'INCORRECT' AND k.removed_at IS NULL)),
        newest AS (
          SELECT DISTINCT ON (n.problem_id) n.problem_id, l.comparison, u.*
          FROM named n
          JOIN problem_lineage l ON l.workspace_id = :workspaceId AND l.identity = n.identity
          JOIN usable u ON u.id = l.result_id AND u.practice_id = n.practice_id AND u.item_id = n.item_id
            AND u.developer_id IS NOT DISTINCT FROM n.developer_id
          ORDER BY n.problem_id, u.ordered_at DESC, u.recorded_at DESC, l.id DESC)
        SELECT w.problem_id AS problemId, w.comparison AS comparison, w.id AS resultId,
          EXISTS (SELECT 1 FROM usable later WHERE later.practice_id = w.practice_id AND later.item_id = w.item_id
            AND later.developer_id IS NOT DISTINCT FROM w.developer_id
            AND (later.ordered_at > w.ordered_at OR (later.ordered_at = w.ordered_at AND later.recorded_at > w.recorded_at))
            AND (later.practice_version_id IS DISTINCT FROM w.practice_version_id
              OR later.digest IS DISTINCT FROM w.digest OR later.epoch IS DISTINCT FROM w.epoch
              OR later.ended_at_once)) AS unchained
        FROM newest w
        """, nativeQuery = true)
    List<ProblemStanding> findStanding(
            @Param("workspaceId") long workspaceId, @Param("problemIds") Collection<UUID> problemIds);

    /**
     * Whether {@code resultId} proved one of {@code problemIds} No longer present: it wrote an {@code ABSENT} row with
     * that problem's identity, in the same practice, item and developer.
     */
    @Query(value = """
        SELECT EXISTS (
          SELECT 1 FROM problem_lineage l
          JOIN result proving ON proving.id = l.result_id AND proving.workspace_id = l.workspace_id
          JOIN problem_lineage named ON named.workspace_id = l.workspace_id AND named.identity = l.identity
          JOIN problem p ON p.workspace_id = named.workspace_id AND p.id IN (:problemIds)
            AND ((named.problem_id = p.id AND named.result_id = p.result_id)
              OR (named.prior_problem_id = p.id AND named.prior_result_id = p.result_id))
          JOIN result cited ON cited.id = p.result_id AND cited.workspace_id = p.workspace_id
          WHERE l.workspace_id = :workspaceId AND l.result_id = :resultId AND l.comparison = 'ABSENT'
            AND proving.practice_id = cited.practice_id AND proving.item_id = cited.item_id
            AND proving.developer_id IS NOT DISTINCT FROM cited.developer_id)
        """, nativeQuery = true)
    boolean namesAny(
            @Param("workspaceId") long workspaceId,
            @Param("resultId") UUID resultId,
            @Param("problemIds") Collection<UUID> problemIds);
}
