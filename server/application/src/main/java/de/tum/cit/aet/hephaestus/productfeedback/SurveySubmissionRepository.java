package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SurveySubmissionRepository extends JpaRepository<SurveySubmission, UUID> {
    Page<SurveySubmission> findAllByOrderByCreatedAtDesc(Pageable pageable);

    void deleteAllByWorkspaceId(Long workspaceId);

    void deleteAllByAccountId(long accountId);

    @Modifying
    @Query(
            "delete from SurveySubmission s where s.surveyId = :surveyId and s.accountId = :accountId and s.disposition = de.tum.cit.aet.hephaestus.productfeedback.SurveySubmission.Disposition.DISMISSED")
    void deleteDismissal(UUID surveyId, Long accountId);
}
