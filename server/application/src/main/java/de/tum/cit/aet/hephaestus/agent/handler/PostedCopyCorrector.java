package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.UpdateOutcome;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository.PostedCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation.ProviderCopy;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brings the comments Hephaestus already posted in line with a correction. A summary comment is edited in place
 * to open with a correction notice naming every practice whose observation behind it is invalidated, and back to
 * its original text once none is; the stored feedback never changes. Inline comments have no edit path, so a
 * correction that still has one reports it instead of claiming the work shows the correction.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Each correction reloads its observation, feedback and job through the row's tenant key")
class PostedCopyCorrector {

    private static final Logger log = LoggerFactory.getLogger(PostedCopyCorrector.class);
    private static final int BATCH_SIZE = 50;
    private static final Duration MIN_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(6);

    private final ObservationInvalidationRepository invalidations;
    private final ObservationRepository observations;
    private final FeedbackDispatchRepository dispatches;
    private final FeedbackPlacementRepository placements;
    private final AgentJobRepository jobs;
    private final PullRequestCommentPoster commentPoster;

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT20S")
    @SchedulerLock(name = "observation-posted-copy-correction", lockAtMostFor = "PT15M", lockAtLeastFor = "PT5S")
    void settleDue() {
        settleDue(Instant.now());
    }

    /** Unresolved corrections stay due too, so a copy confirmed later is still corrected. */
    void settleDue(Instant now) {
        for (ObservationInvalidation invalidation :
                invalidations.findDueProviderCopies(now, PageRequest.of(0, BATCH_SIZE))) {
            ProviderCopy outcome = null;
            try {
                outcome = settle(invalidation, now);
            } catch (RuntimeException exception) {
                log.warn("Posted-copy correction deferred: invalidationId={}", invalidation.getId(), exception);
            }
            if (outcome != null) {
                invalidations.settleProviderCopy(
                        invalidation.getWorkspaceId(),
                        invalidation.getId(),
                        invalidation.getRestoredAt(),
                        outcome.name());
            }
            if (outcome == null || outcome == ProviderCopy.UNRESOLVED) {
                invalidations.deferProviderCopy(
                        invalidation.getWorkspaceId(),
                        invalidation.getId(),
                        invalidation.getRestoredAt(),
                        now.plus(backoff(invalidation, now)));
            }
        }
    }

    /** Half the time the correction has been pending, from a minute up to six hours. */
    static Duration backoff(ObservationInvalidation invalidation, Instant now) {
        Instant restoredAt = invalidation.getRestoredAt();
        Instant pendingSince = restoredAt != null ? restoredAt : invalidation.getInvalidatedAt();
        Duration half = Duration.between(pendingSince, now).dividedBy(2);
        return half.compareTo(MIN_BACKOFF) < 0 ? MIN_BACKOFF : half.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : half;
    }

    /**
     * What became of the posted copies, or {@code null} while that is not known yet: a delivery citing the
     * observation is unsettled, or an edit can only be retried. A write left unconfirmed past its window reads as
     * unresolved meanwhile, never as nothing posted.
     */
    @Nullable
    ProviderCopy settle(ObservationInvalidation invalidation, Instant now) {
        long workspaceId = invalidation.getWorkspaceId();
        Observation observation = observations
                .findByIdAndWorkspaceId(invalidation.getObservationId(), workspaceId)
                .orElse(null);
        if (observation == null) {
            return null;
        }
        boolean inForce = invalidation.getRestoredAt() == null;
        // Every copy already known is corrected now, even while another may still be unconfirmed; the edit
        // rebuilds the body from its stored original, so repeating it changes nothing.
        boolean retry = false;
        boolean unresolved = false;
        boolean inlineRemains = false;
        boolean touchedSummary = false;
        for (PostedCopy copy : placements.findPostedCopies(workspaceId, observation.getId())) {
            if (copy.getPlacementType() != PlacementType.SUMMARY) {
                // Confirmed posted, but with no id nothing can say whether it is still there.
                unresolved |= inForce && copy.getCommentRef() == null;
                inlineRemains |= inForce;
                continue;
            }
            touchedSummary = true;
            switch (edit(workspaceId, copy)) {
                case EDITED, GONE -> {}
                case TRANSIENT -> retry = true;
                case UNSUPPORTED -> unresolved = true;
            }
        }
        if (dispatches.existsUnsettledCiting(workspaceId, observation.getId())) {
            Instant unconfirmedSince = dispatches.findUnconfirmedWriteSince(workspaceId, observation.getId());
            boolean overdue = unconfirmedSince != null
                    && now.isAfter(unconfirmedSince.plus(PracticeFeedbackDispatchService.UNCONFIRMED_WINDOW));
            return inForce && (overdue || unresolved) ? ProviderCopy.UNRESOLVED : null;
        }
        unresolved |= inForce && dispatches.existsUnconfirmedCiting(workspaceId, observation.getId());
        if (retry) {
            return null;
        }
        if (unresolved) {
            return ProviderCopy.UNRESOLVED;
        }
        if (inlineRemains) {
            return ProviderCopy.INLINE_REMAINS;
        }
        return touchedSummary ? ProviderCopy.UPDATED : ProviderCopy.NONE;
    }

    private UpdateOutcome.Kind edit(long workspaceId, PostedCopy copy) {
        AgentJob job =
                jobs.findByIdAndWorkspaceId(copy.getAgentJobId(), workspaceId).orElse(null);
        String original = copy.getBody();
        String commentRef = copy.getCommentRef();
        if (job == null || original == null || commentRef == null) {
            return UpdateOutcome.Kind.UNSUPPORTED;
        }
        String body =
                withCorrection(placements.findInvalidatedPracticeNames(workspaceId, copy.getFeedbackId()), original);
        UpdateOutcome outcome = commentPoster.editSummary(
                job, commentRef, body, Boolean.TRUE.equals(copy.getApproved()) ? copy.getFeedbackId() : null);
        if (outcome.kind() == UpdateOutcome.Kind.TRANSIENT) {
            log.info(
                    "Posted-copy correction will retry: feedbackId={}, reason={}",
                    copy.getFeedbackId(),
                    outcome.reason());
        }
        return outcome.kind();
    }

    static String withCorrection(List<String> invalidatedPractices, String original) {
        if (invalidatedPractices.isEmpty()) {
            return original;
        }
        List<String> names = invalidatedPractices.stream()
                .map(name -> "**" + PullRequestCommentPoster.sanitize(name) + "**")
                .toList();
        String joined = names.size() == 1
                ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
        return "> **Correction:** a workspace admin marked what this review says about " + joined
                + " as incorrect. Please disregard that part.\n\n" + original;
    }
}
