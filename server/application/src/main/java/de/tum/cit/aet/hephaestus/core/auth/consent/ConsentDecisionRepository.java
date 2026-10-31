package de.tum.cit.aet.hephaestus.core.auth.consent;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Consent decisions belong to an account, not a workspace")
interface ConsentDecisionRepository extends JpaRepository<ConsentDecision, Long> {
    Optional<ConsentDecision> findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(
            Long accountId, ConsentDecision.Purpose purpose);

    /**
     * Every decision of the given purpose for these accounts, newest first within each account — the
     * order {@link #findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc} takes the first of. One
     * read for a cohort instead of one per account.
     */
    @Query("""
            SELECT d FROM ConsentDecision d
             WHERE d.account.id IN :accountIds AND d.purpose = :purpose
             ORDER BY d.account.id, d.occurredAt DESC, d.id DESC
            """)
    List<ConsentDecision> findHistoryNewestFirst(
            @Param("accountIds") Collection<Long> accountIds, @Param("purpose") ConsentDecision.Purpose purpose);

    /**
     * Terms and the privacy notice, for wording that covers the retained scope. The research half is decided in
     * {@link ConsentService} from the same latest-decision lookup that decides whether research is
     * authorised, so the two can never answer differently about the same row.
     */
    @Query(value = """
                SELECT (
                    count(*) FILTER (WHERE purpose = 'TERMS_ACCEPTANCE' AND granted) > 0
                    AND count(*) FILTER (WHERE purpose = 'PRIVACY_NOTICE_ACKNOWLEDGEMENT' AND granted) > 0
                )
                FROM consent_decision
                WHERE account_id = :accountId AND notice_version IN (:noticeVersions)
                """, nativeQuery = true)
    boolean hasAcceptedNotice(
            @Param("accountId") Long accountId, @Param("noticeVersions") Collection<String> noticeVersions);
}
