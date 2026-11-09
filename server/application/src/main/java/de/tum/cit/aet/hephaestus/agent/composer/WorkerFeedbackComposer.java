package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.metrics.ReviewStageTelemetry;
import de.tum.cit.aet.hephaestus.agent.proxy.ReviewModelGateway;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Composes the feedback of an admitted item review on the worker, after its sandbox has measured: the review on the
 * work first, then the private feedback, which reads the review it follows. This is the composer loop: it calls the
 * model and owns its calls, retries and stop causes. {@link CompositionSessions} renders each session and runs every
 * tool. Each destination ends with prepared feedback or a withholding the server records, with the cause that stopped
 * it (see {@link CompositionStop}). A passing cause hands the attempt on instead, so another attempt composes what is
 * open, and a composition that cannot finish never changes a result.
 *
 * <p>Each session is bounded: the model calls of one practice's turn per practice it decides (at most three), the
 * attempt's own output-token limit per call, and the composition deadline, which the proxy enforces on every call.
 */
@Service
@ConditionalOnWorkerRole
public class WorkerFeedbackComposer {
    private static final Logger log = LoggerFactory.getLogger(WorkerFeedbackComposer.class);

    /** Fresh sessions after the first, only when the history under the review changed while it was composed. */
    static final int MAX_PUBLIC_RECOMPOSITIONS = 2;

    private static final int MAX_COMPOSITION_CALLS = 12;
    private static final Set<String> TOOLS = Set.of("check_package", "publish_package", "edit_own");
    /** The answer to a tool call that follows the call that ended its session, in the same reply. */
    private static final String REVIEW_FINAL =
            "The review on this work is final; this composition accepts nothing more.";

    private static final Duration PROVIDER_RETRY_PAUSE = Duration.ofSeconds(1);

    private final CompositionSessions sessions;
    private final ReviewCompositionContinuation continuation;
    private final ReviewModelGateway gateway;
    private final AgentCapacityProperties agentCapacity;
    // The wait has no span of its own: the composition span of the attempt covers it.
    private final ReviewStageTelemetry stages;
    private final ObjectMapper mapper;
    private final Duration providerRetryPause;
    private final CompositionCapacity capacity;

    @Autowired
    public WorkerFeedbackComposer(
            CompositionSessions sessions,
            ReviewCompositionContinuation continuation,
            ReviewModelGateway gateway,
            AgentCapacityProperties agentCapacity,
            MeterRegistry meters,
            ObjectMapper mapper,
            CompositionCapacity capacity) {
        this(sessions, continuation, gateway, agentCapacity, meters, mapper, PROVIDER_RETRY_PAUSE, capacity);
    }

    WorkerFeedbackComposer(
            CompositionSessions sessions,
            ReviewCompositionContinuation continuation,
            ReviewModelGateway gateway,
            AgentCapacityProperties agentCapacity,
            MeterRegistry meters,
            ObjectMapper mapper,
            Duration providerRetryPause,
            CompositionCapacity capacity) {
        this.sessions = sessions;
        this.continuation = continuation;
        this.gateway = gateway;
        this.agentCapacity = agentCapacity;
        this.stages = new ReviewStageTelemetry(meters, Tracer.NOOP);
        this.mapper = mapper;
        this.providerRetryPause = providerRetryPause;
        this.capacity = capacity;
    }

    private CompositionCapacity.Slot acquireSlot(Instant notAfter) {
        var wait = stages.start(ReviewStageTelemetry.Stage.COMPOSITION_WAIT);
        try {
            var slot = capacity.acquire(sessions.deadline(notAfter));
            wait.end(ReviewStageTelemetry.Outcome.OK);
            return slot;
        } catch (RuntimeException refused) {
            wait.end(ReviewStageTelemetry.Outcome.FAILED);
            throw refused;
        }
    }

    /**
     * Composes the feedback of the attempt that just measured and admitted the review, from the inputs it retained.
     *
     * @param notAfter when the worker stops renewing the attempt's lease; composition ends a minute before it at the
     *     latest
     * @throws CompositionHandedOnException when a passing cause left a destination open before the retry limit
     */
    public void composeAfterMeasurement(ReviewJob job, UUID leaseId, Instant notAfter) {
        var slot = acquireSlot(notAfter);
        try {
            compose(job, leaseId, sessions.deadline(notAfter));
        } finally {
            slot.close();
        }
    }

