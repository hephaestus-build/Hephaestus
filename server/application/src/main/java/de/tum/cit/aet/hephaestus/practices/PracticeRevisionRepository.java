package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /**
     * The first revision after {@code revisionNumber} whose review rules differ from {@code fingerprint}:
     * when the rules a past observation was measured against stopped being the ones in force. A later edit
     * that touches only prose leaves the fingerprint alone and is skipped, so the moment does not drift.
     */
    Optional<PracticeRevision>
            findFirstByPracticeIdAndRevisionNumberGreaterThanAndReviewRuleFingerprintNotOrderByRevisionNumberAsc(
                    Long practiceId, int revisionNumber, String fingerprint);

    /** The earliest revision of a practice recorded under exactly this review-rule fingerprint. */
    Optional<PracticeRevision> findFirstByPracticeIdAndReviewRuleFingerprintOrderByRevisionNumberAsc(
            Long practiceId, String fingerprint);

    /**
     * Returns the definition available at {@code asOf}, so an edit during a review cannot change the
     * recorded provenance.
     */
    Optional<PracticeRevision> findFirstByPracticeIdAndCreatedAtLessThanEqualOrderByRevisionNumberDesc(
            Long practiceId, Instant asOf);
}
