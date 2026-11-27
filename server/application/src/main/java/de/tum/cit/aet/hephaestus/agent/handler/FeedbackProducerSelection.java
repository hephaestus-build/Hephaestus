package de.tum.cit.aet.hephaestus.agent.handler;

import java.util.Set;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.agent.job.FeedbackProducerReason;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorReviewIntake;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackProducer;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewProducerPin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSealed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chooses the producer of a sealed item review once, and pins it on the review (ADR 0160). Every later seal of the
 * review takes the pin. The mentor produces only for a pull or merge request whose practices are all about its author,
 * and only when that author qualifies; another such review goes to its work place. Every other review stays LEGACY.
 * The author qualifies with a linked, active account that made an explicit AI choice and keeps practice feedback
 * delivery on, a current, visible membership, and a write fence that admits them.
 */
@Component
@ConditionalOnServerRole
public class FeedbackProducerSelection {
    private static final Logger log = LoggerFactory.getLogger(FeedbackProducerSelection.class);

    public record Choice(FeedbackProducer producer, FeedbackProducerReason reason) {}

    private final FeedbackProducerProperties properties;
    private final ReviewProducerPin pins;
    private final ReviewJobAssignments assignments;
    private final MentorReviewIntake intake;
    private final ApplicationEventPublisher events;

    FeedbackProducerSelection(
            FeedbackProducerProperties properties,
            ReviewProducerPin pins,
            ReviewJobAssignments assignments,
            MentorReviewIntake intake,
            ApplicationEventPublisher events) {
        this.properties = properties;
        this.pins = pins;
        this.assignments = assignments;
        this.intake = intake;
        this.events = events;
        if (properties.isAllowMentorBeforeAdviceErasure())
            log.warn("A seal may choose the mentor before the release that opens it: for a synthetic proof environment"
                    + " only");
    }

    /** In the seal's transaction, which holds the review's write lock and has the admission's digest. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Choice choose(ReviewJob job, ReviewSnapshot review, String admissionDigest) {
        var pinned = pins.pinned(job.getWorkspaceId(), job.getReviewId());
        if (pinned.isPresent()) return new Choice(pinned.get(), FeedbackProducerReason.INHERITED);
        var choice = decide(job, review, admissionDigest);
        pins.pin(job.getWorkspaceId(), job.getReviewId(), choice.producer());
        return choice;
    }

    /** Hands the sealed review to its producer's listeners, once the seal kept its inputs and saved the job. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void handOff(ReviewJob job) {
        events.publishEvent(new ReviewSealed(job.getReviewId(), job.getWorkspaceId(), job.getId()));
    }

    /**
     * The item kinds whose reviews LEGACY composes for. Any other kind, such as a team's submitted version (ADR 0165),
     * fails closed: it gets only its sealed observations until the team's mentor can produce for it.
     */
    static final Set<ItemKind> LEGACY_KINDS = Set.of(
            WorkPlaceHandoffEnrolment.PULL_REQUEST,
            new ItemKind("scm.pull_request_review"),
            new ItemKind("scm.issue"),
            new ItemKind("scm.file"),
            new ItemKind("scm.repository"),
            new ItemKind("docs.document"),
            new ItemKind("chat.thread"));

    private Choice decide(ReviewJob job, ReviewSnapshot review, String admissionDigest) {
        if (!LEGACY_KINDS.contains(review.item().kind()))
            return new Choice(FeedbackProducer.OBSERVATIONS, FeedbackProducerReason.TEAM_MENTORING_OFF);
        if (!properties.isAllowMentorBeforeAdviceErasure()) return legacy(FeedbackProducerReason.GATE_CLOSED);
        if (!properties.getMentorWorkspaceIds().contains(job.getWorkspaceId()))
            return legacy(FeedbackProducerReason.NOT_SELECTED);
        var assigned = assignments.load(job);
        // A reviewer's practice has a private destination with no cutover yet, so only author practices may leave it.
        if (!WorkPlaceHandoffEnrolment.PULL_REQUEST.equals(review.item().kind())
                || review.origin() == ReviewOrigin.BACKFILL
                || assigned.stream()
                        .map(assignment -> assignment.practice().definition().about())
                        .anyMatch(about -> about != null && about != AboutRole.AUTHOR))
            return legacy(FeedbackProducerReason.UNSUPPORTED_ROUTE);
        var authors = assigned.stream()
                .filter(assignment -> assignment.practice().definition().about() == AboutRole.AUTHOR)
                .map(assignment -> assignment.developerId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (authors.isEmpty()) return workPlace(FeedbackProducerReason.NO_LINKED_AUTHOR);
        if (authors.size() > 1) return workPlace(FeedbackProducerReason.SEVERAL_AUTHORS);
        var outcome = intake.admit(new MentorReviewIntake.SealedReview(
                job.getWorkspaceId(),
                job.getReviewId(),
                job.getId(),
                authors.getFirst(),
                admissionDigest,
                Instant.now()));
        return switch (outcome) {
            case MentorReviewIntake.Outcome.Admitted ignored ->
                new Choice(FeedbackProducer.MENTOR, FeedbackProducerReason.OWNER_BOUND);
            case MentorReviewIntake.Outcome.Refused refused -> workPlace(reason(refused.reason()));
        };
    }

    private static FeedbackProducerReason reason(MentorReviewIntake.Refusal refusal) {
        return switch (refusal) {
            case OWNER_REFUSED -> FeedbackProducerReason.OWNER_REFUSED;
            case AUTHOR_INACTIVE -> FeedbackProducerReason.AUTHOR_INACTIVE;
            case AUTHOR_NOT_MEMBER -> FeedbackProducerReason.AUTHOR_NOT_MEMBER;
            case AUTHOR_NOT_CONSENTING -> FeedbackProducerReason.AUTHOR_NOT_CONSENTING;
            case EVENT_MISMATCH -> FeedbackProducerReason.EVENT_MISMATCH;
        };
    }

    private static Choice legacy(FeedbackProducerReason reason) {
        return new Choice(FeedbackProducer.LEGACY, reason);
    }

    private static Choice workPlace(FeedbackProducerReason reason) {
        return new Choice(FeedbackProducer.WORK_PLACE, reason);
    }
}
