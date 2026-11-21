package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.PublicationContextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.CompositionToolTrace;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

/**
 * Closes what a composition left open. Each destination that the server routes and no prepared row decides is
 * withheld with the cause that stopped it, or handed on to another attempt while the cause passes and attempts are
 * left.
 */
@Service
@ConditionalOnAgentRuntime
public class CompositionClosing {
    private static final Logger log = LoggerFactory.getLogger(CompositionClosing.class);

    private final ResultAdmissionService admission;
    private final AgentCapacityProperties agentCapacity;
    private final MeterRegistry meters;
    private final FeedbackRepair repairs;
    private final CompositionToolTrace toolTrace;
    private final CompositionPlans plans;

    public CompositionClosing(
            ResultAdmissionService admission,
            AgentCapacityProperties agentCapacity,
            MeterRegistry meters,
            FeedbackRepair repairs,
            CompositionToolTrace toolTrace,
            CompositionPlans plans) {
        this.admission = admission;
        this.agentCapacity = agentCapacity;
        this.meters = meters;
        this.repairs = repairs;
        this.toolTrace = toolTrace;
        this.plans = plans;
    }

    /** The cause a destination stopped with, and the reason its withholding records. */
    private record Stop(CompositionStop cause, FeedbackStopReason reason) {
        static Stop of(CompositionStop cause) {
            return new Stop(cause, cause.reason());
        }
    }

    /** Whether this is the review's last attempt: a passing cause then withholds instead of handing the run on. */
    public boolean lastAttempt(ReviewJob job) {
        return job.spentAttempts() >= agentCapacity.reviewRetries();
    }

    /**
     * Withholds each destination the server routes and no prepared row decides, with the cause that stopped it: the
     * review on the work, and each private destination per practice. Read from the server again, so that it counts
     * what this attempt prepared. A passing cause before the retry limit withholds nothing.
     *
     * @return the passing cause that left a destination open, so that another attempt composes it; null when every
     *     destination is decided
     */
    public @Nullable CompositionStop close(
            ReviewJob job,
            ResultAdmissionService.AdmissionIdentity identity,
            Map<FeedbackDestination, CompositionStop> stops) {
        var plan = plans.plan(job, identity);
        if (plan == null) return null;
        boolean last = lastAttempt(job);
        var confirmed = confirmed(job, plan, stops);
        CompositionStop handedOn = null;
        for (var open : open(job, plan)) {
            var stop = open.routingUnavailable()
                    ? Stop.of(CompositionStop.ROUTING_UNAVAILABLE)
                    : stop(
                            identity,
                            open.destination(),
                            confirmed.getOrDefault(open.destination(), CompositionStop.NO_DECISION));
            if (stop.cause().passing() && !last) {
                if (handedOn == null) handedOn = stop.cause();
                continue;
            }
            // A refused withholding stays open, so a next attempt tries to record it again.
            if (!withhold(plan, open.destination(), stop, open.resultIds()) && handedOn == null)
                handedOn = stop.cause();
        }
        if (handedOn != null) count(handedOn, "handed_on");
        return handedOn;
    }

    /**
     * The stops as the server confirms them. A loop reports that the inputs are gone only because the server said so; a
     * report the retained inputs contradict counts as a failure of the loop, never as an erasure.
     */
    private Map<FeedbackDestination, CompositionStop> confirmed(
            ReviewJob job, CompositionPlans.Plan plan, Map<FeedbackDestination, CompositionStop> stops) {
        if (stops.values().stream()
                .noneMatch(stop -> stop == CompositionStop.INPUTS_GONE || stop == CompositionStop.INPUTS_ERASED))
            return stops;
        CompositionStop actual;
        try {
            plans.basis(job, plan);
            actual = CompositionStop.UNEXPECTED;
        } catch (ReviewCompositionContinuation.InputsUnavailableException unavailable) {
            actual = unavailable.erased() ? CompositionStop.INPUTS_ERASED : CompositionStop.INPUTS_GONE;
        }
        var confirmed = new LinkedHashMap<FeedbackDestination, CompositionStop>();
        for (var entry : stops.entrySet()) {
            boolean inputs = entry.getValue() == CompositionStop.INPUTS_GONE
                    || entry.getValue() == CompositionStop.INPUTS_ERASED;
            confirmed.put(entry.getKey(), inputs ? actual : entry.getValue());
        }
        return confirmed;
    }

