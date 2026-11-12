package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnProgress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * The routes that a conversation sandbox's loop calls for the turn its run drives, on any replica's sandbox gateway.
 * Each acts only for the turn, developer and conversation of the run, and only while the run's claim holds it
 * ({@link HephTurns#held(SandboxRun, boolean)}); every replica reads that from the turn's and the run's rows. A tool in
 * the sandbox can call these routes too, so each one checks this itself. A stopped attempt, or one past its work
 * deadline, may only end ({@link HephTurns.StopException}). The ledger classifies the entries that the end carries; the
 * loop's word is not taken for that. The run's context, lease and end are the driver's ({@code AgentRunController}).
 */
@RestController
@RequestMapping("/internal/agent/runs/{runId}/turn")
@PreAuthorize("isAuthenticated()")
@ConditionalOnAgentRuntime
@Profile(HephTurnConfiguration.PROFILES)
@WorkspaceAgnostic("The sandbox's run names the workspace, and its turn names the developer and the conversation")
public class HephTurnController {

    private final HephTurnContexts contexts;

    private final MentorTurnCompletion completion;
    private final HephTurns turns;
    private final LlmUsageRecorder usage;

    public HephTurnController(
            HephTurnContexts contexts, MentorTurnCompletion completion, HephTurns turns, LlmUsageRecorder usage) {
        this.contexts = contexts;
        this.completion = completion;
        this.turns = turns;
        this.usage = usage;
    }

    /** {@code link_result}: the echo admits the link; a refusal is {@code 422} and the host shows nothing. */
    public record ResultLink(@NotNull UUID resultId) {}

    @PostMapping("/link-result")
    public ResultLink linkResult(SandboxRun run, @Valid @RequestBody ResultLink request) {
        return turns.write(run, false, turn -> {
            return HephToolCalls.logged(
                    turn,
                    "link_result",
                    () -> {
                        if (!contexts.admitsResult(turn, request.resultId())) {
                            throw refused("it is not a result of the developer that Hephaestus may show");
                        }
                        return request;
                    },
                    linked -> "ok");
        });
    }

    /** {@code link_problem}: admitted through the result that carries the problem. */
    public record ProblemLink(@NotNull UUID problemId) {}

    @PostMapping("/link-problem")
    public ProblemLink linkProblem(SandboxRun run, @Valid @RequestBody ProblemLink request) {
        return turns.write(run, false, turn -> {
            return HephToolCalls.logged(
                    turn,
                    "link_problem",
                    () -> {
                        if (!contexts.admitsProblem(turn, request.problemId())) {
                            throw refused("it is not a problem on a result of the developer that Hephaestus may show");
                        }
                        return request;
                    },
                    linked -> "ok");
        });
    }

    /**
     * {@code show_feedback}: the echo means this attempt holds the claim on the feedback's conversation copy, so the
     * answer may show its words ({@link HephTurnContexts#showFeedback}).
     */
    public record FeedbackShow(@NotNull UUID feedbackId) {}

    @PostMapping("/show-feedback")
    public FeedbackShow showFeedback(SandboxRun run, @Valid @RequestBody FeedbackShow request) {
        return turns.write(run, false, turn -> {
            return HephToolCalls.logged(
                    turn,
                    "show_feedback",
                    () -> {
                        if (!contexts.showFeedback(turn, request.feedbackId())) {
                            throw refused("it is not conversation feedback that is ready for the developer now");
                        }
                        return request;
                    },
                    shown -> "ok");
        });
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

    public record SearchResponse(List<HephTurnContexts.SearchHit> hits) {}

    @PostMapping("/search")
    public SearchResponse search(SandboxRun run, @Valid @RequestBody SearchRequest request) {
        return turns.write(run, false, turn -> {
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
            return HephToolCalls.logged(
                    turn, "search_conversation", () -> new SearchResponse(contexts.search(turn, query)), found -> "ok");
        });
    }

    /**
     * Sends the attempt's newest partial answer to readers.
     *
     * @param first whether it is the attempt's first, which times the attempt's first token
     */
    @PostMapping("/partial")
    public ResponseEntity<Void> partial(
            SandboxRun run,
            @RequestParam(defaultValue = "false") boolean first,
            @Valid @RequestBody MentorTurnProgress.Update update) {
        return turns.write(run, false, turn -> {
            if (!MentorTurnProgress.wellFormed(update)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A partial answer is a change of the one before it");
            }
            var start = first ? turns.start(run) : null;
            completion.progress(turn, update, start == null ? null : start.startedAt(), start != null && start.cold());
            return ResponseEntity.noContent().build();
        });
    }

    public enum Outcome {
        DONE,
        STOPPED,
        FAILED
    }

    /** Why a failed attempt failed; the server words it for the developer. */
    public enum FailureReason {
        ERROR,
        DEADLINE,
        /** A model call sent nothing for the host's idle bound ({@code hephaestus.mentor.turn.model-idle-timeout}). */
        STALLED
    }

    /**
     * The kinds of failure a host names in {@link EndRequest#detail}, as {@code heph-host-protocol.ts}
     * {@code FAILURE_DETAILS} lists them. The server log takes no other detail: free text from a host can quote the
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
    public ResponseEntity<Void> end(SandboxRun run, @Valid @RequestBody EndRequest request) {
        return turns.write(run, true, turn -> {
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
                        case DEADLINE -> MentorTurnCompletion.TOOK_TOO_LONG;
                        case STALLED -> HephTurns.MODEL_STOPPED_ANSWERING;
                        case ERROR -> HephTurns.COULD_NOT_FINISH;
                    };
            String detail = request.detail();
            String cause = outcome != MentorTurn.Status.FAILED
                    ? null
                    : "its conversation sandbox reported " + reason
                            + (detail == null
                                    ? ""
                                    : ": " + (FAILURE_DETAILS.contains(detail) ? detail : "unrecognized"));
            completion.end(
                    turn.workspaceId(),
                    turn.turnId(),
                    turn.attempt(),
                    turn.runId(),
                    new MentorTurnCompletion.Ending(
                            outcome,
                            entries,
                            error,
                            cause,
                            usage.latestFailure(turn.workspaceId(), turn.runId(), turn.attempt()),
                            request.lastPartial()));
            return ResponseEntity.noContent().build();
        });
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
