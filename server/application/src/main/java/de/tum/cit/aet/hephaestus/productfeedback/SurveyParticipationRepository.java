package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SurveyParticipationRepository extends JpaRepository<SurveyParticipation, UUID> {
    Optional<SurveyParticipation> findBySurveyIdAndAccountId(UUID surveyId, Long accountId);

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
