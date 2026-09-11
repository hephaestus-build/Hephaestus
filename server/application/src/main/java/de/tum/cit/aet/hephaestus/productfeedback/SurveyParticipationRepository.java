package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SurveyParticipationRepository extends JpaRepository<SurveyParticipation, UUID> {
    Optional<SurveyParticipation> findBySurveyIdAndAccountId(UUID surveyId, Long accountId);

    /**
     * Two requests can race for the same row, and on PostgreSQL a failed INSERT aborts the whole
     * transaction, so the loser must not fail in the first place.
     */
    @Modifying
    @Query(value = """
        INSERT INTO product_survey_participation (id, survey_id, account_id, workspace_id, status, invited_at)
        VALUES (:id, :surveyId, :accountId, :workspaceId, 'INVITED', CURRENT_TIMESTAMP)
        ON CONFLICT (survey_id, account_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("surveyId") UUID surveyId,
            @Param("accountId") Long accountId,
            @Param("workspaceId") @Nullable Long workspaceId);

    List<SurveyParticipation> findAllBySurveyIdInAndAccountId(Collection<UUID> surveyIds, Long accountId);

    List<SurveyParticipation> findAllBySurveyIdAndStatus(UUID surveyId, SurveyParticipation.Status status);

    Page<SurveyParticipation> findAllBySurveyIdAndStatusNotOrderByDecidedAtDesc(
            UUID surveyId, SurveyParticipation.Status status, Pageable pageable);

    @WorkspaceAgnostic("Instance administrators read participation across every workspace a survey targets")
    @Query(
            "select p.surveyId as surveyId, p.status as status, count(p) as count from SurveyParticipation p where p.surveyId in :surveyIds group by p.surveyId, p.status")
    List<ParticipationCount> countBySurvey(Collection<UUID> surveyIds);

    void deleteAllBySurveyId(UUID surveyId);

    void deleteAllByWorkspaceId(Long workspaceId);

    void deleteAllByAccountId(long accountId);

    interface ParticipationCount {
        UUID getSurveyId();

        SurveyParticipation.Status getStatus();

        long getCount();
    }
}
