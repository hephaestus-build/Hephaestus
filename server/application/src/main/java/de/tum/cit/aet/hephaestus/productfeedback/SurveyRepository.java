package de.tum.cit.aet.hephaestus.productfeedback;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SurveyRepository extends JpaRepository<Survey, UUID> {
    /** Open surveys the account has neither answered nor declined, oldest first so a queue drains in order. */
    @Query(
            "select s from Survey s where s.active = true and s.startsAt <= :now and (s.endsAt is null or s.endsAt > :now) and (s.workspaceId is null or s.workspaceId = :workspaceId) and not exists (select p from SurveyParticipation p where p.surveyId = s.id and p.accountId = :accountId and p.status <> de.tum.cit.aet.hephaestus.productfeedback.SurveyParticipation.Status.INVITED) order by s.startsAt, s.createdAt")
    List<Survey> findOpenFor(Long workspaceId, Long accountId, Instant now);

    void deleteAllByWorkspaceId(Long workspaceId);
}
