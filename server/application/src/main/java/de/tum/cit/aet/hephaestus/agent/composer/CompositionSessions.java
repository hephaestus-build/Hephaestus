package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.PublicationContextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackPackage;
import de.tum.cit.aet.hephaestus.agent.job.CompositionToolTrace;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.FeedbackWritingGuide;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackEditBudgetSpentException;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepairStatus;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedPackage;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The server side of feedback composition. It renders each session of an admitted review's composition from the
 * retained basis in PostgreSQL, runs each tool the composer calls, and closes what the composition left open. The
 * composer loop owns only the loop: its calls, retries and the cause each destination stopped with.
 *
 * <p>What a call decided is kept in PostgreSQL, in the composition's tool trace: the package each check accepted, each
 * publication and each refusal's reason. A publish reads the checked package back from the trace, a private
 * destination publishes once, and a withholding records the reason the server refused. Nothing else is kept between
 * calls, so the loop's memory decides no guardrail.
 */
@Service
@ConditionalOnWorkerRole
public class CompositionSessions {
    private static final Logger log = LoggerFactory.getLogger(CompositionSessions.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    /** Reads the retained files, which are plain JSON: no application module changes how they parse. */
    private static final JsonMapper MAPPER = new JsonMapper();

    private static final Duration READBACK_PAUSE = Duration.ofSeconds(1);
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int DEFAULT_MAX_TOKENS = 16_384;
    /** What the model must correct when the server refuses a piece's support. */
    private static final String PRIVATE_CONFLICT = "Every piece must rest on its basedOn and stay within the inputs"
            + " this turn supplied; correct the package and check it again.";

    private final ResultAdmissionService admission;
    private final ReviewCompositionContinuation continuation;
    private final ReviewJobAssignments assignments;
    private final ItemDescriptorRegistry items;
    private final PracticeReviewProperties properties;
    private final AgentCapacityProperties agentCapacity;
    private final MeterRegistry meters;
    private final ObjectMapper mapper;
    private final FeedbackRepair repairs;
    private final CompositionToolTrace toolTrace;
    private final Duration readbackPause;
    private final String reviewSystem;
    private final String privateSystem;

    @Autowired
    public CompositionSessions(
            ResultAdmissionService admission,
            ReviewCompositionContinuation continuation,
            ReviewJobAssignments assignments,
            ItemDescriptorRegistry items,
            PracticeReviewProperties properties,
            AgentCapacityProperties agentCapacity,
            MeterRegistry meters,
            ObjectMapper mapper,
            FeedbackRepair repairs,
            CompositionToolTrace toolTrace) {
        this(
                admission,
                continuation,
                assignments,
                items,
                properties,
                agentCapacity,
                meters,
                mapper,
                repairs,
                toolTrace,
                READBACK_PAUSE);
    }

    CompositionSessions(
            ResultAdmissionService admission,
            ReviewCompositionContinuation continuation,
            ReviewJobAssignments assignments,
            ItemDescriptorRegistry items,
            PracticeReviewProperties properties,
            AgentCapacityProperties agentCapacity,
            MeterRegistry meters,
            ObjectMapper mapper,
            FeedbackRepair repairs,
            CompositionToolTrace toolTrace,
            Duration readbackPause) {
        this.admission = admission;
        this.continuation = continuation;
        this.assignments = assignments;
        this.items = items;
        this.properties = properties;
        this.agentCapacity = agentCapacity;
        this.meters = meters;
        this.mapper = mapper;
        this.repairs = repairs;
        this.toolTrace = toolTrace;
        this.readbackPause = readbackPause;
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
     * When a composition that starts now stops: after its own timeout, and a minute before its attempt's work ends at
     * the latest. No model call starts after it.
     */
    public Instant deadline(Instant notAfter) {
        Instant own = Instant.now().plus(properties.compositionTimeout());
        Instant latest = notAfter.minus(Duration.ofMinutes(1));
        return own.isBefore(latest) ? own : latest;
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

    /** The cause a destination stopped with, and the reason its withholding records. */
    private record Stop(CompositionStop cause, FeedbackStopReason reason) {
        static Stop of(CompositionStop cause) {
            return new Stop(cause, cause.reason());
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
        var plan = found(job, identity);
        if (plan == null) return Optional.empty();
        Basis basis;
        try {
            basis = basis(job, plan);
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
            Plan plan,
            Basis basis,
            List<ObjectNode> reviewable) {
        var turn = reviewTurn(identity, plan, basis);
        boolean accepted = plan.state().publicReviewed();
        String input = turn.text();
        if (accepted)
            input +=
                    "\nThis composition already accepted its publication. Use only edit_own for a checked correction.\n"
                            + mapper.writeValueAsString(repairs.status(job.getWorkspaceId(), job.getReviewId()));
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

    private Session privateSession(ReviewJob job, Plan plan, Basis basis, Scoped scoped) {
        var state = plan.state();
        PrivateFeedback.stageHistory(state.privateHistory(), basis.files());
        var review = priorReview(job, state);
        String turn = privateTurn(
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
        var answered = call.destination() == FeedbackDestination.ON_WORK
                ? reviewing(job, () -> reviewTool(job, identity, call))
                : privateTool(job, identity, call);
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
        var plan = plan(job, identity);
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
                                    + mapper.writeValueAsString(readback),
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
            Plan plan,
            boolean accepted) {
        var destination = FeedbackDestination.ON_WORK;
        var turn = reviewTurn(identity, plan, basis(job, plan));
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
                    ToolResult.text(checkedReceipt(id)),
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
        var plan = found(job, identity);
        if (plan == null || !plan.request().open(destination.name())) throw new AdmissionConflictException();
        if (call.name().equals("publish_package")) return publishPrivately(job, identity, call);
        var basis = basis(job, plan);
        var state = plan.state();
        var scoped = scoped(
                plan.request(),
                PrivateFeedback.closeUnrouted(plan.request(), state, new ArrayList<>()),
                destination.name());
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
                    ToolResult.text(checkedReceipt(id)),
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
        var current = Objects.requireNonNull(plan(job, identity));
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

    private FeedbackRepairStatus awaitReadback(ReviewJob job, Instant deadline) {
        while (Instant.now().isBefore(deadline)) {
            var observed = repairs.status(job.getWorkspaceId(), job.getReviewId());
            if (observed.state() != FeedbackRepairStatus.State.WAITING) return observed;
            if (!pause(deadline)) break;
        }
        return repairs.status(job.getWorkspaceId(), job.getReviewId());
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

    private static String checkedReceipt(String id) {
        return JSON.objectNode()
                .put("draftId", id)
                .put("checked", true)
                .put("next", "Correct the draft and check again, or publish_package with this draftId.")
                .toString();
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

    // ------------------------------------------------------------ closing

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
        var plan = plan(job, identity);
        if (plan == null) return null;
        boolean last = job.spentAttempts() >= agentCapacity.reviewRetries();
        CompositionStop handedOn = null;
        for (var open : open(job, plan)) {
            var stop = open.routingUnavailable()
                    ? Stop.of(CompositionStop.ROUTING_UNAVAILABLE)
                    : stop(
                            identity,
                            open.destination(),
                            stops.getOrDefault(open.destination(), CompositionStop.NO_DECISION));
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

    private List<Open> open(ReviewJob job, Plan plan) {
        var state = admitted(job);
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
    private boolean withhold(Plan plan, FeedbackDestination destination, Stop stop, List<UUID> resultIds) {
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

    // ------------------------------------------------------------ what a composition reads

    /** What one attempt composes: its identity, the request it composes for and the admitted results. */
    @SuppressWarnings("ArrayRecordComponent") // routing narrows a parsed request, so each reader parses its own
    private record Plan(
            ResultAdmissionService.AdmissionIdentity identity,
            byte[] requestBytes,
            PrivateFeedback.Request request,
            Admission state) {}

    /** The attempt's plan, or null when the review has no destination to compose or no admitted result. */
    private @Nullable Plan plan(ReviewJob job, ResultAdmissionService.AdmissionIdentity identity) {
        return plan(job, identity, Map.of());
    }

    /**
     * The plan as this attempt found it: its sessions render and check what earlier attempts decided, so what this
     * attempt published is its own work, not a decision it found. Closing counts both.
     */
    private @Nullable Plan found(ReviewJob job, ResultAdmissionService.AdmissionIdentity identity) {
        var own = new LinkedHashMap<String, Set<String>>();
        var decisions = toolTrace.decisions(identity);
        for (var published : decisions) {
            if (!published.published() || published.destination() == FeedbackDestination.ON_WORK) continue;
            decisions.stream()
                    .filter(check -> check.destination() == published.destination()
                            && Objects.equals(check.draftId(), published.draftId())
                            && check.proposal() != null)
                    .findFirst()
                    .ifPresent(check -> mapper.treeToValue(check.proposal(), FeedbackPackage.class)
                            .composition()
                            .forEach(piece -> piece.resultIds()
                                    .forEach(id -> own.computeIfAbsent(
                                                    published.destination().name(), ignored -> new LinkedHashSet<>())
                                            .add(id.toString()))));
        }
        return plan(job, identity, own);
    }

    private @Nullable Plan plan(
            ReviewJob job, ResultAdmissionService.AdmissionIdentity identity, Map<String, Set<String>> own) {
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
        var state = admitted(job, own);
        if (state.results().isEmpty()) return null;
        return new Plan(identity, requestBytes, request, state);
    }

    /** The retained inputs a session renders from, with what the turns read of them. */
    private record Basis(
            ComposerFiles files,
            CapturedWork captured,
            JsonNode folderIndex,
            JsonNode practiceIndex,
            List<String> practiceSlugs,
            List<String> notReached) {}

    private Basis basis(ReviewJob job, Plan plan) {
        var files = workspace(continuation.retainedInputs(job), plan.requestBytes(), plan.state());
        JsonNode folderIndex = files.json(SandboxLayout.MANIFEST_PATH, mapper).orElse(JSON.missingNode());
        JsonNode practiceIndex =
                files.json(ComposerFiles.PRACTICE_INDEX, mapper).orElse(JSON.arrayNode());
        return new Basis(
                files,
                new CapturedWork(files, mapper),
                folderIndex,
                practiceIndex,
                practiceSlugs(practiceIndex),
                notReached(practiceIndex, plan.state()));
    }

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
        return admission.preparedReview(job).map(CompositionSessions::review).orElse(null);
    }

    private ReviewCompositionTurn reviewTurn(
            ResultAdmissionService.AdmissionIdentity identity, Plan plan, Basis basis) {
        // Read now, not at capture: feedback delivered while this review ran was said here too.
        JsonNode read = mapper.valueToTree(admission.publicFeedbackHistory(identity));
        return reviewTurn(
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

    /** The admitted results with the private feedback each destination's prepared pieces already decide. */
    private Admission admitted(ReviewJob job) {
        return admitted(job, Map.of());
    }

    /** The admitted results with what each destination's prepared pieces decide, without the results in {@code own}. */
    private Admission admitted(ReviewJob job, Map<String, Set<String>> own) {
        var decided = new LinkedHashMap<String, Set<String>>();
        admission.privateProgress(job).decidedByDestination().forEach((destination, results) -> {
            var ids = new LinkedHashSet<String>();
            results.forEach(id -> ids.add(id.toString()));
            ids.removeAll(own.getOrDefault(destination.name(), Set.of()));
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

    static ReviewCompositionTurn reviewTurn(
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
                new ArrayList<>(state.results().stream()
                        .filter(ReviewOnWork::publicNotMet)
                        .toList()),
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
        return new ReviewCompositionTurn(
                text,
                ReviewOnWork.toolParameters(restable, lineNotes, eligible),
                restable,
                lineNotes,
                witnesses,
                Js.string(read.path("publicationContext")));
    }

    /** The private turn includes proven primary source for its routed concerns, as the public turn does. */
    static String privateTurn(
            ComposerFiles files,
            CapturedWork captured,
            PrivateFeedback.Request request,
            Admission state,
            ReviewOnWork.@Nullable ComposedReview review,
            Map<String, List<String>> routes,
            List<String> notReached) {
        String brief = captured.brief();
        JsonNode folderIndex = files.json(SandboxLayout.MANIFEST_PATH, MAPPER).orElse(JSON.missingNode());
        var composable = PrivateFeedback.composable(state, routes);
        var eligible = state.results().stream()
                .filter(result -> {
                    String slug = result.path("practiceSlug").asString();
                    return composable.contains(slug)
                            && request.openPrivate().stream()
                                    .anyMatch(destination ->
                                            routes.getOrDefault(slug, List.of()).contains(destination));
                })
                .toList();
        String primary = PrimarySource.reference(
                files,
                ComposerFiles.CONTEXT_ROOT,
                ComposerFiles.REPOSITORY_ROOT,
                folderIndex,
                new ArrayList<>(eligible),
                PrimarySource.captured(files, folderIndex, ComposerFiles.CONTEXT_ROOT, ComposerFiles.REPOSITORY_ROOT));
        return String.join(
                "\n\n",
                List.of(brief, primary, PrivateFeedback.turn(files, MAPPER, request, state, review, routes, notReached))
                        .stream()
                        .filter(part -> !part.isEmpty())
                        .toList());
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static List<UUID> ids(List<ObjectNode> results) {
        return results.stream()
                .map(result -> UUID.fromString(Evidence.text(result.path("resultId"))))
                .toList();
    }

    /** A review on the work that an attempt prepared, as a composition holds one it composed. */
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
