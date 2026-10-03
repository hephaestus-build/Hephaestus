package de.tum.cit.aet.hephaestus.agent.backfill;

import de.tum.cit.aet.hephaestus.agent.backfill.dto.CreateReviewSweepScheduleRequestDTO;
import de.tum.cit.aet.hephaestus.agent.backfill.dto.ReviewSweepScheduleDTO;
import de.tum.cit.aet.hephaestus.agent.backfill.dto.UpdateReviewSweepScheduleRequestDTO;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin-facing half of a recurring sweep: describe it, change its terms, switch it off, delete it.
 *
 * <p>Nothing here starts a review. {@link ReviewSweepScheduler} is what acts on these rows, and it runs
 * on its own clock — which is the point of the split: an admin editing a cadence should never find
 * themselves holding a request that is submitting reviews.
 */
@Service
public class ReviewSweepScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ReviewSweepScheduleService.class);

    private final ReviewSweepScheduleRepository scheduleRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ConfigAuditPort configAudit;

    public ReviewSweepScheduleService(
            ReviewSweepScheduleRepository scheduleRepository,
            WorkspaceRepository workspaceRepository,
            ConfigAuditPort configAudit) {
        this.scheduleRepository = scheduleRepository;
        this.workspaceRepository = workspaceRepository;
        this.configAudit = configAudit;
    }

    @Transactional(readOnly = true)
    public List<ReviewSweepScheduleDTO> list(WorkspaceContext context) {
        return scheduleRepository.findByWorkspaceIdOrderByArtifactKindAsc(context.id()).stream()
                .map(ReviewSweepScheduleDTO::from)
                .toList();
    }

    @Transactional
    public ReviewSweepScheduleDTO create(WorkspaceContext context, CreateReviewSweepScheduleRequestDTO request) {
        ArtifactKind kind = request.artifactKind();
        // Throws by name for a kind no campaign can enumerate, rather than an empty scope that would read
        // on screen as "there is nothing to review".
        ReviewBackfillService.jobTypeFor(kind);
        validateLookback(request.cadence(), request.lookbackDays());
        if (scheduleRepository.existsByWorkspaceIdAndArtifactKind(context.id(), kind.value())) {
            throw new ReviewSweepScheduleConflictException(
                    "This workspace already has a recurring check for this kind of work. "
                            + "Change that check. Do not add a second one.");
        }
        Workspace workspace = workspaceRepository
                .findById(context.id())
                .orElseThrow(() -> new EntityNotFoundException("Workspace", context.slug()));

        ReviewSweepSchedule schedule = new ReviewSweepSchedule();
        schedule.setWorkspace(workspace);
        schedule.setArtifactKind(kind.value());
        schedule.setCadence(request.cadence());
        schedule.setLookbackDays(request.lookbackDays());
        schedule.setEnabled(true);
        schedule.setNextRunAt(ReviewSweepSchedule.firstRunAt(context.id(), Instant.now()));
        schedule.setCreatedByAccountId(SecurityUtils.getCurrentAccountId()
                .orElseThrow(() ->
                        // A schedule nobody can be named for is a recurring spend nobody can be asked about.
                        new IllegalStateException(
                                "A sweep schedule must be attributable to an authenticated account.")));
        ReviewSweepSchedule saved = scheduleRepository.save(schedule);
        configAudit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.REVIEW_SWEEP_SCHEDULE,
                saved.getId(),
                context.id(),
                ReviewSweepScheduleSnapshot.of(saved)));
        log.info(
                "Review sweep schedule created: scheduleId={}, workspaceId={}, kind={}, cadence={}, lookbackDays={}",
                saved.getId(),
                context.id(),
                kind.value(),
                saved.getCadence(),
                saved.getLookbackDays());
        return ReviewSweepScheduleDTO.from(saved);
    }

    @Transactional
    public ReviewSweepScheduleDTO replace(
            WorkspaceContext context, UUID scheduleId, UpdateReviewSweepScheduleRequestDTO request) {
        validateLookback(request.cadence(), request.lookbackDays());
        ReviewSweepSchedule schedule = scheduleRepository
                .findByIdAndWorkspaceId(scheduleId, context.id())
                .orElseThrow(() -> new EntityNotFoundException("ReviewSweepSchedule", scheduleId.toString()));
        ReviewSweepScheduleSnapshot before = ReviewSweepScheduleSnapshot.of(schedule);

        // Keep nextRunAt's phase: narrowing a lookback is not a request to move the sweep's time of day.
        schedule.setCadence(request.cadence());
        schedule.setLookbackDays(request.lookbackDays());
        schedule.setEnabled(request.enabled());
        schedule.setUpdatedAt(Instant.now());
        ReviewSweepSchedule saved = scheduleRepository.save(schedule);

        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.REVIEW_SWEEP_SCHEDULE,
                saved.getId(),
                context.id(),
                before,
                ReviewSweepScheduleSnapshot.of(saved)));
        return ReviewSweepScheduleDTO.from(saved);
    }

    /**
     * The campaigns this schedule already opened keep their rows and attribution — they describe money
     * that was spent, which deleting the instruction does not undo.
     */
    @Transactional
    public void delete(WorkspaceContext context, UUID scheduleId) {
        ReviewSweepSchedule schedule = scheduleRepository
                .findByIdAndWorkspaceId(scheduleId, context.id())
                .orElseThrow(() -> new EntityNotFoundException("ReviewSweepSchedule", scheduleId.toString()));
        configAudit.record(ConfigAuditEntry.deleted(
                ConfigAuditEntityType.REVIEW_SWEEP_SCHEDULE,
                schedule.getId(),
                context.id(),
                ReviewSweepScheduleSnapshot.of(schedule)));
        scheduleRepository.delete(schedule);
        log.info("Review sweep schedule deleted: scheduleId={}, workspaceId={}", scheduleId, context.id());
    }

    /**
     * A window longer than twice the cadence is not a recurrence any more but a corpus chosen with
     * hindsight — filed in the live population, that is how a trend line comes to show an improvement
     * nobody made.
     */
    private static void validateLookback(ReviewSweepCadence cadence, int lookbackDays) {
        if (lookbackDays < 1) {
            throw new IllegalArgumentException("A recurring check must look back at least one day.");
        }
        Duration lookback = Duration.ofDays(lookbackDays);
        Duration max = cadence.maxLookback();
        if (lookback.compareTo(max) > 0) {
            throw new IllegalArgumentException("A "
                    + switch (cadence) {
                        case DAILY -> "daily";
                        case WEEKLY -> "weekly";
                    }
                    + " recurring check can look back at most "
                    + max.toDays()
                    + " days. The value "
                    + lookbackDays
                    + " is too high. To review older work, start a backfill under “Review past work”. "
                    + "The server counts a backfill apart from live work.");
        }
    }
}
