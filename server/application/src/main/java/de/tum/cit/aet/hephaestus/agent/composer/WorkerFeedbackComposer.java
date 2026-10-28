package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.PublicationContextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ReviewModelGateway;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.FeedbackWritingGuide;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Composes the feedback of an admitted item review on the worker, after its sandbox has measured: the review on the
 * work first, then the private feedback, which reads the review it follows. Each destination ends with prepared
 * feedback or a withholding the server records, with the cause that stopped it (see {@link CompositionStop}). A passing
 * cause hands the attempt on instead, so another attempt composes what is open, and a composition that cannot finish
 * never fails the review.
 *
 * <p>Each session is bounded: the model calls of one practice's turn per practice it decides (at most three), the
 * attempt's own output-token limit per call, and the composition deadline, which the proxy enforces on every call.
 */
@Service
@ConditionalOnWorkerRole
public class WorkerFeedbackComposer {
    private static final Logger log = LoggerFactory.getLogger(WorkerFeedbackComposer.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    /** Reads the retained files, which are plain JSON: no application module changes how they parse. */
    private static final JsonMapper MAPPER = new JsonMapper();

    /** Fresh sessions after the first, only when the history under the review changed while it was composed. */
    static final int MAX_PUBLIC_RECOMPOSITIONS = 2;

    private static final int PRACTICES_PER_TURN = 3;
    private static final Duration PROVIDER_RETRY_PAUSE = Duration.ofSeconds(1);
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int DEFAULT_MAX_TOKENS = 16_384;
    private static final String REVIEW_FINAL =
            "The review on this work is final; this composition accepts nothing more.";

    private final ResultAdmissionService admission;
    private final JobEvidenceFiles files;
    private final ReviewCompositionContinuation continuation;
    private final ReviewModelGateway gateway;
    private final ReviewJobAssignments assignments;
    private final ItemDescriptorRegistry items;
    private final PracticeReviewProperties properties;
    private final AgentProperties agentProperties;
    private final MeterRegistry meters;
    private final ObjectMapper mapper;
    private final Duration providerRetryPause;
    private final String reviewSystem;
    private final String privateSystem;
    private final CompositionCapacity capacity;

    @Autowired
    public WorkerFeedbackComposer(
            ResultAdmissionService admission,
            JobEvidenceFiles files,
            ReviewCompositionContinuation continuation,
            ReviewModelGateway gateway,
            ReviewJobAssignments assignments,
            ItemDescriptorRegistry items,
            PracticeReviewProperties properties,
            AgentProperties agentProperties,
            MeterRegistry meters,
            ObjectMapper mapper,
            CompositionCapacity capacity) {
        this(
                admission,
                files,
                continuation,
                gateway,
                assignments,
                items,
                properties,
                agentProperties,
                meters,
                mapper,
                PROVIDER_RETRY_PAUSE,
                capacity);
    }

    WorkerFeedbackComposer(
            ResultAdmissionService admission,
            JobEvidenceFiles files,
            ReviewCompositionContinuation continuation,
            ReviewModelGateway gateway,
            ReviewJobAssignments assignments,
            ItemDescriptorRegistry items,
            PracticeReviewProperties properties,
            AgentProperties agentProperties,
            MeterRegistry meters,
            ObjectMapper mapper,
            Duration providerRetryPause,
            CompositionCapacity capacity) {
        this.admission = admission;
        this.capacity = capacity;
        this.files = files;
        this.continuation = continuation;
        this.gateway = gateway;
        this.assignments = assignments;
        this.items = items;
        this.properties = properties;
        this.agentProperties = agentProperties;
        this.meters = meters;
        this.mapper = mapper;
        this.providerRetryPause = providerRetryPause;
        String style = sharedStyle();
        this.reviewSystem = style + "\n\n" + prompt("review-composer.md");
        this.privateSystem = style + "\n\n" + prompt("feedback-composer.md");
    }

    static String sharedStyle() {
        return prompt("feedback-style.md") + "\n\n" + FeedbackWritingGuide.text();
    }

    static String prompt(String name) {
        try {
            return new ClassPathResource("agent/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }

    /**
     * Composes the feedback of the attempt that just measured and admitted the review, from the inputs its folder
     * holds.
     *
     * @param notAfter when the stale-job sweep would end the attempt; composition ends a minute before it at the latest
     * @throws CompositionHandedOnException when a passing cause left a destination open before the retry limit
     */
    public void composeAfterMeasurement(ReviewJob job, String workerId, Instant notAfter) {
        var slot = capacity.acquire(deadline(notAfter));
        try {

            var identity = new ResultAdmissionService.AdmissionIdentity(
                    job.getId(), job.getWorkspaceId(), job.getAttempt(), workerId);
            var inputs = files.originalCompositionInputs(identity).orElse(null);
            if (inputs == null) withholdOpen(job, workerId, CompositionStop.INPUTS_GONE);
            else compose(job, workerId, inputs, deadline(notAfter));

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
    public void composeResumed(ReviewJob job, String workerId, Instant notAfter) {
        var slot = capacity.acquire(deadline(notAfter));
        try {

            if (job.spentAttempts() > agentProperties.maxRetries()) {
                withholdWithoutInputs(job, workerId, CompositionStop.RETRIES_EXHAUSTED);
                return;
            }
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
                        workerId,
                        unavailable.erased() ? CompositionStop.INPUTS_ERASED : CompositionStop.INPUTS_GONE);
                return;
            }
            try (restored) {
                compose(job, workerId, restored.files(), deadline(notAfter));
            }

        } finally {
            slot.close();
        }
    }

    /** Withholds what an earlier attempt left open, with a cause the claim met, without composing or staging inputs. */
    public void withholdResumed(ReviewJob job, String workerId, CompositionStop stop) {
        withholdWithoutInputs(job, workerId, stop);
    }

    /** Withholds what is open from an empty folder, whose person-copy receipt indexes what the withholdings read. */
    private void withholdWithoutInputs(ReviewJob job, String workerId, CompositionStop stop) {
        var empty = continuation.stageWithoutInputs(job);
        try {
            withholdOpen(job, workerId, stop);
        } finally {
            empty.close();
        }
    }

    private Instant deadline(Instant notAfter) {
        Instant own = Instant.now().plus(properties.compositionTimeout());
        Instant latest = notAfter.minus(Duration.ofMinutes(1));
        return own.isBefore(latest) ? own : latest;
    }

    /** What one attempt composes: its identity, the request it composes for and the admitted results. */
    @SuppressWarnings("ArrayRecordComponent") // routing narrows a parsed request, so each reader parses its own
    private record Plan(
            ResultAdmissionService.AdmissionIdentity identity,
            CompositionPackages.Attempt attempt,
            byte[] requestBytes,
            PrivateFeedback.Request request,
            Admission state) {}

    /** The attempt's plan, or null when the review has no destination to compose or no admitted result. */
    private @Nullable Plan plan(ReviewJob job, String workerId) {
        var review = assignments.review(job);
        var capabilities = items.descriptorFor(review.item().kind())
                .map(ItemDescriptor::copyCapabilities)
                .orElse(Set.of());
        byte[] requestBytes = FeedbackCompositionInputs.request(
                        review.kind(), review.origin(), Set.of(FeedbackDestination.values()), capabilities, mapper)
                .orElse(null);
        if (requestBytes == null) return null;
        var request = PrivateFeedback.Request.of(mapper.readTree(requestBytes)).orElse(null);
        if (request == null) return null;
        var state = admitted(job);
        if (state.results().isEmpty()) return null;
        return new Plan(
                new ResultAdmissionService.AdmissionIdentity(
                        job.getId(), job.getWorkspaceId(), job.getAttempt(), workerId),
                new CompositionPackages.Attempt(job.getReviewId(), job.getId(), job.getAttempt()),
                requestBytes,
                request,
                state);
    }

    /**
     * Composes every eligible destination of the admitted review this worker runs as the job's current attempt, then
     * closes each destination left open with the cause that stopped it.
     *
     * @param inputs the retained capture the review was measured from
     * @param deadline when composition stops; no model call starts after it
     * @throws ResultAdmissionService.StaleAttemptException when the attempt no longer runs on this worker
     * @throws CompositionHandedOnException when a passing cause left a destination open before the retry limit
     */
    void compose(ReviewJob job, String workerId, Map<String, byte[]> inputs, Instant deadline) {
        var plan = plan(job, workerId);
        if (plan == null) return;
        var stops = new EnumMap<FeedbackDestination, Stop>(FeedbackDestination.class);
        RuntimeException failure = null;
        try {
            new Run(job, workerId, plan, deadline, stops).compose(inputs);
        } catch (ResultAdmissionService.StaleAttemptException lost) {
            throw lost;
        } catch (RuntimeException failed) {
            log.warn("Feedback composition failed: jobId={}", job.getId(), failed);
            for (var destination : FeedbackDestination.values())
                stops.putIfAbsent(destination, Stop.of(CompositionStop.UNEXPECTED));
            failure = failed;
        }
        close(job, plan, destination -> stops.getOrDefault(destination, Stop.of(CompositionStop.NO_DECISION)), failure);
    }

    /** Closes every open destination of the attempt with one cause, without composing. */
    private void withholdOpen(ReviewJob job, String workerId, CompositionStop stop) {
        var plan = plan(job, workerId);
        if (plan != null) close(job, plan, destination -> Stop.of(stop), null);
    }

    /** The cause a destination stopped with, and the reason its withholding records. */
    private record Stop(CompositionStop cause, FeedbackStopReason reason) {
        static Stop of(CompositionStop cause) {
            return new Stop(cause, cause.reason());
        }
    }

    /**
     * Withholds each destination the server routes and no prepared row decides, with the cause that stopped it: the
     * review on the work, and each private destination per practice. Read from the server again, so that it counts
     * what this attempt prepared. A passing cause before the retry limit withholds nothing and hands the attempt on.
     */
    private void close(
            ReviewJob job, Plan plan, Function<FeedbackDestination, Stop> stops, @Nullable RuntimeException failure) {
        boolean last = job.spentAttempts() >= agentProperties.maxRetries();
        CompositionStop handedOn = null;
        for (var open : open(job, plan)) {
            var stop = open.routingUnavailable()
                    ? Stop.of(CompositionStop.ROUTING_UNAVAILABLE)
                    : stops.apply(open.destination());
            if (stop.cause().passing() && !last) {
                if (handedOn == null) handedOn = stop.cause();
                continue;
            }
            withhold(plan, open.destination(), stop, open.resultIds());
        }
        if (handedOn != null) {
            count(handedOn, "handed_on");
            throw new CompositionHandedOnException(handedOn, failure);
        }
    }

    /** A destination no prepared row decides, with the results a withholding there rests on. */
    private record Open(FeedbackDestination destination, List<UUID> resultIds, boolean routingUnavailable) {}

    private List<Open> open(ReviewJob job, Plan plan) {
        var state = admitted(job);
        var request =
                PrivateFeedback.Request.of(mapper.readTree(plan.requestBytes())).orElseThrow();
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

    /** The admitted results with the private feedback each destination's prepared pieces already decide. */
    private Admission admitted(ReviewJob job) {
        var decided = new LinkedHashMap<String, Set<String>>();
        admission.privateProgress(job).decidedByDestination().forEach((destination, results) -> {
            var ids = new LinkedHashSet<String>();
            results.forEach(id -> ids.add(id.toString()));
            decided.put(destination.name(), ids);
        });
        return Admission.of(mapper.valueToTree(admission.admittedResponse(job)), decided);
    }

    private static List<UUID> notMet(Admission state, String slug) {
        return state.results().stream()
                .filter(result -> slug.equals(Js.string(result.path("practiceSlug")))
                        && "NOT_MET".equals(Js.string(result.path("outcome"))))
                .map(result -> UUID.fromString(Evidence.text(result.path("resultId"))))
                .toList();
    }

    private void withhold(Plan plan, FeedbackDestination destination, Stop stop, List<UUID> resultIds) {
        if (resultIds.isEmpty()) return;
        try {
            admission.prepareFeedback(
                    plan.identity(),
                    CompositionPackages.withheld(plan.attempt(), destination, stop.reason(), resultIds));
            count(stop.cause(), "withheld");
        } catch (AdmissionConflictException | PublicationContextRefusedException refused) {
            count(stop.cause(), "refused");
            log.error(
                    "The server refused to record a withheld {} piece, so it stays open: jobId={}, reason={}",
                    destination,
                    plan.identity().jobId(),
                    refused.getClass().getSimpleName());
        }
    }

    /** What the model must rewrite: the server names the rule and the field, never the text. */
    static String textRefusal(FeedbackTextRefusedException refused) {
        String broken = switch (refused.rule()) {
            case "LINK" -> "has a link that no cited quote supplies. Write no links: Hephaestus adds its own";
            case "MENTION" ->
                "has an @-mention or Slack mention. Mention no one, and put code such as `@Override` in backticks";
            case "HTML" -> "has raw HTML. Put code such as `List<String>` in backticks";
            case "IMAGE" -> "has an image. Write no images";
            case "HIDDEN_CHARACTER" -> "has a hidden or direction-changing character. Remove it";
            default -> "breaks the feedback text rules";
        };
        String field = refused.field();
        String name;
        if (field.equals("summary")) name = "The summary";
        else if (field.startsWith("notes[") && field.endsWith("]"))
            name = "Inline note " + (Integer.parseInt(field.substring(6, field.length() - 1)) + 1);
        else name = "The card's " + (field.equals("page.nextStep") ? "next step" : field.replaceFirst("^page\\.", ""));
        return name + " " + broken + ".";
    }

    private void count(CompositionStop cause, String outcome) {
        meters.counter(AgentMetrics.AGENT_JOB_COMPOSITION_STOPPED, "cause", cause.tag(), "outcome", outcome)
                .increment();
    }

    /** One composition: its attempt, the admitted results and what it stored so far. */
    private final class Run {
        private final ReviewJob job;
        private final ResultAdmissionService.AdmissionIdentity identity;
        private final CompositionPackages.Attempt attempt;
        private final byte[] requestBytes;
        private final PrivateFeedback.Request request;
        private final Admission state;
        private final Instant deadline;
        private final Map<FeedbackDestination, Stop> stops;
        private final ModelSession.Settings settings;
        private final int contextWindow;
        private final ModelSession.Transport transport;
        /** Why the current session ended without deciding everything it was given. */
        private @Nullable Stop stop;

        Run(ReviewJob job, String workerId, Plan plan, Instant deadline, Map<FeedbackDestination, Stop> stops) {
            this.job = job;
            this.identity = plan.identity();
            this.attempt = plan.attempt();
            this.requestBytes = plan.requestBytes();
            this.request = plan.request();
            this.state = plan.state();
            this.deadline = deadline;
            this.stops = stops;
            var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), mapper);
            this.settings = new ModelSession.Settings(
                    snapshot.model().apiProtocol(),
                    snapshot.model().upstreamModelId(),
                    snapshot.model().reasoningEffort(),
                    Objects.requireNonNullElse(snapshot.maxOutputTokens(), DEFAULT_MAX_TOKENS),
                    properties.samplingTemperature());
            this.contextWindow = Objects.requireNonNullElse(snapshot.contextWindow(), DEFAULT_CONTEXT_WINDOW);
            var model = new ReviewModelGateway.Attempt(
                    job.getId(), job.getWorkspaceId(), job.getAttempt(), workerId, deadline);
            this.transport = body -> {
                var answer = gateway.call(model, body);
                return new ModelSession.Answer(answer.status(), answer.body(), answer.owned(), answer.refusal());
            };
        }

        void compose(Map<String, byte[]> inputs) {
            var files = workspace(inputs, requestBytes, state);
            JsonNode folderIndex =
                    files.json(SandboxLayout.MANIFEST_PATH, mapper).orElse(JSON.missingNode());
            JsonNode practiceIndex =
                    files.json(ComposerFiles.PRACTICE_INDEX, mapper).orElse(JSON.arrayNode());
            var practiceSlugs = practiceSlugs(practiceIndex);
            var notReached = notReached(practiceIndex, state);
            var captured = new CapturedWork(files, mapper);

            ReviewOnWork.ComposedReview review = null;
            var reviewable = ReviewOnWork.publicResults(state.results());
            if (state.publicReviewed())
                review = admission
                        .preparedReview(job)
                        .map(WorkerFeedbackComposer::review)
                        .orElse(null);
            else if (publicDue(reviewable)) {
                review = composeReview(files, captured, folderIndex, practiceIndex, reviewable, notReached);
                if (review == null) {
                    var ended = ended();
                    stops.put(FeedbackDestination.ON_WORK, ended);
                    // The private feedback reads the review it follows: a review that another attempt may still
                    // compose comes first.
                    if (ended.cause().passing() && job.spentAttempts() < agentProperties.maxRetries()) {
                        stops.put(FeedbackDestination.PRACTICE_PAGE, ended);
                        stops.put(FeedbackDestination.CONVERSATION, ended);
                        return;
                    }
                }
            }

            var routes = PrivateFeedback.closeUnrouted(request, state, new ArrayList<>());
            if (!PrivateFeedback.composable(state, routes).isEmpty()) {
                stop = null;
                composePrivately(files, captured, folderIndex, practiceSlugs, review, routes, notReached);
                var ended = ended();
                stops.put(FeedbackDestination.PRACTICE_PAGE, ended);
                stops.put(FeedbackDestination.CONVERSATION, ended);
            }
        }

        /** The cause a session recorded, or, when none ran or none recorded one, whether time or the model ended it. */
        private Stop ended() {
            return stop != null ? stop : Stop.of(outOfRoom());
        }

        /** Ends the session for a cause that left something undecided. */
        private void stopFor(CompositionStop cause) {
            stop = Stop.of(cause);
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
            return expired() ? CompositionStop.DEADLINE : CompositionStop.NO_DECISION;
        }

        private boolean publicDue(List<ObjectNode> reviewable) {
            return request.open("ON_WORK") && !state.publicReviewed() && !reviewable.isEmpty();
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

        private Ending stopped(CompositionStop cause) {
            stopFor(cause);
            return Ending.NONE;
        }

        private int bound(int practices) {
            return properties.practiceModelCalls() * Math.min(Math.max(1, practices), PRACTICES_PER_TURN);
        }

        /** Whether a request with this turn fits the model's context window with room for its answer. */
        private boolean fits(ModelSession session, String turn) {
            return session.requestText(turn).length() / 4 + settings.maxOutputTokens() <= contextWindow;
        }

        // ------------------------------------------------------------ the review on the work

        private enum Ending {
            STORED,
            RECOMPOSE,
            NONE
        }

        private ReviewOnWork.@Nullable ComposedReview composeReview(
                ComposerFiles files,
                CapturedWork captured,
                JsonNode folderIndex,
                JsonNode practiceIndex,
                List<ObjectNode> reviewable,
                List<String> notReached) {
            var holder = new ReviewOnWork.ComposedReview[1];
            for (int recomposed = 0; recomposed <= MAX_PUBLIC_RECOMPOSITIONS && !expired(); recomposed++) {
                // Read now, not at capture: feedback delivered while this review ran was said here too.
                JsonNode read = mapper.valueToTree(admission.publicFeedbackHistory(identity));
                var turn = reviewTurn(
                        files,
                        captured,
                        folderIndex,
                        practiceIndex,
                        state,
                        request,
                        read,
                        notReached,
                        PrimarySource.captured(
                                files, folderIndex, ComposerFiles.CONTEXT_ROOT, ComposerFiles.REPOSITORY_ROOT));
                var tool = new ModelSession.Tool("report_review", ReviewOnWork.TOOL_DESCRIPTION, turn.toolParameters());
                Ending ending = reviewSession(
                        turn.text(),
                        tool,
                        turn.restable(),
                        turn.lineNotes(),
                        turn.witnesses(),
                        turn.publicationContext(),
                        reviewable,
                        holder);
                if (ending != Ending.RECOMPOSE) break;
            }
            return holder[0];
        }

        private Ending reviewSession(
                String turn,
                ModelSession.Tool tool,
                Map<String, Evidence.Result> restable,
                boolean lineNotes,
                Map<String, ReviewOnWork.Witness> witnesses,
                @Nullable String publicationContext,
                List<ObjectNode> reviewable,
                ReviewOnWork.ComposedReview[] holder) {
            var session = new ModelSession(transport, mapper, settings, reviewSystem, List.of(tool));
            if (expired()) return stopped(CompositionStop.DEADLINE);
            if (!fits(session, turn)) {
                log.warn("The review input exceeds the model's context window: jobId={}", job.getId());
                return stopped(CompositionStop.CONTEXT_WINDOW);
            }
            int bound = bound((int)
                    restable.values().stream().filter(Evidence.Result::notMet).count());
            var reply = session.send(turn);
            boolean askedOnceMore = false;
            boolean nudged = false;
            int providerFailures = 0;
            while (true) {
                var failure = reply.failure();
                boolean room = session.calls() < bound && !expired();
                if (failure == ModelSession.Failure.PROVIDER) {
                    if (!room || !pause(++providerFailures)) return stopped(CompositionStop.PROVIDER);
                    reply = session.retry();
                    continue;
                }
                if (failure != null) return stopped(causeOf(failure));
                if (reply.toolCalls().isEmpty()) {
                    if (!room) return stopped(outOfRoom());
                    if (askedOnceMore) return stopped(CompositionStop.NO_DECISION);
                    askedOnceMore = true;
                    var left = reviewable.stream()
                            .map(result -> Evidence.text(result.path("resultId")))
                            .toList();
                    reply = session.send(ReviewOnWork.finishText(left));
                    continue;
                }
                var results = new ArrayList<ModelSession.ToolResult>();
                Ending ending = null;
                for (var call : reply.toolCalls()) {
                    if (!call.name().equals("report_review")) {
                        results.add(new ModelSession.ToolResult(
                                call.id(),
                                "There is no tool named " + call.name() + "; store the review with report_review."));
                        continue;
                    }
                    if (ending != null) {
                        results.add(new ModelSession.ToolResult(call.id(), REVIEW_FINAL));
                        continue;
                    }
                    var read = ReviewOnWork.read(call.arguments(), restable, lineNotes, witnesses);
                    if (read.review() == null) {
                        results.add(new ModelSession.ToolResult(
                                call.id(),
                                "review refused, nothing was stored:\n"
                                        + String.join(
                                                "\n",
                                                read.errors().stream()
                                                        .map(error -> "- " + error)
                                                        .toList())));
                        continue;
                    }
                    try {
                        admission.prepareFeedback(
                                identity, CompositionPackages.review(attempt, read.review(), publicationContext));
                        holder[0] = read.review();
                        ending = Ending.STORED;
                        results.add(new ModelSession.ToolResult(call.id(), storedText(read.review())));
                    } catch (PublicationContextRefusedException refused) {
                        stop = new Stop(CompositionStop.PUBLICATION_REFUSED, refused.reason());
                        ending = refused.reason() == FeedbackStopReason.PUBLIC_HISTORY_CHANGED
                                ? Ending.RECOMPOSE
                                : Ending.NONE;
                        results.add(new ModelSession.ToolResult(call.id(), "The review was not stored."));
                    } catch (FeedbackTextRefusedException refused) {
                        results.add(new ModelSession.ToolResult(
                                call.id(),
                                "review refused, nothing was stored: " + textRefusal(refused)
                                        + "\nSend the whole review again."));
                    } catch (AdmissionConflictException refused) {
                        results.add(
                                new ModelSession.ToolResult(
                                        call.id(),
                                        "The server refused the review; nothing was stored. Every text must rest on its basedOn and stay within the inputs this turn supplied; correct it and send the whole review again."));
                    }
                }
                if (ending != null) return ending;
                if (!room) return stopped(outOfRoom());
                String then = null;
                if (!nudged && session.calls() >= bound - 2) {
                    nudged = true;
                    then = ReviewOnWork.NUDGE;
                }
                reply = session.answer(results, then);
            }
        }

        private static String storedText(ReviewOnWork.ComposedReview review) {
            String said = review.summary() == null
                    ? "no summary"
                    : "a summary resting on " + review.summary().basedOn().size() + " result(s) and problem(s)";
            return "Stored the review: " + said + ", " + review.inline().size() + " line note(s), "
                    + review.withheld().size() + " withholding decision(s). It is final.";
        }

        // ------------------------------------------------------------ private feedback

        private void composePrivately(
                ComposerFiles files,
                CapturedWork captured,
                JsonNode folderIndex,
                List<String> practiceSlugs,
                ReviewOnWork.@Nullable ComposedReview review,
                Map<String, List<String>> routes,
                List<String> notReached) {
            PrivateFeedback.stageHistory(state.privateHistory(), files);
            var targets = PrivateFeedback.preparedTargets(files, mapper);
            var witnesses = PrivateFeedback.deliveredWitnesses(files, mapper, CapturedWork.captureTime(folderIndex));
            var store = new PrivateFeedback.Store(
                    List.copyOf(practiceSlugs),
                    request,
                    state,
                    targets,
                    witnesses,
                    routes,
                    piece -> preparePiece(piece, targets));
            String turn = privateTurn(files, captured, request, state, review, routes, notReached);
            var tools = List.of(new ModelSession.Tool(
                    "report_feedback",
                    PrivateFeedback.TOOL_DESCRIPTION,
                    PrivateFeedback.toolParameters(List.copyOf(practiceSlugs), request)));
            var session = new ModelSession(transport, mapper, settings, privateSystem, tools);
            if (expired()) {
                stopFor(CompositionStop.DEADLINE);
                return;
            }
            if (!fits(session, turn)) {
                log.warn("The private feedback input exceeds the model's context window: jobId={}", job.getId());
                stopFor(CompositionStop.CONTEXT_WINDOW);
                return;
            }
            int bound = bound(PrivateFeedback.composable(state, routes).size());
            var reply = session.send(turn);
            boolean askedOnceMore = false;
            boolean nudged = false;
            int providerFailures = 0;
            while (true) {
                var failure = reply.failure();
                boolean room = session.calls() < bound && !expired();
                if (failure == ModelSession.Failure.PROVIDER) {
                    if (!room || !pause(++providerFailures)) {
                        stopFor(CompositionStop.PROVIDER);
                        break;
                    }
                    reply = session.retry();
                    continue;
                }
                if (failure != null) {
                    stopFor(causeOf(failure));
                    break;
                }
                var left = PrivateFeedback.undecided(state, store.feedback, routes);
                if (reply.toolCalls().isEmpty()) {
                    if (left.isEmpty()) break;
                    if (!room) {
                        stopFor(outOfRoom());
                        break;
                    }
                    if (askedOnceMore) break;
                    askedOnceMore = true;
                    reply = session.send(PrivateFeedback.finishText(left, request, store.feedback));
                    continue;
                }
                var results = new ArrayList<ModelSession.ToolResult>();
                for (var call : reply.toolCalls()) {
                    String text = switch (call.name()) {
                        case "report_feedback" -> store.call(call.arguments()).text();
                        default ->
                            "There is no tool named " + call.name() + "; persist the feedback with report_feedback.";
                    };
                    results.add(new ModelSession.ToolResult(call.id(), text));
                }
                // After it was asked once more, the session ends as soon as every practice is decided.
                if (askedOnceMore
                        && PrivateFeedback.undecided(state, store.feedback, routes)
                                .isEmpty()) break;
                if (!room) {
                    stopFor(outOfRoom());
                    break;
                }
                String then = null;
                if (!nudged && session.calls() >= bound - 2) {
                    nudged = true;
                    then = PrivateFeedback.NUDGE;
                }
                reply = session.answer(results, then);
            }
        }

        /** Prepares one stored piece; the refusal text when the server refuses it. */
        private @Nullable String preparePiece(
                PrivateFeedback.Piece piece, List<PrivateFeedback.PreparedTarget> targets) {
            String primary = state.results().stream()
                    .filter(result -> piece.practiceSlug().equals(Js.string(result.path("practiceSlug"))))
                    .map(result -> Evidence.text(result.path("resultId")))
                    .findFirst()
                    .orElse(null);
            PrepareFeedbackRequestDTO prepared = CompositionPackages.piece(attempt, piece, targets, primary);
            try {
                admission.prepareFeedback(identity, prepared);
                return null;
            } catch (FeedbackTextRefusedException refused) {
                return textRefusal(refused);
            } catch (AdmissionConflictException refused) {
                return "its support, its target or its text failed the server's checks.";
            }
        }
    }

    /** The files a composition renders its turns from: the retained inputs, the request and the page support. */
    static ComposerFiles workspace(Map<String, byte[]> inputs, byte[] requestBytes, Admission state) {
        var files = new ComposerFiles(inputs);
        files.put(SandboxLayout.FEEDBACK_COMPOSITION_PATH, requestBytes);
        if (state.complete("PRACTICE_PAGE") && state.practicePageSupport() != null)
            files.put(ComposerFiles.SUPPORT, utf8(JsonText.stringify(state.practicePageSupport(), 2)));
        return files;
    }

    static List<String> practiceSlugs(JsonNode practiceIndex) {
        var slugs = new TreeSet<String>();
        Js.listOf(practiceIndex).forEach(practice -> slugs.add(Evidence.text(practice.path("slug"))));
        return List.copyOf(slugs);
    }

    /** The practices the review took on and admitted no result for: not settled, so nothing is said about them. */
    static List<String> notReached(JsonNode practiceIndex, Admission state) {
        var admitted = new LinkedHashSet<String>();
        state.results().forEach(result -> admitted.add(Evidence.text(result.path("practiceSlug"))));
        return Js.listOf(practiceIndex).stream()
                .map(practice -> Evidence.text(practice.path("slug")))
                .filter(slug -> !admitted.contains(slug))
                .toList();
    }

    /** The review turn rendered from one public history read, with the tool and checks its answer is read with. */
    record ReviewTurn(
            String text,
            ObjectNode toolParameters,
            Map<String, Evidence.Result> restable,
            boolean lineNotes,
            Map<String, ReviewOnWork.Witness> witnesses,
            @Nullable String publicationContext) {}

    static ReviewTurn reviewTurn(
            ComposerFiles files,
            CapturedWork captured,
            JsonNode folderIndex,
            JsonNode practiceIndex,
            Admission state,
            PrivateFeedback.Request request,
            JsonNode read,
            List<String> notReached,
            PrimarySource.Reader reader) {
        boolean lineNotes = request.onWorkCopyForms.contains("LINE_NOTE");
        var framing = new CapturedWork.Framing(null, null);
        var reviewable = ReviewOnWork.publicResults(state.results());
        String readAt = Js.string(read.path("readAt"));
        ObjectNode history = captured.publicReviewHistory(folderIndex, framing);
        var alreadySaid = ReviewOnWork.priorPublicFeedback(
                ReviewOnWork.publicHistory(read),
                thisWork(files, folderIndex),
                Js.string(history.path("capturedAt")),
                readAt);
        var witnesses = ReviewOnWork.priorAdviceWitnesses(alreadySaid.feedback(), history.path("statements"));
        var eligible = witnesses.entrySet().stream()
                .filter(entry -> entry.getValue().eligibleForAlreadySaid())
                .map(Map.Entry::getKey)
                .toList();
        String primary = PrimarySource.reference(
                files,
                ComposerFiles.CONTEXT_ROOT,
                ComposerFiles.REPOSITORY_ROOT,
                folderIndex,
                new ArrayList<>(state.results()),
                reader);
        String sameWork = String.join(
                "\n\n",
                List.of(captured.sameWorkContext(folderIndex, framing), primary).stream()
                        .filter(part -> !part.isEmpty())
                        .toList());
        String text = ReviewOnWork.turn(new ReviewOnWork.TurnInput(
                sameWork,
                reviewable,
                ReviewOnWork.publicQualifications(state.results()),
                alreadySaid,
                readAt,
                history,
                practices(practiceIndex, reviewable),
                notReached,
                lineNotes,
                slug -> files.text(ComposerFiles.criteria(slug)).orElse(null)));
        var restable = Evidence.byId(reviewable);
        return new ReviewTurn(
                text,
                ReviewOnWork.toolParameters(restable, lineNotes, eligible),
                restable,
                lineNotes,
                witnesses,
                Js.string(read.path("publicationContext")));
    }

    /** The private turn: the captured-work brief, then what the private feedback is composed from. */
    static String privateTurn(
            ComposerFiles files,
            CapturedWork captured,
            PrivateFeedback.Request request,
            Admission state,
            ReviewOnWork.@Nullable ComposedReview review,
            Map<String, List<String>> routes,
            List<String> notReached) {
        String brief = captured.brief();
        return (brief.isEmpty() ? "" : brief + "\n\n")
                + PrivateFeedback.turn(files, MAPPER, request, state, review, routes, notReached);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static List<UUID> ids(List<ObjectNode> results) {
        return results.stream()
                .map(result -> UUID.fromString(Evidence.text(result.path("resultId"))))
                .toList();
    }

    /** A review on the work that an earlier attempt prepared, as this composition holds one it composed. */
    static ReviewOnWork.ComposedReview review(ResultAdmissionService.PreparedReview prepared) {
        String summary = prepared.summary();
        return new ReviewOnWork.ComposedReview(
                summary == null ? null : new ReviewOnWork.Summary(summary, evidence(prepared.summaryBasis())),
                prepared.notes().stream()
                        .map(note -> new ReviewOnWork.InlineNote(
                                note.body(),
                                evidence(note.basedOn()),
                                new ReviewOnWork.Anchor(
                                        note.anchor().problemId().toString(),
                                        note.anchor().citationIndex())))
                        .toList(),
                prepared.withheld().stream()
                        .map(decision -> new ReviewOnWork.Withheld(
                                evidence(decision.basedOn()), decision.reason(), decision.witnessIds()))
                        .toList());
    }

    private static List<Evidence.Ref> evidence(List<FeedbackCompositionTrace.Support> support) {
        return support.stream()
                .map(ref -> new Evidence.Ref(
                        ref.resultId().toString(),
                        ref.problemId() == null ? null : ref.problemId().toString()))
                .toList();
    }

    /** The reviewed work's identity, derived from the captured record. */
    static @Nullable String thisWork(ComposerFiles files, JsonNode folderIndex) {
        var metadata = files.json(ComposerFiles.CONTEXT_ROOT + "/metadata.json", MAPPER)
                .orElse(null);
        if (metadata == null || !folderIndex.isObject() || !metadata.isObject()) return null;
        String kind = Js.string(folderIndex.path("itemKind"));
        boolean pullRequest = "scm.pull_request".equals(kind);
        if (!pullRequest && !"scm.issue".equals(kind)) return null;
        return ReviewOnWork.workIdentity(
                folderIndex.path("itemKind"), metadata.path(pullRequest ? "pr_url" : "html_url"));
    }

    /** The practices of the reviewable results, as the practice index names them. */
    static List<ReviewOnWork.Practice> practices(JsonNode practiceIndex, List<ObjectNode> reviewable) {
        var slugs = new LinkedHashSet<String>();
        reviewable.forEach(result -> slugs.add(Evidence.text(result.path("practiceSlug"))));
        var practices = new ArrayList<ReviewOnWork.Practice>();
        for (JsonNode practice : Js.listOf(practiceIndex)) {
            String slug = Js.string(practice.path("slug"));
            if (slug == null || !slugs.contains(slug)) continue;
            practices.add(new ReviewOnWork.Practice(
                    slug,
                    Evidence.text(practice.path("name")),
                    Js.string(practice.path("whyItMatters")),
                    Js.listOf(practice.path("knownLimitations")).stream()
                            .map(JsonNode::asString)
                            .toList(),
                    Js.string(practice.path("versionId"))));
        }
        return practices;
    }
}
