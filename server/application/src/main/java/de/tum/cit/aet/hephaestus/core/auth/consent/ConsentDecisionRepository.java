package de.tum.cit.aet.hephaestus.core.auth.consent;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Consent decisions belong to an account, not a workspace")
interface ConsentDecisionRepository extends JpaRepository<ConsentDecision, Long> {
    Optional<ConsentDecision> findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(
            Long accountId, ConsentDecision.Purpose purpose);

    /**
     * A research row counts whether it grants or refuses — the question is answered either way. It is
     * only required when the instance names a research organisation; where none is configured the
     * question is never asked, and demanding an answer would gate every account out of the app.
     */
    @Query(value = """
                SELECT (
                    count(*) FILTER (WHERE purpose = 'TERMS_ACCEPTANCE' AND granted) > 0
                    AND count(*) FILTER (WHERE purpose = 'PRIVACY_NOTICE_ACKNOWLEDGEMENT' AND granted) > 0
                    AND (NOT :requireResearch OR count(*) FILTER (WHERE purpose = 'RESEARCH_PARTICIPATION') > 0)
                )
                FROM consent_decision
                WHERE account_id = :accountId AND notice_version = :noticeVersion
                """, nativeQuery = true)
    boolean isCompletedForNotice(
            @Param("accountId") Long accountId,
            @Param("noticeVersion") String noticeVersion,
            @Param("requireResearch") boolean requireResearch);
}
