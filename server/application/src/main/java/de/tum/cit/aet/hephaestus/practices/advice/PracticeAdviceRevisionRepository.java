package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@RawObservationAccess
public interface PracticeAdviceRevisionRepository extends JpaRepository<PracticeAdviceRevision, UUID> {

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
}