    /**
     * Composes what an earlier, lost attempt of the review left undecided, from the inputs its admission retained.
     * Without them, and on the attempt after the retry limit, each undecided destination is withheld without a model
     * call. Erased inputs are never staged.
     *
     * @throws CompositionHandedOnException when a passing cause left a destination open before the retry limit
     */
    public void composeResumed(ReviewJob job, UUID leaseId, Instant notAfter) {
        if (job.spentAttempts() > agentCapacity.reviewRetries()) {
            withholdWithoutInputs(job, leaseId, CompositionStop.RETRIES_EXHAUSTED);
            return;
        }
        var slot = acquireSlot(notAfter);
        try {
            ReviewCompositionContinuation.Restored restored;
            try {
                restored = continuation.restore(job);
            } catch (ReviewCompositionContinuation.InputsUnavailableException unavailable) {
                log.warn(
                        "The retained composition inputs are unavailable: jobId={}, erased={}",
                        job.getId(),
                        unavailable.erased());
                withholdWithoutInputs(
                        job,
                        leaseId,
                        unavailable.erased() ? CompositionStop.INPUTS_ERASED : CompositionStop.INPUTS_GONE);
                return;
            }
            // The restored folder holds the person-copy receipt that the sessions' reads of earlier feedback index.
            try (restored) {
                compose(job, leaseId, sessions.deadline(notAfter));
            }
        } finally {
            slot.close();
        }
    }

    /** Withholds what an earlier attempt left open, with a cause the claim met, without composing or staging inputs. */
    public void withholdResumed(ReviewJob job, UUID leaseId, CompositionStop stop) {
        withholdWithoutInputs(job, leaseId, stop);
    }

    /** Withholds what is open from an empty folder, whose person-copy receipt indexes what the withholdings read. */
    private void withholdWithoutInputs(ReviewJob job, UUID leaseId, CompositionStop stop) {
        var empty = continuation.stageWithoutInputs(job);
        try {
            handOnIfOpen(sessions.withholdOpen(job, identity(job, leaseId), stop), null);
        } finally {
            empty.close();
        }
    }

    private static ResultAdmissionService.AdmissionIdentity identity(ReviewJob job, UUID leaseId) {
        return new ResultAdmissionService.AdmissionIdentity(
                job.getId(), job.getWorkspaceId(), job.getAttempt(), leaseId);
    }

    private static void handOnIfOpen(@Nullable CompositionStop handedOn, @Nullable RuntimeException failure) {
        if (handedOn != null) throw new CompositionHandedOnException(handedOn, failure);
    }

    /**
     * Composes every eligible destination of the admitted review this worker runs as the job's current attempt, then
     * closes each destination left open with the cause that stopped it.
     *
     * @param deadline when composition stops; no model call starts after it
     * @throws ResultAdmissionService.StaleAttemptException when the attempt no longer runs on this worker
     * @throws CompositionHandedOnException when a passing cause left a destination open before the retry limit
     */
    void compose(ReviewJob job, UUID leaseId, Instant deadline) {
        var identity = identity(job, leaseId);
        var stops = new EnumMap<FeedbackDestination, CompositionStop>(FeedbackDestination.class);
        RuntimeException failure = null;
        try {
            new Run(job, identity, deadline, stops).compose();
        } catch (ResultAdmissionService.StaleAttemptException lost) {
            throw lost;
        } catch (ReviewCompositionContinuation.InputsUnavailableException unavailable) {
            var cause = unavailable.erased() ? CompositionStop.INPUTS_ERASED : CompositionStop.INPUTS_GONE;
            sessions.stop(job, cause.name());
            for (var destination : FeedbackDestination.values()) stops.put(destination, cause);
        } catch (RuntimeException failed) {
            log.warn("Feedback composition failed: jobId={}", job.getId(), failed);
            for (var destination : FeedbackDestination.values())
                stops.putIfAbsent(destination, CompositionStop.UNEXPECTED);
            failure = failed;
        }
        handOnIfOpen(sessions.close(job, identity, stops), failure);
    }

