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
     * Terms and the privacy notice, for this wording. The research half is decided in
     * {@link ConsentService} from the same latest-decision lookup that decides whether research is
     * authorised, so the two can never answer differently about the same row.
     */
    @Query(value = """
                SELECT (
                    count(*) FILTER (WHERE purpose = 'TERMS_ACCEPTANCE' AND granted) > 0
                    AND count(*) FILTER (WHERE purpose = 'PRIVACY_NOTICE_ACKNOWLEDGEMENT' AND granted) > 0
                )
                FROM consent_decision
                WHERE account_id = :accountId AND notice_version = :noticeVersion
                """, nativeQuery = true)
    boolean hasAcceptedNotice(@Param("accountId") Long accountId, @Param("noticeVersion") String noticeVersion);
}
