package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@WorkspaceAgnostic(
        "Workspace-scoped via custom queries that all include workspaceId; PK-only DML allowed for delete/save")
public interface PracticeRepository extends JpaRepository<Practice, Long> {
    /**
     * Every practice of the workspace, at any autonomy — including {@code OFF}, so the detection gate can tell
     * "nothing is bound to this signal" apart from "something is bound and turned off".
     */
    @EntityGraph(attributePaths = {"group", "currentRevision"})
    List<Practice> findByWorkspaceId(Long workspaceId);

    /**
     * Every practice of the workspace for one work type, at any autonomy. Deliberately unfiltered: pushing
     * {@code autonomy <> 'OFF'} into SQL is a trap once the column can be null, since
     * {@code NULL <> 'OFF'} is UNKNOWN and an inheriting practice would silently vanish from the query.
     * Autonomy is resolved in the JVM by {@link de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver}.
     */
    @EntityGraph(attributePaths = {"group", "currentRevision"})
    List<Practice> findByWorkspaceIdAndArtifactKind(Long workspaceId, ArtifactKind artifactKind);

    /**
     * The raw autonomy columns of every practice in a workspace, with its group's, for callers that only need to
     * count or test autonomy states without hydrating the whole catalogue.
     */
    @Query("""
        SELECT p.autonomy AS practiceAutonomy, a.autonomy AS groupAutonomy,
               a.id AS groupId, p.artifactKind AS artifactKind
        FROM Practice p
        LEFT JOIN p.group a
        WHERE p.workspace.id = :workspaceId
        """)
    List<PracticeAutonomyRow> findAutonomyRows(@Param("workspaceId") Long workspaceId);

    /** One practice's autonomy and its group's, without hydrating either entity. */
    interface PracticeAutonomyRow {
        @Nullable
        PracticeAutonomy getPracticeAutonomy();

        @Nullable
        PracticeAutonomy getGroupAutonomy();

        @Nullable
        Long getGroupId();

        ArtifactKind getArtifactKind();
    }

    @EntityGraph(attributePaths = {"group", "currentRevision"})
    Optional<Practice> findByWorkspaceIdAndSlug(Long workspaceId, String slug);

    @EntityGraph(attributePaths = {"group"})
    List<Practice> findByWorkspaceIdAndSlugIn(Long workspaceId, Collection<String> slugs);

    @EntityGraph(attributePaths = {"group"})
    @Query("""
        SELECT DISTINCT fo.observation.practice FROM FeedbackObservation fo
        WHERE fo.feedback.id = :feedbackId
          AND fo.feedback.workspaceId = :workspaceId
        """)
    List<Practice> findContributingPractices(
            @Param("workspaceId") Long workspaceId, @Param("feedbackId") java.util.UUID feedbackId);

    List<Practice> findByWorkspaceIdAndGroupIdOrderByDisplayOrderAscNameAsc(Long workspaceId, Long groupId);

    @Query("""
        SELECT COALESCE(MAX(p.displayOrder), -1)
        FROM Practice p
        WHERE p.workspace.id = :workspaceId
        AND ((:groupId IS NULL AND p.group IS NULL) OR p.group.id = :groupId)
        """)
    int findMaxDisplayOrder(@Param("workspaceId") Long workspaceId, @Param("groupId") @Nullable Long groupId);

    /**
     * Row-level write lock ({@code SELECT ... FOR UPDATE}) held for the read-max-then-insert that appends a
     * {@link PracticeRevision}, so concurrent criteria edits get distinct, gap-free revision numbers instead
     * of colliding on {@code uk_practice_revision_practice_number}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Practice p WHERE p.id = :id")
    Optional<Practice> findByIdForUpdate(@Param("id") Long id);

    boolean existsByWorkspaceId(Long workspaceId);

    boolean existsByWorkspaceIdAndSlug(Long workspaceId, String slug);

    @Query("SELECT DISTINCT p.id FROM Practice p JOIN p.currentRevision current, PracticeRevision previous "
            + "WHERE p.sourceCuratedSlug IS NOT NULL "
            + "AND previous.practice = p "
            + "AND previous.revisionNumber = current.revisionNumber - 1 "
            + "AND p.sourceCuratedFingerprint = previous.reviewRuleFingerprint "
            + "AND p.sourceCuratedFingerprint LIKE 'v1:%'")
    List<Long> findSourceAlignedV1PracticeIds();

    @Query(
            value =
                    "SELECT id FROM practice WHERE automated_review_policy ->> 'sourceContractVersion' = :version ORDER BY id",
            nativeQuery = true)
    List<Long> findIdsBySourceContractVersion(@Param("version") String version);

    /**
     * Every practice of a workspace in the order the admin catalogue shows them, groups first. No autonomy
     * predicate: filtering to an autonomy means filtering to an <em>effective</em> autonomy, which is not a column
     * on this row — the caller resolves, then filters.
     */
    @EntityGraph(attributePaths = {"group", "currentRevision"})
    @Query("""
        SELECT p FROM Practice p
        LEFT JOIN FETCH p.group a
        WHERE p.workspace.id = :workspaceId
        ORDER BY a.displayOrder ASC NULLS LAST, p.displayOrder ASC, p.name ASC
        """)
    List<Practice> findAllForCatalog(@Param("workspaceId") Long workspaceId);

    /** Deletes all practices for the workspace. Cascades to observation via ON DELETE CASCADE. */
    @Modifying
    @Transactional
    @Query("DELETE FROM Practice p WHERE p.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);
}
