package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("PracticeRevision scoped through practice.workspace relationship")
public interface PracticeRevisionRepository
        extends org.springframework.data.repository.Repository<PracticeRevision, Long> {
    PracticeRevision save(PracticeRevision revision);

    Optional<PracticeRevision> findById(Long id);

    @Query(
            "SELECT r FROM PracticeRevision r JOIN FETCH r.practice p JOIN FETCH p.workspace WHERE r.id = :id AND p.workspace.id = :workspaceId")
    Optional<PracticeRevision> findByIdAndWorkspaceId(@Param("id") Long id, @Param("workspaceId") Long workspaceId);

    List<PracticeRevision> findAll();

    Optional<PracticeRevision> findFirstByPracticeIdOrderByRevisionNumberDesc(Long practiceId);

    @Query("""
        SELECT DISTINCT r.practice.workspace.id FROM PracticeRevision r
        WHERE r.slug IS NOT NULL
          AND r.reviewRuleFingerprint IS NULL
          AND r.automatedReviewPolicy IS NOT NULL
        ORDER BY r.practice.workspace.id
        """)
    List<Long> findWorkspaceIdsWithDefinitionRevisionsMissingFingerprint();

    @Query("""
        SELECT r FROM PracticeRevision r
        WHERE r.practice.workspace.id = :workspaceId
          AND r.slug IS NOT NULL
          AND r.reviewRuleFingerprint IS NULL
          AND r.automatedReviewPolicy IS NOT NULL
        ORDER BY r.id
        """)
    List<PracticeRevision> findDefinitionRevisionsMissingFingerprint(@Param("workspaceId") Long workspaceId);

    @Modifying
    @Query(
            value = "UPDATE practice_revision SET review_rule_fingerprint = :fingerprint WHERE id = :revisionId",
            nativeQuery = true)
    void setReviewRuleFingerprint(@Param("revisionId") long revisionId, @Param("fingerprint") String fingerprint);

    /**
     * Returns the definition available at {@code asOf}, so an edit during detection cannot change the
     * recorded provenance.
     */
    Optional<PracticeRevision> findFirstByPracticeIdAndCreatedAtLessThanEqualOrderByRevisionNumberDesc(
            Long practiceId, Instant asOf);
}
