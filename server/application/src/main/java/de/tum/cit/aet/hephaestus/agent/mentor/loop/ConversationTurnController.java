package de.tum.cit.aet.hephaestus.agent.mentor.loop;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.mentor.context.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * The endpoints that the conversation loop calls for one attempt, on a worker's gateway, with the attempt's credential.
 * Each acts only for the turn, developer and conversation that the credential names, and only while the attempt holds
 * its lease ({@link MentorLeaseLostException}). The ledger classifies the entries that the end carries; the loop's word
 * is not taken for that.
 */
@RestController
@RequestMapping("/internal/llm/turn/{turnId}")
@Hidden
@PreAuthorize("isAuthenticated()")
@ConditionalOnWorkerRole
@Profile(ConversationAssignments.PROFILES)
@WorkspaceAgnostic("The turn credential binds the workspace, the developer and the conversation of every call")
public class ConversationTurnController {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnController.class);

    /**
     * How long an end waits for the proxy to record the attempt's last model call. The host reads the end of an answer
     * before the proxy has written the call's usage, which takes milliseconds after it. An end without an answer
     * cancels the calls first, so it waits only for their usage.
     */
    private final ConversationTurnContexts contexts;

    private final MentorTurnCompletion completion;
    private final ConversationAssignments dispatcher;
    private final ConversationFolder folder;
    private final ProxyCallsInFlight callsInFlight;

    public ConversationTurnController(
            ConversationTurnContexts contexts,
            MentorTurnCompletion completion,
            ConversationAssignments dispatcher,
            ConversationFolder folder,
            ProxyCallsInFlight callsInFlight) {
        this.contexts = contexts;
        this.completion = completion;
        this.dispatcher = dispatcher;
        this.folder = folder;
        this.callsInFlight = callsInFlight;
    }

    @GetMapping("/context")
    public ConversationTurnContexts.Context context(@PathVariable UUID turnId, Authentication authentication) {
        TurnAttempt turn = turn(turnId, authentication);
        return contexts.context(turn);
    }

    /** {@code link_result}: the echo admits the link; a refusal is {@code 422} and the host shows nothing. */
    public record ResultLink(@NotNull UUID resultId) {}

    @PostMapping("/link-result")
    public ResultLink linkResult(
            @PathVariable UUID turnId, @Valid @RequestBody ResultLink request, Authentication authentication) {
        TurnAttempt turn = turn(turnId, authentication);
        return ConversationToolCalls.logged(
                turn,
                "link_result",
                () -> {
                    if (!contexts.admitsResult(turn, request.resultId())) {
                        throw refused("it is not a result of the developer that Hephaestus may show");
                    }
                    return request;
                },
                linked -> "ok");
    }

    /** {@code link_problem}: admitted through the result that carries the problem. */
    public record ProblemLink(@NotNull UUID problemId) {}

    @PostMapping("/link-problem")
    public ProblemLink linkProblem(
            @PathVariable UUID turnId, @Valid @RequestBody ProblemLink request, Authentication authentication) {
        TurnAttempt turn = turn(turnId, authentication);
        return ConversationToolCalls.logged(
                turn,
                "link_problem",
                () -> {
                    if (!contexts.admitsProblem(turn, request.problemId())) {
                        throw refused("it is not a problem on a result of the developer that Hephaestus may show");
                    }
                    return request;
                },
                linked -> "ok");
    }

    /**
     * {@code show_feedback}: the echo means this attempt holds the claim on the feedback's conversation copy, so the
     * answer may show its words ({@link ConversationTurnContexts#showFeedback}).
     */
    public record FeedbackShow(@NotNull UUID feedbackId) {}

    @PostMapping("/show-feedback")
    public FeedbackShow showFeedback(
            @PathVariable UUID turnId, @Valid @RequestBody FeedbackShow request, Authentication authentication) {
        TurnAttempt turn = turn(turnId, authentication);
        return ConversationToolCalls.logged(
                turn,
                "show_feedback",
                () -> {
                    if (!contexts.showFeedback(turn, request.feedbackId())) {
                        throw refused("it is not conversation feedback that is ready for the developer now");
                    }
                    return request;
                },
                shown -> "ok");
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, reason);
    }

    /**
     * @param branch {@code main}, {@code this} or {@code all}; {@code all} when absent
     */
    public record SearchRequest(
            @Nullable @Size(max = 500) String query,
            @Nullable String branch,
            @Nullable Instant before,
            @Nullable Instant after,
            @Nullable Integer limit) {}

    public record SearchResponse(List<ConversationTurnContexts.SearchHit> hits) {}

    @PostMapping("/search")
    public SearchResponse search(
            @PathVariable UUID turnId, @Valid @RequestBody SearchRequest request, Authentication authentication) {
        TurnAttempt turn = turn(turnId, authentication);
        String branch = request.branch();
        MentorEntryService.SearchScope scope;
        try {
            scope = branch == null
                    ? MentorEntryService.SearchScope.ALL
                    : MentorEntryService.SearchScope.valueOf(branch.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "branch is one of main, this or all", unknown);
        }
        Integer limit = request.limit();
        var query = new MentorEntryService.SearchQuery(
                request.query(),
                scope,
                request.before(),
                request.after(),
                limit == null ? MentorEntryService.MAX_SEARCH_HITS : limit);
        return ConversationToolCalls.logged(
                turn, "search_conversation", () -> new SearchResponse(contexts.search(turn, query)), found -> "ok");
    }

    @PutMapping("/partial")
    public ResponseEntity<Void> partial(
            @PathVariable UUID turnId, @RequestBody JsonNode partial, Authentication authentication) {
        AttemptJwt token = token(turnId, authentication);
        if (!partial.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A partial answer is a JSON object");
        }
        dispatcher.partial(token, partial);
        return ResponseEntity.noContent().build();
    }

    public enum Outcome {
        DONE,
        STOPPED,
        FAILED
    }

    /** Why a failed attempt failed; the worker words it for the developer. */
    public enum FailureReason {
        ERROR,
        DEADLINE,
        /** A model call sent nothing for the host's idle bound ({@code hephaestus.mentor.turn.model-idle-timeout}). */
        STALLED
    }

    /**
     * The kinds of failure a host names in {@link EndRequest#detail}, as {@code heph-host-protocol.ts}
     * {@code FAILURE_DETAILS} lists them. The worker log takes no other detail: free text from a host can quote the
     * model provider, a tool or the developer.
     */
    static final Set<String> FAILURE_DETAILS = Set.of(
            "deadline",
            "call_limit",
            "upstream_stalled",
            "upstream_paused",
            "provider_unavailable",
            "model_error",
            "model_aborted",
            "no_answer",
            "empty_answer",
            "output_limit",
            "context_overflow",
            "worker_refused",
            "worker_unavailable",
            "host_error");

    /**
     * @param detail the kind of failure of a failed attempt, for the operator's log; never shown to the developer.
     *     Text outside {@link #FAILURE_DETAILS} is logged as unrecognized, not quoted.
     */
    public record EndRequest(
            @NotNull Outcome outcome,
            @NotNull @Size(max = 500) List<JsonNode> entries,
            @Nullable FailureReason reason,
            @Nullable @Size(max = 500) String detail,
            @Nullable JsonNode lastPartial) {}

    @PostMapping("/end")
    public ResponseEntity<Void> end(
            @PathVariable UUID turnId, @Valid @RequestBody EndRequest request, Authentication authentication) {
        // A stopped attempt still ends: it stores the answer it gave until the stop.
        TurnAttempt turn = dispatcher.identity(dispatcher.held(token(turnId, authentication)));
        JsonNode lastPartial = request.lastPartial();
        List<JsonNode> entries = validated(request.entries());
        MentorTurn.Status outcome = switch (request.outcome()) {
            case DONE -> MentorTurn.Status.DONE;
            case STOPPED -> MentorTurn.Status.STOPPED;
            case FAILED -> MentorTurn.Status.FAILED;
        };
        FailureReason reason = request.reason() == null ? FailureReason.ERROR : request.reason();
        String error = outcome != MentorTurn.Status.FAILED
                ? null
                : switch (reason) {
                    case DEADLINE -> ConversationAssignments.TOOK_TOO_LONG;
                    case STALLED -> ConversationAssignments.MODEL_STOPPED_ANSWERING;
                    case ERROR -> ConversationAssignments.COULD_NOT_FINISH;
                };
        String detail = request.detail();
        String cause = outcome != MentorTurn.Status.FAILED
                ? null
                : "its conversation loop reported " + reason
                        + (detail == null ? "" : ": " + (FAILURE_DETAILS.contains(detail) ? detail : "unrecognized"));
        // A host that gave up on its model call cannot wait for it: a silent provider would hold the end.
        if (!callsInFlight.settle(turn.turnId(), turn.attempt(), outcome != MentorTurn.Status.DONE)) {
            log.warn(
                    "conversation turn {} attempt {} ended with a model call still in flight; that call may go unbilled",
                    turn.turnId(),
                    turn.attempt());
        }
        try {
            completion.end(
                    turn.workspaceId(),
                    turn.turnId(),
                    turn.attempt(),
                    turn.leaseId(),
                    new MentorTurnCompletion.Ending(
                            outcome,
                            entries,
                            error,
                            cause,
                            callsInFlight.lastFailed(turn.turnId(), turn.attempt()),
                            lastPartial != null && lastPartial.isObject() ? lastPartial : null));
            callsInFlight.forgetFailed(turn.turnId(), turn.attempt());
        } finally {
            folder.ended(turn);
        }
        return ResponseEntity.noContent().build();
    }

    /** The running attempt that this request's turn credential names. */
    private TurnAttempt turn(UUID turnId, Authentication authentication) {
        return turn(dispatcher, turnId, authentication);
    }

    /**
     * The turn credential of this request, for this turn; any other credential is answered as if it did not exist.
     * The gateway already checked its live lease on the database clock.
     */
    static AttemptJwt token(UUID turnId, Authentication authentication) {
        if (!(authentication.getDetails() instanceof AttemptJwt token)
                || token.kind() != AttemptJwt.Kind.TURN
                || !token.id().equals(turnId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return token;
    }

    /**
     * The running attempt that the credential names, read under its complete lease fence: the token's workspace,
     * turn, attempt and lease.
     *
     * @throws MentorLeaseLostException when the attempt no longer holds its lease or no longer runs
     */
    static TurnAttempt turn(ConversationAssignments assignments, UUID turnId, Authentication authentication) {
        return assignments.running(token(turnId, authentication));
    }

    private static List<JsonNode> validated(List<JsonNode> entries) {
        for (JsonNode entry : entries) {
            if (!entry.isObject()
                    || !entry.path("type").isString()
                    || !entry.path("id").isString()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A Pi session entry is an object with a type and an id");
            }
        }
        return entries;
    }
}
