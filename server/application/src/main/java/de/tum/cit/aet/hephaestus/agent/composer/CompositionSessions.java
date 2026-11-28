package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.PublicationContextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackPackage;
import de.tum.cit.aet.hephaestus.agent.job.CompositionToolTrace;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackEditBudgetSpentException;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepairStatus;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.NoteThreadState;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedPackage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The server side of feedback composition. It renders each session of an admitted review's composition from the
 * retained basis in PostgreSQL and runs each tool the composer calls; {@link CompositionClosing} closes what the
 * composition left open. The composer loop owns only the loop: its calls, retries and the cause each destination
 * stopped with.
 *
 * <p>What a call decided is kept in PostgreSQL, in the composition's tool trace: the package each check accepted, each
 * publication and each refusal's reason. A publish reads the checked package back from the trace, a private
 * destination publishes once, and a withholding records the reason the server refused. Nothing else is kept between
 * calls, so the loop's memory decides no guardrail.
 */
@Service
@ConditionalOnAgentRuntime
public class CompositionSessions {
    private static final Logger log = LoggerFactory.getLogger(CompositionSessions.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static final Duration READBACK_PAUSE = Duration.ofSeconds(1);
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int DEFAULT_MAX_TOKENS = 16_384;
    /** What the model must correct when the server refuses a piece's support. */
    private static final String PRIVATE_CONFLICT = "Every piece must rest on its basedOn and stay within the inputs"
            + " this turn supplied; correct the package and check it again.";

    private final ResultAdmissionService admission;
    private final PracticeReviewProperties properties;
    private final ObjectMapper mapper;
    private final FeedbackRepair repairs;
    private final CompositionToolTrace toolTrace;
    private final CompositionPlans plans;
    private final Duration readbackPause;
    private final String reviewSystem;
    private final String privateSystem;

    @Autowired
    public CompositionSessions(
            ResultAdmissionService admission,
            PracticeReviewProperties properties,
            ObjectMapper mapper,
            FeedbackRepair repairs,
            CompositionToolTrace toolTrace,
            CompositionPlans plans) {
        this(admission, properties, mapper, repairs, toolTrace, plans, READBACK_PAUSE);
    }

    CompositionSessions(
            ResultAdmissionService admission,
            PracticeReviewProperties properties,
            ObjectMapper mapper,
            FeedbackRepair repairs,
            CompositionToolTrace toolTrace,
            CompositionPlans plans,
            Duration readbackPause) {
        this.admission = admission;
        this.properties = properties;
        this.mapper = mapper;
        this.repairs = repairs;
        this.toolTrace = toolTrace;
        this.plans = plans;
        this.readbackPause = readbackPause;
        String style = ComposerPrompts.sharedStyle();
        this.reviewSystem = ComposerPrompts.reviewSystem();
        this.privateSystem = style + "\n\n" + ComposerPrompts.prompt("feedback-composer.md");
    }

    // ------------------------------------------------------------ the wire records

    /** A tool the model may call. */
    public record Tool(String name, String description, ObjectNode parameters) {}

    /** The model settings frozen on the attempt, and the window its requests must fit. */
    public record Model(
            LlmApiProtocol protocol,
            String modelId,
            @Nullable ReasoningEffort reasoningEffort,
            int maxOutputTokens,
            @Nullable Double temperature) {}

    /** What {@link #next} answers: a session to run, or the cause that stops every destination. */
    public sealed interface Next permits Session, Unavailable {}

    /**
     * One model session of the composition: the review on the work, or one private destination.
     *
     * @param input the first user turn
     * @param finish the turn sent once when the model calls no tool and something is undecided; null when nothing is
     * @param nudge the turn added to the tool results two calls before the session's bound
     * @param accepted whether this destination's publication is already accepted, so that only quiet edits remain
     * @param fits whether the first request fits the model's context window with room for its answer
     * @param calls the most model calls the session may make, before the composition's own cap
     * @param publicationContext the public history the review was rendered on; the loop sends it with each tool call
     */
    public record Session(
            FeedbackDestination destination,
            Model model,
            String system,
            String input,
            List<Tool> tools,
            @Nullable String finish,
            String nudge,
            boolean accepted,
            boolean fits,
            int calls,
            @Nullable String publicationContext)
            implements Next {}

    /** The retained inputs are gone or erased: every destination stops with this cause, and no model is called. */
    public record Unavailable(CompositionStop cause) implements Next {}

    /**
     * One tool call of a session. It carries nothing that the server must trust: what earlier calls decided is read
     * from the composition's trace.
     *
     * @param publicationContext the session's public history, as {@link Session#publicationContext} gave it; a review
     *     checked on another history is composed again
     * @param deadline the composition's deadline; the server never waits for a readback past it
     */
    public record ToolCall(
            FeedbackDestination destination,
            String id,
            String name,
            JsonNode arguments,
            @Nullable String publicationContext,
            Instant deadline) {}

    /** How a tool call ended the review session it ran in. */
    public enum Ending {
        /** The review on the work is decided. */
        STORED,
        /** The public history changed under the review: compose it again from a fresh read. */
        RECOMPOSE,
        /** The session stopped with {@link ToolResult#stop}. */
        STOPPED
    }

    /**
     * The tool's answer: the text the model reads, and what the call decided.
     *
     * @param published whether the call accepted a publication
     * @param ending set when the call ended the review session
     * @param stop the cause the session ended with, when the call decided one
     */
    public record ToolResult(
            String text,
            boolean published,
            @Nullable Ending ending,
            @Nullable CompositionStop stop) {
        static ToolResult text(String text) {
            return new ToolResult(text, false, null, null);
        }
    }

    /** A tool's answer and the decision the trace keeps for it. */
    private record Answered(ToolResult result, CompositionToolTrace.Decision decision) {
        static Answered text(FeedbackDestination destination, String text) {
            return new Answered(ToolResult.text(text), CompositionToolTrace.Decision.none(destination));
        }
    }

    // ------------------------------------------------------------ the session order

    /**
     * The next session the composition runs, rendered from the retained inputs: the review on the work first, then
     * each open private destination in order. Empty when nothing is left to compose.
     *
     * @param finished the destinations whose sessions this composition already ended; a review that is composed again
     *     after its history changed is not finished
     */
    public Optional<Next> next(
            ReviewJob job, ResultAdmissionService.AdmissionIdentity identity, Set<FeedbackDestination> finished) {
        var plan = plans.found(job, identity);
        if (plan == null) return Optional.empty();
        CompositionPlans.Basis basis;
        try {
            basis = plans.basis(job, plan);
        } catch (ReviewCompositionContinuation.InputsUnavailableException unavailable) {
            var cause = unavailable.erased() ? CompositionStop.INPUTS_ERASED : CompositionStop.INPUTS_GONE;
            repairs.stop(job.getWorkspaceId(), job.getReviewId(), cause.name());
            return Optional.of(new Unavailable(cause));
        }
        var state = plan.state();
        if (!finished.contains(FeedbackDestination.ON_WORK)) {
            var reviewable = ReviewOnWork.publicResults(state.results());
            boolean due = state.publicReviewed()
                    ? repairs.status(job.getWorkspaceId(), job.getReviewId()).state()
                            == FeedbackRepairStatus.State.READBACK
                    : plan.request().open("ON_WORK") && !reviewable.isEmpty();
            if (due) return Optional.of(reviewing(job, () -> reviewSession(job, identity, plan, basis, reviewable)));
        }
        var routes = PrivateFeedback.closeUnrouted(plan.request(), state, new ArrayList<>());
        if (PrivateFeedback.composable(state, routes).isEmpty()) return Optional.empty();
        for (String destination : plan.request().openPrivate()) {
            if (finished.contains(FeedbackDestination.valueOf(destination))) continue;
            var scoped = scoped(plan.request(), routes, destination);
            if (!PrivateFeedback.composable(state, scoped.routes()).isEmpty())
                return Optional.of(privateSession(job, plan, basis, scoped));
        }
        return Optional.empty();
    }

    private Session reviewSession(
            ReviewJob job,
            ResultAdmissionService.AdmissionIdentity identity,
            CompositionPlans.Plan plan,
            CompositionPlans.Basis basis,
            List<ObjectNode> reviewable) {
        var turn = reviewTurn(identity, plan, basis);
        boolean accepted = plan.state().publicReviewed();
        String input = turn.text();
        if (accepted)
            input +=
                    "\nThis composition already accepted its publication. Use only edit_own for a checked correction.\n"
                            + readbackText(mapper, repairs.statusWithRepeats(job.getWorkspaceId(), job.getReviewId()));
        var tools = List.of(
                new Tool("check_package", ReviewOnWork.TOOL_DESCRIPTION, turn.toolParameters()),
                publishTool(),
                editTool());
        var left = reviewable.stream()
                .map(result -> Evidence.text(result.path("resultId")))
                .toList();
        return session(
                job,
                FeedbackDestination.ON_WORK,
                reviewSystem,
                input,
                tools,
                ReviewOnWork.finishText(left),
                ReviewOnWork.NUDGE,
                accepted,
                turn.publicationContext());
    }

    private Session privateSession(
            ReviewJob job, CompositionPlans.Plan plan, CompositionPlans.Basis basis, Scoped scoped) {
        var state = plan.state();
        PrivateFeedback.stageHistory(state.privateHistory(), basis.files());
        var review = priorReview(job, state);
        String turn = ComposerTurns.privateTurn(
                basis.files(), basis.captured(), scoped.request(), state, review, scoped.routes(), basis.notReached());
        var tools = List.of(
                new Tool(
                        "check_package",
                        PrivateFeedback.TOOL_DESCRIPTION,
                        PrivateFeedback.toolParameters(basis.practiceSlugs(), scoped.request())),
                publishTool());
        var left = PrivateFeedback.undecided(state, List.of(), scoped.routes());
        return session(
                job,
                scoped.destination(),
                privateSystem,
                turn,
                tools,
                left.isEmpty() ? null : PrivateFeedback.finishText(left, scoped.request(), List.of()),
                PrivateFeedback.NUDGE,
                false,
                null);
    }

    private Session session(
            ReviewJob job,
            FeedbackDestination destination,
            String system,
            String input,
            List<Tool> tools,
            @Nullable String finish,
            String nudge,
            boolean accepted,
            @Nullable String publicationContext) {
        var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), mapper);
        var model = new Model(
                snapshot.model().apiProtocol(),
                snapshot.model().upstreamModelId(),
                snapshot.model().reasoningEffort(),
                Objects.requireNonNullElse(snapshot.maxOutputTokens(), DEFAULT_MAX_TOKENS),
                properties.samplingTemperature());
        int window = Objects.requireNonNullElse(snapshot.contextWindow(), DEFAULT_CONTEXT_WINDOW);
        // About four characters per token over what the first request carries: the system prompt, the tools and
        // the turn.
        long characters =
                (long) system.length() + mapper.writeValueAsString(tools).length() + input.length();
        boolean fits = characters / 4 + model.maxOutputTokens() <= window;
        if (!fits)
            log.warn("The {} composition input exceeds the model's context window: jobId={}", destination, job.getId());
        return new Session(
                destination,
                model,
                system,
                input,
                tools,
                finish,
                nudge,
                accepted,
                fits,
                properties.practiceModelCalls(),
                publicationContext);
    }

    /** Records why the review on the work stopped, so that its readback correction is not pending any more. */
    public void stop(ReviewJob job, String reason) {
        repairs.stop(job.getWorkspaceId(), job.getReviewId(), reason);
    }

    /**
     * Runs a step of the review on the work. A failure that no attempt expects stops the review's correction with
     * {@code UNEXPECTED}, so that no later attempt starts another model session for it.
     */
    private <T> T reviewing(ReviewJob job, Supplier<T> step) {
        try {
            return step.get();
        } catch (ResultAdmissionService.StaleAttemptException
                | ReviewCompositionContinuation.InputsUnavailableException gone) {
            throw gone;
        } catch (RuntimeException failed) {
            repairs.stop(job.getWorkspaceId(), job.getReviewId(), CompositionStop.UNEXPECTED.name());
            throw failed;
        }
    }

    // ------------------------------------------------------------ the tools

    /** Runs one tool call of a session and records it, with what it decided, in the composition's trace. */
    public ToolResult tool(ReviewJob job, ResultAdmissionService.AdmissionIdentity identity, ToolCall call) {
        Answered answered;
        try {
            answered = call.destination() == FeedbackDestination.ON_WORK
                    ? reviewing(job, () -> reviewTool(job, identity, call))
                    : privateTool(job, identity, call);
        } catch (ResultAdmissionService.StaleAttemptException
                | AdmissionConflictException
                | ReviewCompositionContinuation.InputsUnavailableException answeredByTheRoute) {
            throw answeredByTheRoute;
        } catch (RuntimeException failed) {
            // A defect of the server ends the session with its own cause, never as a failed request.
            log.atError()
                    .addKeyValue("event.name", "agent.composition.tool_failed")
                    .addKeyValue("error.type", failed.getClass().getName())
                    .setCause(failed)
                    .log("A composition tool failed");
            answered = new Answered(
                    new ToolResult(
                            "The server could not run this tool. This session stops.",
                            false,
                            Ending.STOPPED,
                            CompositionStop.UNEXPECTED),
                    CompositionToolTrace.Decision.none(call.destination()));
        }
        toolTrace.record(
                identity,
                call.id(),
                call.name(),
                call.arguments(),
                answered.result().text(),
                answered.decision());
        return answered.result();
    }

    /** The package a check at this destination accepted under this draft ID, as this attempt's trace holds it. */
    private @Nullable FeedbackPackage checked(
            ResultAdmissionService.AdmissionIdentity identity, FeedbackDestination destination, String draftId) {
        return toolTrace.decisions(identity).stream()
                .filter(decision -> decision.destination() == destination
                        && draftId.equals(decision.draftId())
                        && decision.proposal() != null)
                .reduce((earlier, later) -> later)
                .map(decision -> mapper.treeToValue(decision.proposal(), FeedbackPackage.class))
                .orElse(null);
    }

    private Answered reviewTool(ReviewJob job, ResultAdmissionService.AdmissionIdentity identity, ToolCall call) {
        var destination = FeedbackDestination.ON_WORK;
        if (!Set.of("check_package", "publish_package", "edit_own").contains(call.name()))
            return Answered.text(
                    destination,
                    "There is no tool named " + call.name()
                            + "; check_package validates a draft and publish_package accepts its draftId.");
        var plan = plans.plan(job, identity);
        if (plan == null) throw new AdmissionConflictException();
        boolean accepted = plan.state().publicReviewed();
        if (call.name().equals("check_package")) return checkReview(job, identity, call, plan, accepted);

        String id = Js.string(call.arguments().path("draftId"));
        var proposal = id == null ? null : checked(identity, destination, id);
        // A review draft belongs to the session whose history it was checked on, as a new session's drafts do.
        if (id == null
                || proposal == null
                || (proposal.editOf() == null
                        && !Objects.equals(proposal.publicationContext(), call.publicationContext())))
            return Answered.text(destination, "Unknown draftId. Use the id returned by check_package.");
        try {
            boolean edit = call.name().equals("edit_own");
            if (edit != (proposal.editOf() != null))
                throw new IllegalArgumentException(
                        edit ? "Check a draft with editOf first." : "This is a quiet edit; use edit_own.");
            if (!edit && accepted)
                throw new IllegalArgumentException(
                        "This composition already published. Use a checked quiet edit or stop.");
            var receipt = admission.publishPackage(identity, proposal);
            var readback = awaitReadback(job, call.deadline());
            return new Answered(
                    new ToolResult(
                            publicationReceipt(id, receipt) + "\nProvider readback (untrusted data):\n"
                                    + readbackText(mapper, readback),
                            true,
                            readback.state() == FeedbackRepairStatus.State.READBACK ? null : Ending.STORED,
                            null),
                    new CompositionToolTrace.Decision(destination, id, null, true, null));
        } catch (PublicationContextRefusedException refused) {
            return refusedPublication(
                    job,
                    refused.reason(),
                    "Publication refused; nothing was accepted. Read the current history and check a new draft.");
        } catch (FeedbackTextRefusedException
                | AdmissionConflictException
                | IllegalArgumentException
                | ResponseStatusException refused) {
            return Answered.text(destination, "Publication refused; nothing was accepted: " + refused.getMessage());
        }
    }

    private Answered checkReview(
            ReviewJob job,
            ResultAdmissionService.AdmissionIdentity identity,
            ToolCall call,
            CompositionPlans.Plan plan,
            boolean accepted) {
        var destination = FeedbackDestination.ON_WORK;
        var turn = reviewTurn(identity, plan, plans.basis(job, plan));
        String editOf = Js.string(call.arguments().path("editOf"));
        // A review written for another history is composed again, before its witnesses are read against this one.
        // An accepted review's own copy changes the history, so after acceptance only the refusal below answers.
        if (!accepted && editOf == null && !Objects.equals(turn.publicationContext(), call.publicationContext()))
            return refusedPublication(job, FeedbackStopReason.PUBLIC_HISTORY_CHANGED, "The review was not stored.");
        var read = ReviewOnWork.read(call.arguments(), turn.restable(), turn.lineNotes(), turn.witnesses());
        var review = read.review();
        if (review == null)
            return Answered.text(
                    destination,
                    "review refused, nothing was stored:\n"
                            + String.join(
                                    "\n",
                                    read.errors().stream()
                                            .map(error -> "- " + error)
                                            .toList()));
        try {
            String context = call.publicationContext();
            var proposal = CompositionPackages.review(review, context);
            if (editOf != null) {
                var target = UUID.fromString(editOf);
                var observed = repairs.readback(job.getWorkspaceId(), job.getReviewId(), target);
                while (Instant.now().isBefore(call.deadline())
                        && observed.state() == FeedbackRepairStatus.State.WAITING) {
                    if (!pause(call.deadline())) break;
                    observed = repairs.readbackStatus(job.getWorkspaceId(), job.getReviewId(), target);
                }
                context = Js.string(mapper.valueToTree(admission.publicFeedbackHistory(identity))
                        .path("publicationContext"));
                proposal =
                        new FeedbackPackage(proposal.basisResultIds(), proposal.composition(), true, context, target);
            }
            if (accepted && proposal.editOf() == null)
                throw new IllegalArgumentException(
                        "Publication already accepted. Check a correction with editOf, or stop.");
            admission.checkPackage(identity, proposal);
            String id = proposal.draftId(job.getReviewId());
            return new Answered(
                    ToolResult.text(
                            checkedReceipt(id, ReviewOnWork.repeats(review, turn.earlierNotes(), turn.restable()))),
                    new CompositionToolTrace.Decision(destination, id, mapper.valueToTree(proposal), false, null));
        } catch (PublicationContextRefusedException refused) {
            return refusedPublication(job, refused.reason(), "The review was not stored.");
        } catch (FeedbackTextRefusedException refused) {
            return Answered.text(
                    destination,
                    "review refused, nothing was stored: " + textRefusal(refused) + "\nSend the whole review again.");
        } catch (AdmissionConflictException refused) {
            return Answered.text(
                    destination,
                    "The server refused the review; nothing was stored. Every text must rest on its basedOn and stay within the inputs this turn supplied; correct it and send the whole review again.");
        } catch (IllegalArgumentException | ResponseStatusException refused) {
            String text = "Package check refused; nothing was accepted: " + refused.getMessage();
            if (refused instanceof FeedbackEditBudgetSpentException) {
                repairs.stop(job.getWorkspaceId(), job.getReviewId(), "STEP_LIMIT");
                return new Answered(
                        new ToolResult(text, false, Ending.STORED, null),
                        CompositionToolTrace.Decision.none(destination));
            }
            return Answered.text(destination, text);
        }
    }

    /**
     * A publication the server refused for its context. A changed history composes the review again; any other reason
     * stops the review with it. The trace keeps the reason, which the withholding records.
     */
    private Answered refusedPublication(ReviewJob job, FeedbackStopReason reason, String text) {
        var decision = new CompositionToolTrace.Decision(FeedbackDestination.ON_WORK, null, null, false, reason);
        if (reason == FeedbackStopReason.PUBLIC_HISTORY_CHANGED)
            return new Answered(
                    new ToolResult(text, false, Ending.RECOMPOSE, CompositionStop.PUBLICATION_REFUSED), decision);
        repairs.stop(job.getWorkspaceId(), job.getReviewId(), reason.name());
        return new Answered(new ToolResult(text, false, Ending.STOPPED, CompositionStop.PUBLICATION_REFUSED), decision);
    }

    private Answered privateTool(ReviewJob job, ResultAdmissionService.AdmissionIdentity identity, ToolCall call) {
        var destination = call.destination();
        if (!call.name().equals("check_package") && !call.name().equals("publish_package"))
            return Answered.text(
                    destination, "Unknown tool; use check_package, then publish_package with its draftId.");
        var plan = plans.found(job, identity);
        if (plan == null) throw new AdmissionConflictException();
        if (call.name().equals("publish_package")) return publishPrivately(job, identity, call);
        var state = plan.state();
        // Each call reads the admission again, and routing narrows the request to what is still open: a destination
        // that its session opened can be closed by now, because its routing read failed or nothing is left to compose.
        var unavailable = new ArrayList<String>();
        var routes = PrivateFeedback.closeUnrouted(plan.request(), state, unavailable);
        if (unavailable.contains(destination.name()))
            return Answered.text(
                    destination,
                    "Where this feedback may go cannot be read now. Stop this session; nothing was checked, and the "
                            + "server records why the destination is still open.");
        if (!plan.request().open(destination.name()))
            return Answered.text(
                    destination,
                    "This destination has nothing left to compose. Stop this session; nothing was checked.");
        var basis = plans.basis(job, plan);
        var scoped = scoped(plan.request(), routes, destination.name());
        PrivateFeedback.stageHistory(state.privateHistory(), basis.files());
        var targets = PrivateFeedback.preparedTargets(basis.files(), mapper);
        var witnesses = PrivateFeedback.deliveredWitnesses(
                basis.files(), mapper, CapturedWork.captureTime(basis.folderIndex()));
        var inspected = new PrivateFeedback.Draft(
                basis.practiceSlugs(), scoped.request(), state, targets, witnesses, scoped.routes());
        var outcome = inspected.call(call.arguments());
        var missing = PrivateFeedback.undecided(state, inspected.feedback, scoped.routes());
        if (!inspected.complete() || !missing.isEmpty())
            return Answered.text(
                    destination,
                    "Package not checked; nothing was accepted. " + outcome.text()
                            + (missing.isEmpty() ? "" : " Decide all eligible practices: " + missing));
        try {
            var proposal = CompositionPackages.pieces(inspected.feedback, targets, piece -> primary(state, piece));
            admission.checkPackage(identity, proposal);
            String id = proposal.draftId(job.getReviewId());
            return new Answered(
                    ToolResult.text(checkedReceipt(id, List.of())),
                    new CompositionToolTrace.Decision(destination, id, mapper.valueToTree(proposal), false, null));
        } catch (FeedbackTextRefusedException refused) {
            return Answered.text(destination, "Package check refused; nothing was accepted: " + textRefusal(refused));
        } catch (AdmissionConflictException refused) {
            return Answered.text(destination, "Package check refused; nothing was accepted. " + PRIVATE_CONFLICT);
        } catch (PublicationContextRefusedException | IllegalArgumentException | ResponseStatusException refused) {
            return Answered.text(destination, "Package check refused; nothing was accepted: " + refused.getMessage());
        }
    }

    /** Publishes the package a check accepted at this private destination, once for each attempt. */
    private Answered publishPrivately(ReviewJob job, ResultAdmissionService.AdmissionIdentity identity, ToolCall call) {
        var destination = call.destination();
        String id = Js.string(call.arguments().path("draftId"));
        var proposal = id == null ? null : checked(identity, destination, id);
        if (id == null || proposal == null)
            return Answered.text(destination, "Unknown draftId. Check the complete package first.");
        // A checked package decides every practice the destination may carry, so a destination whose prepared rows
        // leave nothing to compose has accepted its package. Routing closes such a destination.
        var current = Objects.requireNonNull(plans.plan(job, identity));
        var routes = PrivateFeedback.closeUnrouted(current.request(), current.state(), new ArrayList<>());
        if (!current.request().open(destination.name())
                || PrivateFeedback.composable(
                                current.state(),
                                scoped(current.request(), routes, destination.name())
                                        .routes())
                        .isEmpty())
            return Answered.text(
                    destination,
                    "Publication refused; nothing was accepted: This destination already accepted its package; do not publish another.");
        try {
            var receipt = admission.publishPackage(identity, proposal);
            return new Answered(
                    new ToolResult(publicationReceipt(id, receipt), true, null, null),
                    new CompositionToolTrace.Decision(destination, id, null, true, null));
        } catch (FeedbackTextRefusedException refused) {
            return Answered.text(destination, "Publication refused; nothing was accepted: " + textRefusal(refused));
        } catch (AdmissionConflictException refused) {
            return Answered.text(destination, "Publication refused; nothing was accepted. " + PRIVATE_CONFLICT);
        } catch (PublicationContextRefusedException | IllegalArgumentException | ResponseStatusException refused) {
            return Answered.text(destination, "Publication refused; nothing was accepted: " + refused.getMessage());
        }
    }

    private static @Nullable String primary(Admission state, PrivateFeedback.Piece piece) {
        return state.results().stream()
                .filter(result -> piece.practiceSlug().equals(Js.string(result.path("practiceSlug"))))
                .map(result -> Evidence.text(result.path("resultId")))
                .findFirst()
                .orElse(null);
    }

    /** Polls the cheap status; only the status the composer reads carries the repeats. */
    private FeedbackRepairStatus awaitReadback(ReviewJob job, Instant deadline) {
        while (Instant.now().isBefore(deadline)) {
            if (repairs.status(job.getWorkspaceId(), job.getReviewId()).state() != FeedbackRepairStatus.State.WAITING)
                break;
            if (!pause(deadline)) break;
        }
        return repairs.statusWithRepeats(job.getWorkspaceId(), job.getReviewId());
    }

    /** Waits once for a readback, never past the deadline. */
    private boolean pause(Instant deadline) {
        Duration wait = readbackPause.isZero() ? Duration.ofMillis(10) : readbackPause;
        Duration left = Duration.between(Instant.now(), deadline);
        Duration nap = wait.compareTo(left) < 0 ? wait : left;
        try {
            if (nap.isPositive()) Thread.sleep(nap);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return Instant.now().isBefore(deadline);
    }

    private static Tool editTool() {
        var schema = JSON.objectNode().put("type", "object").put("additionalProperties", false);
        schema.withObject("properties").set("draftId", JSON.objectNode().put("type", "string"));
        schema.putArray("required").add("draftId");
        return new Tool(
                "edit_own",
                "Quietly correct the existing provider copies named by editOf in an exact checked draft. "
                        + "Never posts another comment. At most three edits per composition; a person's state wins. Returns actual provider readback.",
                schema);
    }

    private static Tool publishTool() {
        var schema = JSON.objectNode().put("type", "object").put("additionalProperties", false);
        schema.withObject("properties").set("draftId", JSON.objectNode().put("type", "string"));
        schema.putArray("required").add("draftId");
        return new Tool(
                "publish_package",
                "Accept the exact checked draft for publication once and return provider readback. Use its check_package draftId. "
                        + "Publication rechecks admission, currentness, consent, text and placement; approval and delivery retain their server fences.",
                schema);
    }

    /**
     * The delivery facts as the composer reads them. A repeat shows the other note's opening words, not its whole
     * text, and says that the other note raised the problem. Its thread is OPEN when the mirror of the provider held it
     * unresolved at the readback, and UNKNOWN when the mirror holds no thread for it.
     */
    static String readbackText(ObjectMapper mapper, FeedbackRepairStatus status) {
        ObjectNode shown = mapper.valueToTree(status);
        if (status.repeats().isEmpty()) {
            shown.remove("repeats");
            return shown.toString();
        }
        for (JsonNode repeat : shown.path("repeats")) {
            var other = (ObjectNode) repeat.path("otherNote");
            String body = Js.string(other.path("body"));
            other.remove("body");
            if (body != null && !Js.blank(body)) other.put("quote", ReviewOnWork.quote(body));
        }
        var next = new ArrayList<String>();
        next.add(
                "Each repeat is a note of yours that cites a problem that another note on this work raised (otherNote).");
        next.addAll(ReviewOnWork.threadStates(status.repeats().stream()
                .map(repeat -> repeat.otherNote().thread())
                .toList()));
        next.add(
                "To point to it, check a correction with editOf that changes only your note's words, then edit_own; or keep your note.");
        return shown.put("repeatsNext", String.join(" ", next)).toString();
    }

    static String checkedReceipt(String id, List<ReviewOnWork.Repeat> repeats) {
        var receipt = JSON.objectNode().put("draftId", id).put("checked", true);
        if (!repeats.isEmpty()) {
            var listed = receipt.putArray("repeats");
            for (var repeat : repeats) {
                var row = listed.addObject().put("entry", repeat.entry());
                repeat.problemIds().forEach(row.putArray("problemIds")::add);
                if (repeat.alreadyCitedBy() != null) row.put("alreadyCitedBy", repeat.alreadyCitedBy());
                var note = repeat.earlierNote();
                if (note != null)
                    row.putObject("earlierNote")
                            .put("path", note.path())
                            .put("startLine", note.startLine())
                            .put("endLine", note.endLine())
                            .put("link", note.link())
                            .put("thread", (note.threadOpen() ? NoteThreadState.OPEN : NoteThreadState.UNKNOWN).name());
                if (repeat.quote() != null) row.put("quote", repeat.quote());
            }
        }
        var next = new ArrayList<String>();
        if (repeats.stream().anyMatch(repeat -> repeat.alreadyCitedBy() != null))
            next.add(
                    "A repeat with alreadyCitedBy cites a problem that another note of this draft already cites: merge the notes, drop one, or keep both when each says something the other does not.");
        var earlier = repeats.stream()
                .map(ReviewOnWork.Repeat::earlierNote)
                .filter(Objects::nonNull)
                .map(note -> note.threadOpen() ? NoteThreadState.OPEN : NoteThreadState.UNKNOWN)
                .toList();
        if (!earlier.isEmpty()) {
            next.add(
                    "A repeat with earlierNote cites a problem that an earlier note on this work raised. Hephaestus did not close that note as fixed.");
            next.addAll(ReviewOnWork.threadStates(earlier));
            next.add("Drop the entry, or keep it only when it says what the earlier note does not.");
        }
        next.add(
                repeats.isEmpty()
                        ? "Correct the draft and check again, or publish_package with this draftId."
                        : "Then check again, or publish_package with this draftId.");
        return receipt.put("next", String.join(" ", next)).toString();
    }

    private static String publicationReceipt(String id, PreparedPackage receipt) {
        var result = JSON.objectNode()
                .put("draftId", id)
                .put("accepted", true)
                .put("digest", receipt.packageDigest())
                .put("acceptedAt", receipt.preparedAt().toString())
                .put(
                        "next",
                        "Publication is accepted through the server's approval and delivery path. Do not publish another package here.");
        var ids = result.putArray("feedbackIds");
        receipt.pieces().forEach(piece -> ids.add(piece.feedbackId().toString()));
        return result.toString();
    }

    /** What the model must rewrite: the server names the rule and the field, never the text. */
    static String textRefusal(FeedbackTextRefusedException refused) {
        String broken = switch (refused.rule()) {
            case "LINK" -> "has a link that no cited quote supplies. Write no links: Hephaestus adds its own";
            case "COUNT" ->
                "states a number that no cited quote supplies. Leave the count out, or state only a number that a cited quote has";
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

    // ------------------------------------------------------------ what a session reads

    /** One private destination's request and routes. */
    private record Scoped(
            FeedbackDestination destination, PrivateFeedback.Request request, Map<String, List<String>> routes) {}

    private static Scoped scoped(
            PrivateFeedback.Request request, Map<String, List<String>> routes, String destination) {
        var scopedRequest = new PrivateFeedback.Request(
                Map.of(destination, Objects.requireNonNull(request.destinations.get(destination))),
                request.minDistinctItems,
                request.onWorkCopyForms);
        var scopedRoutes = new LinkedHashMap<String, List<String>>();
        routes.forEach((practice, destinations) -> {
            if (destinations.contains(destination)) scopedRoutes.put(practice, List.of(destination));
        });
        return new Scoped(FeedbackDestination.valueOf(destination), scopedRequest, scopedRoutes);
    }

    /** The review on the work that this or an earlier attempt prepared, which the private feedback follows. */
    private ReviewOnWork.@Nullable ComposedReview priorReview(ReviewJob job, Admission state) {
        if (!state.publicReviewed()) return null;
        return admission.preparedReview(job).map(ComposerTurns::review).orElse(null);
    }

    private ReviewCompositionTurn reviewTurn(
            ResultAdmissionService.AdmissionIdentity identity,
            CompositionPlans.Plan plan,
            CompositionPlans.Basis basis) {
        // Read now, not at capture: feedback delivered while this review ran was said here too.
        JsonNode read = mapper.valueToTree(admission.publicFeedbackHistory(identity));
        return ComposerTurns.reviewTurn(
                basis.files(),
                basis.captured(),
                basis.folderIndex(),
                basis.practiceIndex(),
                plan.state(),
                plan.request(),
                read,
                basis.notReached(),
                PrimarySource.captured(
                        basis.files(), basis.folderIndex(), ComposerFiles.CONTEXT_ROOT, ComposerFiles.REPOSITORY_ROOT));
    }
}