    /** Closes every open destination with one cause, without composing. */
    public @Nullable CompositionStop withholdOpen(
            ReviewJob job, ResultAdmissionService.AdmissionIdentity identity, CompositionStop stop) {
        repairs.stop(job.getWorkspaceId(), job.getReviewId(), stop.name());
        var all = new LinkedHashMap<FeedbackDestination, CompositionStop>();
        for (var destination : FeedbackDestination.values()) all.put(destination, stop);
        return close(job, identity, all);
    }

    /**
     * The reason a destination's withholding records for its cause. A refused publication records the reason the
     * server refused it for, as this attempt's trace keeps it; every other cause records its own.
     */
    private Stop stop(
            ResultAdmissionService.AdmissionIdentity identity, FeedbackDestination destination, CompositionStop cause) {
        if (cause != CompositionStop.PUBLICATION_REFUSED) return Stop.of(cause);
        try {
            return toolTrace.decisions(identity).stream()
                    .filter(decision -> decision.destination() == destination && decision.refusal() != null)
                    .reduce((earlier, later) -> later)
                    .map(decision -> new Stop(cause, Objects.requireNonNull(decision.refusal())))
                    .orElse(Stop.of(cause));
        } catch (ReviewCompositionContinuation.InputsUnavailableException unreadable) {
            // The trace expired or was erased with its capsule: the withholding is recorded now, with the cause's
            // reason.
            return Stop.of(cause);
        }
    }

    /** A destination no prepared row decides, with the results a withholding there rests on. */
    private record Open(FeedbackDestination destination, List<UUID> resultIds, boolean routingUnavailable) {}

    private List<Open> open(ReviewJob job, CompositionPlans.Plan plan) {
        var state = plans.admitted(job);
        var request = plan.request();
        var open = new ArrayList<Open>();
        var reviewable = ReviewOnWork.publicResults(state.results());
        if (request.open("ON_WORK") && !state.publicReviewed() && !reviewable.isEmpty())
            open.add(new Open(FeedbackDestination.ON_WORK, ids(reviewable), false));
        var unavailable = new ArrayList<String>();
        var routes = PrivateFeedback.closeUnrouted(request, state, unavailable);
        // A destination whose routing read failed is still owed a decision. If it is withheld, preparation reads its
        // routing again and records the server's own reason when the destination is not eligible.
        for (String destination : unavailable)
            for (String slug : Evidence.notMetPractices(state.results()))
                if (!state.decided().getOrDefault(destination, Set.of()).contains(slug))
                    open.add(new Open(FeedbackDestination.valueOf(destination), notMet(state, slug), true));
        routes.forEach((slug, destinations) -> {
            for (String destination : destinations)
                open.add(new Open(FeedbackDestination.valueOf(destination), notMet(state, slug), false));
        });
        return open;
    }

    /** Whether the server recorded the withholding; there is nothing to record without results. */
    private boolean withhold(
            CompositionPlans.Plan plan, FeedbackDestination destination, Stop stop, List<UUID> resultIds) {
        if (resultIds.isEmpty()) return true;
        try {
            admission.publishPackage(
                    plan.identity(), CompositionPackages.withheld(destination, stop.reason(), resultIds));
            count(stop.cause(), "withheld");
            return true;
        } catch (AdmissionConflictException | PublicationContextRefusedException refused) {
            count(stop.cause(), "refused");
            log.error(
                    "The server refused to record a withheld {} piece, so it stays open: jobId={}, reason={}",
                    destination,
                    plan.identity().jobId(),
                    refused.getClass().getSimpleName());
            return false;
        }
    }

    private void count(CompositionStop cause, String outcome) {
        meters.counter(AgentMetrics.AGENT_JOB_COMPOSITION_STOPPED, "cause", cause.tag(), "outcome", outcome)
                .increment();
    }

    private static List<UUID> notMet(Admission state, String slug) {
        return state.results().stream()
                .filter(result -> slug.equals(Js.string(result.path("practiceSlug")))
                        && "NOT_MET".equals(Js.string(result.path("outcome"))))
                .map(result -> UUID.fromString(Evidence.text(result.path("resultId"))))
                .toList();
    }

    private static List<UUID> ids(List<ObjectNode> results) {
        return results.stream()
                .map(result -> UUID.fromString(Evidence.text(result.path("resultId"))))
                .toList();
    }
}
