package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@RawObservationAccess
public interface PracticeAdviceRevisionRepository extends JpaRepository<PracticeAdviceRevision, UUID> {

    Optional<PracticeAdviceRevision> findByAdviceIdAndWorkspaceIdAndNumber(UUID adviceId, long workspaceId, int number);

    Optional<PracticeAdviceRevision> findByIdAndWorkspaceIdAndPersonAccountId(
            UUID id, long workspaceId, long personAccountId);

    /** Locks, in id order, every revision with a source among {@code resultIds}. */
    @Query(
            value = "SELECT r.id FROM practice_advice_revision r WHERE r.workspace_id=:workspaceId AND EXISTS("
                    + "SELECT 1 FROM practice_advice_source s WHERE s.workspace_id=r.workspace_id"
                    + " AND s.revision_id=r.id AND s.result_id=ANY(CAST(:resultIds AS uuid[])))"
                    + " ORDER BY r.id FOR UPDATE",
            nativeQuery = true)
    List<UUID> lockWithSourcesIn(@Param("workspaceId") long workspaceId, @Param("resultIds") UUID[] resultIds);

    /** Removes the text and marks the revisions stale. A revision that is already stale keeps its first time. */
    @Modifying
    @Query(
            value = "UPDATE practice_advice_revision SET text=NULL, stale_at=COALESCE(stale_at, clock_timestamp())"
                    + " WHERE workspace_id=:workspaceId AND id=ANY(CAST(:ids AS uuid[]))",
            nativeQuery = true)
    int redact(@Param("workspaceId") long workspaceId, @Param("ids") UUID[] ids);

    /** A new revision, written in the transaction that holds its entry's lock. */
    @Modifying
    @Query(
            value =
                    "INSERT INTO practice_advice_revision (id, workspace_id, person_account_id, advice_id, number, text,"
                            + " review_id, cited_count, source_count, written_at) VALUES (:id, :workspaceId, :accountId,"
                            + " :adviceId, :number, :text, :reviewId, :citedCount, :sourceCount, clock_timestamp())",
            nativeQuery = true)
    int insertWritten(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("accountId") long accountId,
            @Param("adviceId") UUID adviceId,
            @Param("number") int number,
            @Param("text") String text,
            @Param("reviewId") UUID reviewId,
            @Param("citedCount") int citedCount,
            @Param("sourceCount") int sourceCount);
}
