package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.TurnJwt;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The endpoints a Heph host calls for one turn, on this worker's sandbox gateway, with the turn's credential. Each
 * acts only for the turn, developer and conversation the credential names, and only while the attempt runs on this
 * worker ({@link MentorLeaseLostException}). The ledger classifies the
 * entries that the end carries; the host's word is not taken for that.
 */
@RestController
@RequestMapping("/internal/llm/turn/{turnId}")
@Hidden
@PreAuthorize("isAuthenticated()")
@ConditionalOnWorkerRole
@Profile(HephHostConfiguration.PROFILES)
@WorkspaceAgnostic("The turn credential binds the workspace, the developer and the conversation of every call")
public class HephTurnController {

    private static final Logger log = LoggerFactory.getLogger(HephTurnController.class);

    /**
     * How long an end waits for the proxy to record the attempt's last model call. The host reads the end of an answer
     * before the proxy has written the call's usage, which takes milliseconds after it. An end without an answer
     * cancels the calls first, so it waits only for their usage.
     */
    private final HephTurnContexts contexts;

    private final MentorTurnCompletion completion;
    private final HephTurnDispatcher dispatcher;
    private final ProxyCallsInFlight callsInFlight;
    private final String workerId;

    public HephTurnController(
            HephTurnContexts contexts,
            MentorTurnCompletion completion,
            HephTurnDispatcher dispatcher,
            ProxyCallsInFlight callsInFlight,
            WorkerProperties workerProperties) {
        this.contexts = contexts;
        this.completion = completion;
        this.dispatcher = dispatcher;
        this.callsInFlight = callsInFlight;
        this.workerId = workerProperties.resolvedWorkerId();
    }

    @GetMapping("/context")
    public HephTurnContexts.Context context(@PathVariable UUID turnId, Authentication authentication) {
        TurnJwt turn = turn(turnId, authentication);
        return contexts.context(turn);
    }

    public record FetchRequest(@NotBlank @Size(max = 300) String path) {}

    @PostMapping("/fetch-context")
    public ObjectNode fetchContext(
            @PathVariable UUID turnId, @Valid @RequestBody FetchRequest request, Authentication authentication) {
        TurnJwt turn = turn(turnId, authentication);
        try {
            return JsonNodeFactory.instance
                    .objectNode()
                    .set("content", contexts.fetch(turn, request.path().strip()));
        } catch (IllegalArgumentException notAResource) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, notAResource.getMessage(), notAResource);
        }
    }

    public record LinkRequest(@NotNull UUID observationId) {}

    @PostMapping("/link-observation")
    public ObjectNode linkObservation(
            @PathVariable UUID turnId, @Valid @RequestBody LinkRequest request, Authentication authentication) {
        TurnJwt turn = turn(turnId, authentication);
        if (!contexts.admits(turn, request.observationId())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "Heph may not show feedback about observation " + request.observationId() + " now");
        }
        return JsonNodeFactory.instance
                .objectNode()
                .put("observationId", request.observationId().toString());
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
    public SearchResponse search(
            @PathVariable UUID turnId, @Valid @RequestBody SearchRequest request, Authentication authentication) {
        TurnJwt turn = turn(turnId, authentication);
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
        return new SearchResponse(contexts.search(
                turn,
                new MentorEntryService.SearchQuery(
                        request.query(),
                        scope,
                        request.before(),
                        request.after(),
                        limit == null ? MentorEntryService.MAX_SEARCH_HITS : limit)));
    }

    @PutMapping("/partial")
    public ResponseEntity<Void> partial(
            @PathVariable UUID turnId, @RequestBody JsonNode partial, Authentication authentication) {
        TurnJwt turn = turn(turnId, authentication);
        if (!partial.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A partial answer is a JSON object");
        }
        dispatcher.partial(turn.turnId(), turn.attempt(), partial);
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
     * @param detail why a failed attempt failed, for the operator's log; never shown to the developer
     */
    public record EndRequest(
            @NotNull Outcome outcome,
            @NotNull @Size(max = 500) List<JsonNode> entries,
            @Nullable FailureReason reason,
            @Nullable @Size(max = 500) String detail) {}

    @PostMapping("/end")
    public ResponseEntity<Void> end(
            @PathVariable UUID turnId, @Valid @RequestBody EndRequest request, Authentication authentication) {
        TurnJwt turn = turn(turnId, authentication);
        List<JsonNode> entries = validated(request.entries());
        MentorTurn.Status outcome =
                switch (request.outcome()) {
                    case DONE -> MentorTurn.Status.DONE;
                    case STOPPED -> MentorTurn.Status.STOPPED;
                    case FAILED -> MentorTurn.Status.FAILED;
                };
        FailureReason reason = request.reason() == null ? FailureReason.ERROR : request.reason();
        String error = outcome != MentorTurn.Status.FAILED
                ? null
                : switch (reason) {
                    case DEADLINE -> HephTurnDispatcher.TOOK_TOO_LONG;
                    case STALLED -> HephTurnDispatcher.MODEL_STOPPED_ANSWERING;
                    case ERROR -> HephTurnDispatcher.COULD_NOT_FINISH;
                };
        String cause = outcome != MentorTurn.Status.FAILED
                ? null
                : "its Heph host reported " + reason + (request.detail() == null ? "" : ": " + request.detail());
        // A host that gave up on its model call cannot wait for it: a silent provider would hold the end.
        if (!callsInFlight.settle(turn.turnId(), turn.attempt(), outcome != MentorTurn.Status.DONE)) {
            log.warn(
                    "Heph turn {} attempt {} ended with a model call still in flight; that call may go unbilled",
                    turn.turnId(),
                    turn.attempt());
        }
        try {
            completion.end(
                    turn.turnId(),
                    turn.attempt(),
                    workerId,
                    new MentorTurnCompletion.Ending(outcome, entries, error, cause));
        } finally {
            contexts.forget(turn);
            dispatcher.ended(turn.turnId(), turn.attempt());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The turn credential of this request, for this turn; any other credential is answered as if it did not exist. The
     * attempt must run here, which this worker knows without reading the turn.
     *
     * @throws MentorLeaseLostException when the attempt no longer runs here
     */
    private TurnJwt turn(UUID turnId, Authentication authentication) {
        if (!(authentication.getDetails() instanceof TurnJwt turn)
                || !turn.turnId().equals(turnId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!dispatcher.runs(turn.turnId(), turn.attempt())) {
            throw new MentorLeaseLostException(turn.turnId(), turn.attempt());
        }
        return turn;
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
