package de.tum.cit.aet.hephaestus.practices.advice;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Advice entries. Every write locks its entry first and raises {@code version} by one. */
public interface PracticeAdviceRepository extends JpaRepository<PracticeAdvice, UUID> {

    @Query(
            value = "SELECT a.* FROM practice_advice a WHERE a.workspace_id=:workspaceId"
                    + " AND a.person_account_id=:accountId AND a.practice_id=:practiceId FOR UPDATE",
            nativeQuery = true)
    Optional<PracticeAdvice> lockEntry(
            @Param("workspaceId") long workspaceId,
            @Param("accountId") long accountId,
            @Param("practiceId") long practiceId);

    /** The first write's entry at version 0; nothing when another write created it first. */
    @Modifying
    @Query(
            value = "INSERT INTO practice_advice (id, workspace_id, person_account_id, practice_id, state, version,"
                    + " created_at) VALUES (:id, :workspaceId, :accountId, :practiceId, 'CURRENT', 0, clock_timestamp())"
                    + " ON CONFLICT (workspace_id, person_account_id, practice_id) DO NOTHING",
            nativeQuery = true)
    int insertFirst(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("accountId") long accountId,
            @Param("practiceId") long practiceId);

    /** A new revision makes the entry current again and ends the owner's dismissal. */
    @Modifying
    @Query(
            value =
                    "UPDATE practice_advice SET state='CURRENT', retire_reason=NULL, retired_at=NULL, dismissed_at=NULL,"
                            + " dismissed_items=NULL, version=version+1, latest_revision=:number"
                            + " WHERE id=:id AND workspace_id=:workspaceId",
            nativeQuery = true)
    int markRevised(@Param("id") UUID id, @Param("workspaceId") long workspaceId, @Param("number") int number);

    /** The mentor retires the entry; the owner's dismissal stays as it is. */
    @Modifying
    @Query(
            value = "UPDATE practice_advice SET state='RETIRED', retire_reason=:reason, retired_at=clock_timestamp(),"
                    + " version=version+1 WHERE id=:id AND workspace_id=:workspaceId",
            nativeQuery = true)
    int markRetired(@Param("id") UUID id, @Param("workspaceId") long workspaceId, @Param("reason") String reason);
}
