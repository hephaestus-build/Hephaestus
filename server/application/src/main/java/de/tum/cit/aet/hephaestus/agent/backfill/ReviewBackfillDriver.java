package de.tum.cit.aet.hephaestus.agent.backfill;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Walks each confirmed campaign through its scope, one bounded batch per tick.
 *
 * <p>A campaign whose budget runs out pauses with its cursor unchanged rather than let each submission be
 * refused, so the resulting baseline stays truncated instead of gap-toothed; anything that still crosses
 * the cap mid-batch lands in a PENDING ledger state that {@code PendingSignalReaper} re-offers later. A
 * paused campaign resumes automatically once the blocking reason clears — the admin-approved estimate
 * already covers the whole scope.
 */
@ConditionalOnServerRole
@Component
@ConditionalOnProperty(prefix = "hephaestus.agent", name = "enabled", havingValue = "true")
@WorkspaceAgnostic("Confirmed campaigns are driven for every workspace on the instance")
public class ReviewBackfillDriver {

    private static final Logger log = LoggerFactory.getLogger(ReviewBackfillDriver.class);

    /** Bounds how many campaigns share one tick, so no single run monopolises the driver. */
    private static final int MAX_RUNS_PER_TICK = 5;

    private final ReviewBackfillRunRepository runRepository;
    private final ReviewBackfillScopeRepository scopeRepository;
    private final ReviewBackfillSubmitter submitter;
    private final WorkspaceAgentBindingRepository bindingRepository;
    private final LlmBudgetService llmBudgetService;
    private final ReviewBackfillProperties properties;

