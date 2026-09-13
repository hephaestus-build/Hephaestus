package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SurveyRepository extends JpaRepository<Survey, UUID> {
    /**
     * Every unpaused survey whichever workspace it targets; the caller applies {@link Survey#isOpenFor}
     * so the schedule and the workspace predicate have one home.
     */
    List<Survey> findAllByActiveTrueOrderByStartsAtAscCreatedAtAsc();

    @WorkspaceAgnostic(
            "The instance scheduler discovers ended surveys; delivery independently rechecks opted-in instance administrators")
    @Query(
            "SELECT s FROM Survey s WHERE s.summaryQueuedAt IS NULL AND s.endsAt <= :now AND s.endsAt > :oldest ORDER BY s.endsAt")
    List<Survey> findEndedWithoutSummary(@Param("now") Instant now, @Param("oldest") Instant oldest, Pageable pageable);

    @Modifying
    @WorkspaceAgnostic("The instance scheduler claims one survey and its exact end time, never a workspace-wide write")
    @Query(
            "UPDATE Survey s SET s.summaryQueuedAt = :now WHERE s.id = :id AND s.endsAt = :expectedEndedAt AND s.summaryQueuedAt IS NULL")
    int claimSummary(
            @Param("id") UUID id, @Param("expectedEndedAt") Instant expectedEndedAt, @Param("now") Instant now);

    void deleteAllByWorkspaceId(Long workspaceId);
}
