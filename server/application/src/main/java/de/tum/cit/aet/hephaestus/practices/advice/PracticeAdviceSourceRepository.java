package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The observations behind each advice revision, as references. */
@RawObservationAccess
public interface PracticeAdviceSourceRepository extends JpaRepository<PracticeAdviceSource, UUID> {

    List<PracticeAdviceSource> findByWorkspaceIdAndRevisionIdOrderByOrdinalAsc(long workspaceId, UUID revisionId);

    /** {@code problemId} is empty for a source without a problem, so the statement never binds an untyped null. */
    @Modifying
    @Query(
            value = "INSERT INTO practice_advice_source (id, workspace_id, person_account_id, revision_id, result_id,"
                    + " problem_id, cited, ordinal) VALUES (:id, :workspaceId, :accountId, :revisionId, :resultId,"
                    + " CAST(NULLIF(:problemId, '') AS uuid), :cited, :ordinal)",
            nativeQuery = true)
    int insertSource(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("accountId") long accountId,
            @Param("revisionId") UUID revisionId,
            @Param("resultId") UUID resultId,
            @Param("problemId") String problemId,
            @Param("cited") boolean cited,
            @Param("ordinal") int ordinal);
}
