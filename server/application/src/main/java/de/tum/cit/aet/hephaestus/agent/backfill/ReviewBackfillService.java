package de.tum.cit.aet.hephaestus.agent.backfill;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.backfill.dto.CreateReviewBackfillRunRequestDTO;
import de.tum.cit.aet.hephaestus.agent.backfill.dto.ReviewBackfillRunDTO;
import de.tum.cit.aet.hephaestus.agent.backfill.dto.UpdateReviewBackfillRunStatusRequestDTO.RequestedReviewBackfillStatus;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin-facing half of a campaign: enumerate, cost, confirm, cancel, report.
 *
 * <p>Everything that spends money lives in {@link ReviewBackfillDriver}. The split is the point — this
 * service can be called freely without a review ever running, which is what makes the estimate safe to
 * produce and re-produce while an admin narrows the window.
 */
@Service
public class ReviewBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ReviewBackfillService.class);

    /** Statuses that mean a campaign is already under way and a second one must not start. */
    private static final List<ReviewBackfillStatus> UNDER_WAY =
            List.of(ReviewBackfillStatus.RUNNING, ReviewBackfillStatus.PAUSED);

    private static final int MAX_LISTED = 20;

    private final ReviewBackfillRunRepository runRepository;
    private final ReviewBackfillScopeRepository scopeRepository;
    private final ReviewBackfillCostEstimator costEstimator;
    private final WorkspaceRepository workspaceRepository;
    private final ReviewBackfillProperties properties;
    private final ConfigAuditPort configAudit;

    public ReviewBackfillService(
            ReviewBackfillRunRepository runRepository,
            ReviewBackfillScopeRepository scopeRepository,
            ReviewBackfillCostEstimator costEstimator,
            WorkspaceRepository workspaceRepository,
            ReviewBackfillProperties properties,
            ConfigAuditPort configAudit) {
        this.runRepository = runRepository;
        this.scopeRepository = scopeRepository;
        this.costEstimator = costEstimator;
        this.workspaceRepository = workspaceRepository;
        this.properties = properties;
        this.configAudit = configAudit;
    }

    static AgentJobType jobTypeFor(ArtifactKind kind) {
        if (ArtifactKinds.PULL_REQUEST.equals(kind)) {
            return AgentJobType.PULL_REQUEST_REVIEW;
        }
        if (ArtifactKinds.ISSUE.equals(kind)) {
            return AgentJobType.ISSUE_REVIEW;
        }
        // A conversation thread has no mirrored corpus to walk — the threads a campaign would sweep are
        // not enumerable from a repository the way pull requests and issues are. Refused by name rather
        // than silently producing an empty scope, which would read as "nothing to review".
        throw new IllegalArgumentException(
                "You can review past work only for a pull or merge request or an issue. You cannot review past work of this kind.");
    }

    private static String statusWords(ReviewBackfillStatus status) {
        return switch (status) {
            case AWAITING_CONFIRMATION -> "waiting for confirmation";
            case RUNNING -> "already running";
            case PAUSED -> "paused";
            case COMPLETED -> "already finished";
            case CANCELLED -> "already canceled";
        };
    }

    /**
     * Enumerate and cost a campaign without starting it.
     *
     * <p>Supersedes any earlier unconfirmed estimate for this workspace: an estimate nobody acted on is a
     * draft, and leaving stale drafts around to block a corrected one would make the confirmation step
     * feel like an obstacle rather than a decision. An estimate that <em>was</em> acted on is a different
     * matter, and a second campaign is refused while one is under way.
     */
    @Transactional
    public ReviewBackfillRunDTO preflight(WorkspaceContext context, CreateReviewBackfillRunRequestDTO request) {
        ArtifactKind kind = request.artifactKind();
        AgentJobType jobType = jobTypeFor(kind);
        Instant fromAt = request.fromAt();
        Instant toAt = request.toAt();
        if (!fromAt.isBefore(toAt)) {
            throw new IllegalArgumentException("The backfill window must start before it ends.");
        }
        Duration window = Duration.between(fromAt, toAt);
        if (window.compareTo(properties.maxWindow()) > 0) {
            throw new IllegalArgumentException("The backfill window covers " + window.toDays()
                    + " days. The limit is "
                    + properties.maxWindow().toDays()
                    + " days.");
        }
        if (runRepository.existsByWorkspaceIdAndStatusIn(context.id(), UNDER_WAY)) {
            throw new ReviewBackfillConflictException(
                    "A backfill is already active for this workspace. To start another backfill, first cancel the active backfill.");
        }
        supersedeUnconfirmed(context.id());

        long inScope = countScope(context.id(), kind, fromAt, toAt);
        if (inScope > properties.maxArtifacts()) {
            throw new IllegalArgumentException("The backfill window covers " + inScope
                    + " pieces of work. The limit is "
                    + properties.maxArtifacts()
                    + ". Narrow the window.");
        }
        BigDecimal estimate = costEstimator.estimateTotalUsd(context.id(), jobType, (int) inScope);

        Workspace workspace = workspaceRepository
                .findById(context.id())
                .orElseThrow(() -> new EntityNotFoundException("Workspace", context.slug()));

        ReviewBackfillRun run = new ReviewBackfillRun();
        run.setWorkspace(workspace);
        run.setArtifactKind(kind.value());
        run.setFromAt(fromAt);
        run.setToAt(toAt);
        run.setStatus(ReviewBackfillStatus.AWAITING_CONFIRMATION);
        run.setEstimatedArtifacts((int) inScope);
        run.setEstimatedCostUsd(estimate);
        run.setRequestedByAccountId(SecurityUtils.getCurrentAccountId()
                .orElseThrow(() ->
                        // Attribution is not optional on a campaign that can spend a month's budget: an
                        // unattributable run is one nobody can be asked about afterwards.
                        new IllegalStateException("A backfill must be attributable to an authenticated account.")));
        ReviewBackfillRun saved = runRepository.save(run);
        configAudit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.REVIEW_BACKFILL_RUN,
                saved.getId(),
                context.id(),
                ReviewBackfillSnapshot.of(saved)));
        log.info(
                "Review backfill preflight: workspaceId={}, kind={}, artifacts={}, estimateUsd={}, runId={}",
                context.id(),
                kind.value(),
                inScope,
                estimate,
                saved.getId());
        return ReviewBackfillRunDTO.from(saved);
    }

    /**
     * Confirm or cancel a campaign.
     *
     * <p>Confirmation is the only place the estimate turns into permission to spend, so it records who
     * gave it. A paused run is confirmable again, which is how an admin restarts one that stopped on an
     * exhausted budget without waiting for the driver's own retry.
     *
     * <p>Retried on an optimistic-lock failure rather than reported: the only contender is the driver's own
     * save mid-batch, and reporting that conflict would fail the cancel button exactly while a campaign is
     * spending — the one moment it has to work.
     */
    @Retryable(
            includes = {OptimisticLockingFailureException.class},
            maxRetries = 3,
            delay = 50)
    @Transactional
    public ReviewBackfillRunDTO updateStatus(
            WorkspaceContext context, UUID runId, RequestedReviewBackfillStatus requested) {
        ReviewBackfillRun run = runRepository
                .findByIdAndWorkspaceId(runId, context.id())
                .orElseThrow(() -> new EntityNotFoundException("ReviewBackfillRun", runId.toString()));
        ReviewBackfillSnapshot before = ReviewBackfillSnapshot.of(run);

        switch (requested) {
            case RUNNING -> {
                if (!run.getStatus().isConfirmable()) {
                    throw new ReviewBackfillConflictException(
                            "You cannot start this backfill because it is " + statusWords(run.getStatus()) + ".");
                }
                if (run.getStartedAt() == null) {
                    run.setStartedAt(Instant.now());
                }
                run.setConfirmedByAccountId(SecurityUtils.getCurrentAccountId()
                        .orElseThrow(() ->
                                new IllegalStateException("Confirming a backfill requires an authenticated account.")));
                run.transitionTo(ReviewBackfillStatus.RUNNING, null);
                log.info(
                        "Review backfill confirmed: runId={}, workspaceId={}, artifacts={}, estimateUsd={}",
                        run.getId(),
                        context.id(),
                        run.getEstimatedArtifacts(),
                        run.getEstimatedCostUsd());
            }
            case CANCELLED -> {
                if (!run.getStatus().isActive() && run.getStatus() != ReviewBackfillStatus.AWAITING_CONFIRMATION) {
                    throw new ReviewBackfillConflictException(
                            "You cannot cancel this backfill because it is " + statusWords(run.getStatus()) + ".");
                }
                run.transitionTo(ReviewBackfillStatus.CANCELLED, null);
                log.info("Review backfill cancelled: runId={}, workspaceId={}", run.getId(), context.id());
            }
        }
        ReviewBackfillRun saved = runRepository.save(run);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.REVIEW_BACKFILL_RUN,
                saved.getId(),
                context.id(),
                before,
                ReviewBackfillSnapshot.of(saved)));
        return ReviewBackfillRunDTO.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ReviewBackfillRunDTO> list(WorkspaceContext context) {
        return runRepository
                .findByWorkspaceIdOrderByCreatedAtDesc(context.id(), PageRequest.ofSize(MAX_LISTED))
                .stream()
                .map(ReviewBackfillRunDTO::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReviewBackfillRunDTO get(WorkspaceContext context, UUID runId) {
        return runRepository
                .findByIdAndWorkspaceId(runId, context.id())
                .map(ReviewBackfillRunDTO::from)
                .orElseThrow(() -> new EntityNotFoundException("ReviewBackfillRun", runId.toString()));
    }

    private void supersedeUnconfirmed(Long workspaceId) {
        for (ReviewBackfillRun previous :
                runRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId, PageRequest.ofSize(MAX_LISTED))) {
            if (previous.getStatus() == ReviewBackfillStatus.AWAITING_CONFIRMATION) {
                previous.transitionTo(ReviewBackfillStatus.CANCELLED, null);
                runRepository.save(previous);
            }
        }
    }

    private long countScope(Long workspaceId, ArtifactKind kind, Instant fromAt, Instant toAt) {
        return ArtifactKinds.PULL_REQUEST.equals(kind)
                ? scopeRepository.countPullRequests(workspaceId, fromAt, toAt)
                : scopeRepository.countIssues(workspaceId, fromAt, toAt);
    }
}