    /** One composition: its attempt, its deadline and what its sessions decided so far. */
    private final class Run {
        private final ReviewJob job;
        private final ResultAdmissionService.AdmissionIdentity identity;
        private final Instant deadline;
        private final Map<FeedbackDestination, CompositionStop> stops;
        private final Set<FeedbackDestination> finished = EnumSet.noneOf(FeedbackDestination.class);
        private int calls;
        /** Why the current session ended without deciding everything it was given. */
        private @Nullable CompositionStop stop;

        Run(
                ReviewJob job,
                ResultAdmissionService.AdmissionIdentity identity,
                Instant deadline,
                Map<FeedbackDestination, CompositionStop> stops) {
            this.job = job;
            this.identity = identity;
            this.deadline = deadline;
            this.stops = stops;
        }

        private Optional<CompositionSessions.Next> next() {
            return sessions.next(job, identity, finished);
        }

        void compose() {
            var next = next();
            if (next.orElse(null) instanceof CompositionSessions.Unavailable(var cause)) {
                for (var destination : FeedbackDestination.values()) stops.put(destination, cause);
                return;
            }
            if (next.orElse(null) instanceof CompositionSessions.Session review
                    && review.destination() == FeedbackDestination.ON_WORK) {
                boolean reviewed;
                try {
                    reviewed = composeReview(review);
                } catch (ResultAdmissionService.StaleAttemptException
                        | ReviewCompositionContinuation.InputsUnavailableException gone) {
                    throw gone;
                } catch (RuntimeException failed) {
                    // A failure of the loop itself, such as an unreadable answer: no attempt starts another session.
                    sessions.stop(job, CompositionStop.UNEXPECTED.name());
                    throw failed;
                }
                finished.add(FeedbackDestination.ON_WORK);
                if (!reviewed) {
                    var ended = ended();
                    stops.put(FeedbackDestination.ON_WORK, ended);
                    // The private feedback reads the review it follows: a review that another attempt may still
                    // compose comes first.
                    if (ended.passing() && job.spentAttempts() < agentCapacity.reviewRetries()) {
                        stops.put(FeedbackDestination.PRACTICE_PAGE, ended);
                        stops.put(FeedbackDestination.CONVERSATION, ended);
                        return;
                    }
                }
                next = next();
            }
            if (next.isEmpty()) return;
            // A review that stopped for a passing cause also spent the calls the private sessions would use.
            if (stop != null && !stop.passing()) stop = null;
            while (true) {
                if (calls >= MAX_COMPOSITION_CALLS || expired()) {
                    stopFor(outOfRoom());
                    break;
                }
                if (!(next.orElse(null) instanceof CompositionSessions.Session session)) break;
                composePrivateDestination(session);
                finished.add(session.destination());
                next = next();
            }
            var ended = ended();
            stops.put(FeedbackDestination.PRACTICE_PAGE, ended);
            stops.put(FeedbackDestination.CONVERSATION, ended);
        }

        /** The cause a session recorded, or, when none ran or none recorded one, whether time or the model ended it. */
        private CompositionStop ended() {
            return stop != null ? stop : outOfRoom();
        }

        /**
         * Ends the session for a cause that left something undecided. A cause that another attempt may compose past
         * stays recorded: a later destination that found the run's calls spent must not turn it into a withholding.
         */
        private void stopFor(CompositionStop cause) {
            if (stop != null && stop.passing() && !cause.passing()) return;
            if (stop == CompositionStop.PUBLICATION_REFUSED && cause == CompositionStop.PUBLICATION_REFUSED) return;
            stop = cause;
        }

        /** The cause a refused or failed call ends its session with. */
        private CompositionStop causeOf(ModelSession.Failure failure) {
            return switch (failure) {
                case OWNERSHIP_LOST -> throw new ResultAdmissionService.StaleAttemptException();
                case AI_CHOICE -> CompositionStop.NO_AI;
                case BUDGET -> CompositionStop.BUDGET;
                case WORK_TIME -> CompositionStop.DEADLINE;
                case REFUSED -> CompositionStop.MODEL_REFUSED;
                case PROVIDER -> CompositionStop.PROVIDER;
                case CAPACITY -> CompositionStop.CAPACITY;
            };
        }

