package de.tum.cit.aet.hephaestus.practices.research;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.research.ResearchDisclosureSubject.RemovalReason;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ResearchDisclosureSubjectRepository extends JpaRepository<ResearchDisclosureSubject, UUID> {

    @Query("""
        SELECT s FROM ResearchDisclosureSubject s
         WHERE s.workspaceId = :workspaceId AND s.disclosureId = :disclosureId
         ORDER BY s.participantKey
        """)
    List<ResearchDisclosureSubject> findForDisclosure(
            @Param("workspaceId") long workspaceId, @Param("disclosureId") UUID disclosureId);

    /**
     * The stored state of each subject of these disclosures, as values. Erasure and receipts change these rows with
     * field-only statements, so a managed copy read here could write a stale link or removal back; a value cannot.
     */
    @WorkspaceAgnostic("The instance-admin removal manifest reads the subjects of every open disclosure at once")
    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.practices.research.ResearchDisclosureSubjectRepository$SubjectState(
          s.id, s.disclosureId, s.workspaceId, s.accountId, s.grantDecisionId, s.participantKey,
          s.removalReason, s.removalRequiredAt, s.removedAt)
        FROM ResearchDisclosureSubject s WHERE s.disclosureId IN :disclosureIds ORDER BY s.participantKey
        """)
    List<SubjectState> stateOf(@Param("disclosureIds") Collection<UUID> disclosureIds);

    /**
     * Records why removal is owed, only while the row still links the account and grant whose standing was read and no
     * reason stands yet. A concurrent erasure, receipt or check makes it match nothing, and what committed first stays.
     */
    @Modifying
    @Query(value = """
        UPDATE research_disclosure_subject
           SET removal_reason = :reason, removal_required_at = :at
         WHERE workspace_id = :workspaceId AND id = :id AND removal_reason IS NULL
           AND account_id = :accountId AND grant_decision_id = :grantDecisionId
        """, nativeQuery = true)
    int requireRemoval(
            @Param("workspaceId") long workspaceId,
            @Param("id") UUID id,
            @Param("accountId") long accountId,
            @Param("grantDecisionId") long grantDecisionId,
            @Param("reason") String reason,
            @Param("at") Instant at);

    /**
     * Records the operator's attestation for these keys and drops their identifying link, only where removal is owed,
     * not yet recorded and not later than the attestation. The reason and its date are never written here.
     */
    @Modifying
    @Query(value = """
        UPDATE research_disclosure_subject
           SET removed_at = :at, account_id = NULL, grant_decision_id = NULL
         WHERE workspace_id = :workspaceId AND disclosure_id = :disclosureId
           AND participant_key = ANY(:participantKeys)
           AND removal_reason IS NOT NULL AND removed_at IS NULL AND removal_required_at <= :at
        """, nativeQuery = true)
    int recordRemoved(
            @Param("workspaceId") long workspaceId,
            @Param("disclosureId") UUID disclosureId,
            @Param("participantKeys") String[] participantKeys,
            @Param("at") Instant at);

    record SubjectState(
            UUID id,
            UUID disclosureId,
            long workspaceId,
            @Nullable Long accountId,
            @Nullable Long grantDecisionId,
            String participantKey,
            @Nullable RemovalReason removalReason,
            @Nullable Instant removalRequiredAt,
            @Nullable Instant removedAt) {

        boolean isRemovalOutstanding() {
            return removalReason != null && removedAt == null;
        }
    }

    @Modifying
    @Query("DELETE FROM ResearchDisclosureSubject s WHERE s.workspaceId = :workspaceId AND s.disclosureId ="
            + " :disclosureId")
    int deleteForDisclosure(@Param("workspaceId") long workspaceId, @Param("disclosureId") UUID disclosureId);
}