    public ReviewBackfillDriver(
            ReviewBackfillRunRepository runRepository,
            ReviewBackfillScopeRepository scopeRepository,
            ReviewBackfillSubmitter submitter,
            WorkspaceAgentBindingRepository bindingRepository,
            LlmBudgetService llmBudgetService,
            ReviewBackfillProperties properties) {
        this.runRepository = runRepository;
        this.scopeRepository = scopeRepository;
        this.submitter = submitter;
        this.bindingRepository = bindingRepository;
        this.llmBudgetService = llmBudgetService;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 2, initialDelay = 2, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "review-backfill-driver", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @WorkspaceAgnostic("Sweeps campaigns across every workspace")
    public void tick() {
        List<ReviewBackfillRun> active = runRepository.findByStatusIn(
                List.of(ReviewBackfillStatus.RUNNING, ReviewBackfillStatus.PAUSED),
                PageRequest.ofSize(MAX_RUNS_PER_TICK));
        for (ReviewBackfillRun run : active) {
            try {
                advance(run);
            } catch (RuntimeException e) {
                // A run's own failure — the scope query, or a losing optimistic-lock write — must not stop the
                // others. The run keeps its persisted cursor, so the next tick resumes from the last committed
                // batch, and the ledger's unique key stops that resume from re-reviewing what already ran.
                log.warn("Review backfill batch failed: runId={}", run.getId(), e);
            }
        }
    }

    /**
     * Deliberately holds no transaction of its own: each artifact's turn opens one via
     * {@link ReviewBackfillSubmitter#offer} ({@code REQUIRES_NEW}), so a single failure cannot unwind the
     * batch around it.
     */
    void advance(ReviewBackfillRun run) {
        if (!run.getStatus().isActive()) {
            return;
        }
        Workspace workspace = run.getWorkspace();

        ReviewBackfillPauseReason blocked = blockingReason(workspace);
        if (blocked != null) {
            if (run.getStatus() != ReviewBackfillStatus.PAUSED || run.getPauseReason() != blocked) {
                log.info("Review backfill paused: runId={}, reason={}", run.getId(), blocked);
                run.transitionTo(ReviewBackfillStatus.PAUSED, blocked);
                runRepository.save(run);
            }
            return;
        }
        if (run.getStatus() == ReviewBackfillStatus.PAUSED) {
            log.info("Review backfill resumed: runId={}", run.getId());
            run.transitionTo(ReviewBackfillStatus.RUNNING, null);
        }

        List<Long> batch = nextBatch(run);
        if (batch.isEmpty()) {
            log.info(
                    "Review backfill complete: runId={}, submitted={}, passed={}, failed={}",
                    run.getId(),
                    run.getSubmittedCount(),
                    run.getPassedCount(),
                    run.getFailedCount());
            run.transitionTo(ReviewBackfillStatus.COMPLETED, null);
            runRepository.save(run);
            return;
        }

        int submitted = 0;
        int passed = 0;
        int failed = 0;
        long cursor = run.getCursorArtifactId() == null ? 0L : run.getCursorArtifactId();
        for (Long artifactId : batch) {
            ReviewBackfillSubmitter.Outcome outcome = null;
            try {
                outcome = submitter.offer(run, artifactId);
            } catch (RuntimeException e) {
                // The cursor advances past a failure on purpose: aborting here would leave it unwritten and let a
                // deterministically failing artifact freeze the campaign forever. The failure is counted
                // separately from passes, so it can never let submitted + passed reach the estimate and report
                // COMPLETED over a baseline with a hole in it.
                log.warn(
                        "Review backfill artifact failed, walking past it: runId={}, artifactId={}",
                        run.getId(),
                        artifactId,
                        e);
                failed++;
            }
            if (outcome == ReviewBackfillSubmitter.Outcome.SUBMITTED) {
                submitted++;
            } else if (outcome != null) {
                passed++;
            }
            cursor = artifactId;
        }
        run.setCursorArtifactId(cursor);
        run.setSubmittedCount(run.getSubmittedCount() + submitted);
        run.setPassedCount(run.getPassedCount() + passed);
        run.setFailedCount(run.getFailedCount() + failed);
        run.setUpdatedAt(java.time.Instant.now());
        runRepository.save(run);
        log.info(
                "Review backfill batch: runId={}, submitted={}, passed={}, failed={}, cursor={}",
                run.getId(),
                submitted,
                passed,
                failed,
                cursor);
    }

    /**
     * Why this workspace cannot be spent against right now, or {@code null} if it can.
     *
     * <p>Checked once per batch, not per artifact: the usage ledger only gains a row when a job ends, so a
     * per-artifact check would cost an extra query without being any fresher.
     */
    private @Nullable ReviewBackfillPauseReason blockingReason(Workspace workspace) {
        if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE) {
            return ReviewBackfillPauseReason.WORKSPACE_UNAVAILABLE;
        }
        if (!Boolean.TRUE.equals(workspace.getFeatures().getPracticesEnabled())) {
            return ReviewBackfillPauseReason.WORKSPACE_UNAVAILABLE;
        }
        var bindings = bindingRepository.findByWorkspaceIdWithModels(workspace.getId()).stream()
                .filter(binding -> binding.getPurpose() == AgentPurpose.PRACTICE_REVIEW)
                .filter(WorkspaceAgentBinding::isEnabled)
                .toList();
        if (bindings.isEmpty()) return ReviewBackfillPauseReason.REVIEW_MODEL_UNBOUND;
        if (bindings.stream()
                .allMatch(binding ->
                        llmBudgetService.blockSubmission(workspace, "REVIEW_BACKFILL", binding.getFundingSource()))) {
            return ReviewBackfillPauseReason.BUDGET_EXHAUSTED;
        }
        return null;
    }

    private List<Long> nextBatch(ReviewBackfillRun run) {
        long after = run.getCursorArtifactId() == null ? 0L : run.getCursorArtifactId();
        var page = PageRequest.ofSize(properties.batchSize());
        return ArtifactKinds.PULL_REQUEST.equals(run.kind())
                ? scopeRepository.findPullRequestIds(
                        run.getWorkspace().getId(), run.getFromAt(), run.getToAt(), after, page)
                : scopeRepository.findIssueIds(run.getWorkspace().getId(), run.getFromAt(), run.getToAt(), after, page);
    }
}