        /** Why a session that has no room for another call ended. */
        private CompositionStop outOfRoom() {
            if (expired()) return CompositionStop.DEADLINE;
            return calls >= MAX_COMPOSITION_CALLS ? CompositionStop.STEP_LIMIT : CompositionStop.NO_DECISION;
        }

        private CompositionStop limitReached() {
            return expired() ? CompositionStop.DEADLINE : CompositionStop.STEP_LIMIT;
        }

        private boolean expired() {
            return !Instant.now().isBefore(deadline);
        }

        /**
         * Waits before the call after a provider failure, twice as long after each, so that a short outage does not
         * spend the session's calls at once. Never waits past the deadline.
         *
         * @return whether a call may still start
         */
        private boolean pause(int failures) {
            Duration wait = providerRetryPause.multipliedBy(1L << Math.min(failures - 1, 5));
            Duration left = Duration.between(Instant.now(), deadline);
            Duration nap = wait.compareTo(left) < 0 ? wait : left;
            try {
                if (nap.isPositive()) Thread.sleep(nap);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            return !expired();
        }

        private CompositionSessions.Ending stopped(CompositionStop cause) {
            stopFor(cause);
            sessions.stop(job, cause.name());
            return CompositionSessions.Ending.STOPPED;
        }

        private int bound(CompositionSessions.Session session) {
            return Math.min(MAX_COMPOSITION_CALLS - calls, session.calls());
        }

        private ModelSession model(CompositionSessions.Session session) {
            var settings = new ModelSession.Settings(
                    session.model().protocol(),
                    session.model().modelId(),
                    session.model().reasoningEffort(),
                    session.model().maxOutputTokens(),
                    session.model().temperature());
            var attempt = new ReviewModelGateway.Attempt(
                    identity.jobId(), identity.workspaceId(), identity.attempt(), identity.runId(), deadline);
            ModelSession.Transport transport = body -> {
                calls++;
                var answer = gateway.call(attempt, body);
                return new ModelSession.Answer(answer.status(), answer.body(), answer.owned(), answer.refusal());
            };
            return new ModelSession(
                    transport,
                    mapper,
                    settings,
                    session.system(),
                    session.tools().stream()
                            .map(tool -> new ModelSession.Tool(tool.name(), tool.description(), tool.parameters()))
                            .toList());
        }

        private CompositionSessions.ToolResult tool(CompositionSessions.Session session, ModelSession.ToolCall call) {
            return sessions.tool(
                    job,
                    identity,
                    new CompositionSessions.ToolCall(
                            session.destination(),
                            call.id(),
                            call.name(),
                            call.arguments(),
                            session.publicationContext(),
                            deadline));
        }

        // ------------------------------------------------------------ the review on the work

        /** Whether the review on the work is accepted, by this composition or an earlier attempt. */
        private boolean composeReview(CompositionSessions.Session first) {
            var session = first;
            boolean accepted = first.accepted();
            for (int recomposed = 0; recomposed <= MAX_PUBLIC_RECOMPOSITIONS; recomposed++) {
                if (expired()) {
                    stopped(CompositionStop.DEADLINE);
                    break;
                }
                if (recomposed > 0) {
                    // A fresh read: the history changed under the review.
                    if (!(next().orElse(null) instanceof CompositionSessions.Session again
                            && again.destination() == FeedbackDestination.ON_WORK)) break;
                    session = again;
                }
                var outcome = reviewSession(session);
                accepted |= outcome.published();
                if (outcome.ending() != CompositionSessions.Ending.RECOMPOSE) break;
                if (recomposed == MAX_PUBLIC_RECOMPOSITIONS) stopped(CompositionStop.PUBLICATION_REFUSED);
            }
            return accepted;
        }

        private record Outcome(CompositionSessions.Ending ending, boolean published) {}

        private Outcome reviewSession(CompositionSessions.Session session) {
            boolean accepted = session.accepted();
            boolean published = false;
            if (calls >= MAX_COMPOSITION_CALLS) return new Outcome(stopped(CompositionStop.STEP_LIMIT), false);
            if (expired()) return new Outcome(stopped(CompositionStop.DEADLINE), false);
            if (!session.fits()) return new Outcome(stopped(CompositionStop.CONTEXT_WINDOW), false);
            var model = model(session);
            int bound = bound(session);
            var reply = model.send(session.input());
            boolean askedOnceMore = false;
            boolean nudged = false;
            int providerFailures = 0;
            while (true) {
                var failure = reply.failure();
                boolean room = model.calls() < bound && !expired();
                if (failure == ModelSession.Failure.PROVIDER) {
                    if (!room || !pause(++providerFailures))
                        return new Outcome(stopped(CompositionStop.PROVIDER), published);
                    reply = model.retry();
                    continue;
                }
                if (failure != null) return new Outcome(stopped(causeOf(failure)), published);
                if (reply.toolCalls().isEmpty()) {
                    if (accepted) {
                        sessions.stop(job, "COMPLETE");
                        return new Outcome(CompositionSessions.Ending.STORED, published);
                    }
                    String finish = session.finish();
                    if (!room) return new Outcome(stopped(limitReached()), published);
                    if (askedOnceMore || finish == null)
                        return new Outcome(stopped(CompositionStop.NO_DECISION), published);
                    askedOnceMore = true;
                    reply = model.send(finish);
                    continue;
                }
                var results = new ArrayList<ModelSession.ToolResult>();
                CompositionSessions.Ending ending = null;
                for (var call : reply.toolCalls()) {
                    if (ending != null && TOOLS.contains(call.name())) {
                        results.add(new ModelSession.ToolResult(call.id(), REVIEW_FINAL));
                        continue;
                    }
                    var result = tool(session, call);
                    results.add(new ModelSession.ToolResult(call.id(), result.text()));
                    if (result.published()) {
                        accepted = true;
                        published = true;
                    }
                    if (result.stop() != null) stop = result.stop();
                    if (ending == null) ending = result.ending();
                }
                if (ending != null) return new Outcome(ending, published);
                if (!room || expired()) return new Outcome(stopped(limitReached()), published);
                String then = null;
                if (!nudged && model.calls() >= bound - 2) {
                    nudged = true;
                    then = session.nudge();
                }
                reply = model.answer(results, then);
            }
        }

        // ------------------------------------------------------------ private feedback

        private void composePrivateDestination(CompositionSessions.Session session) {
            if (expired()) {
                stopFor(CompositionStop.DEADLINE);
                return;
            }
            if (!session.fits()) {
                stopFor(CompositionStop.CONTEXT_WINDOW);
                return;
            }
            var model = model(session);
            int bound = bound(session);
            var reply = model.send(session.input());
            boolean accepted = false;
            boolean askedOnceMore = false;
            boolean nudged = false;
            int providerFailures = 0;
            while (true) {
                var failure = reply.failure();
                boolean room = model.calls() < bound && !expired();
                if (failure == ModelSession.Failure.PROVIDER) {
                    if (!room || !pause(++providerFailures)) {
                        stopFor(CompositionStop.PROVIDER);
                        break;
                    }
                    reply = model.retry();
                    continue;
                }
                if (failure != null) {
                    stopFor(causeOf(failure));
                    break;
                }
                String finish = session.finish();
                if (reply.toolCalls().isEmpty()) {
                    if (finish == null) break;
                    if (!room) {
                        stopFor(limitReached());
                        break;
                    }
                    if (askedOnceMore) break;
                    askedOnceMore = true;
                    reply = model.send(finish);
                    continue;
                }
                var results = new ArrayList<ModelSession.ToolResult>();
                for (var call : reply.toolCalls()) {
                    var result = tool(session, call);
                    results.add(new ModelSession.ToolResult(call.id(), result.text()));
                    accepted |= result.published();
                }
                if (accepted) return;
                if (!room) {
                    stopFor(limitReached());
                    break;
                }
                String then = null;
                if (!nudged && model.calls() >= bound - 2) {
                    nudged = true;
                    then = session.nudge();
                }
                reply = model.answer(results, then);
            }
        }
    }
}
