package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.composer.CompositionSessions;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * The feedback composer's routes of a {@code review_compose} run (ADR 0079, ADR 0069): the next session, each tool
 * call, the stop of the review on the work, and the close. {@link CompositionSessions} renders and checks everything and
 * keeps what each call decided, so the composer loop in the sandbox holds no provider token and no state that a
 * guardrail depends on. Every write is fenced on the run's current lease in its own transaction; a stale claim answers
 * {@code 409}, and inputs that are no longer retained answer {@code 410} with the stop as the reason.
 */
@RestController
@RequestMapping("/internal/agent/runs/{runId}/composition")
@PreAuthorize("isAuthenticated()")
@ConditionalOnWorkerRole
@Profile("!specs & !cds-training")
@WorkspaceAgnostic("The sandbox token and the run's own row name the workspace, and the resolver checks both")
public class CompositionController {
    private final ResultAdmissionService admission;
    private final CompositionSessions sessions;

    public CompositionController(ResultAdmissionService admission, CompositionSessions sessions) {
        this.admission = admission;
        this.sessions = sessions;
    }

    @PostMapping("/next")
    public ResponseEntity<NextAnswer> next(SandboxRun run, @Valid @RequestBody NextRequest request) {
        return fenced(run, composing -> {
            var finished = EnumSet.noneOf(FeedbackDestination.class);
            finished.addAll(request.finished());
            var deadline = sessions.deadline(notAfter(composing));
            boolean last = sessions.lastAttempt(composing.job());
            return sessions.next(composing.job(), composing.identity(), finished)
                    .map(next -> switch (next) {
                        case CompositionSessions.Session session ->
                            ResponseEntity.ok(new NextAnswer(session, null, deadline, last));
                        case CompositionSessions.Unavailable unavailable ->
                            ResponseEntity.ok(new NextAnswer(null, unavailable.cause(), deadline, last));
                    })
                    .orElseGet(() -> ResponseEntity.noContent().build());
        });
    }

    @PostMapping("/tools/{name}")
    public CompositionSessions.ToolResult tool(
            SandboxRun run, @PathVariable String name, @Valid @RequestBody ToolRequest request) {
        return fenced(run, composing -> {
            // The server never waits for a readback past the run's own end.
            Instant bound = notAfter(composing);
            Instant deadline = request.deadline().isBefore(bound) ? request.deadline() : bound;
            return sessions.tool(
                    composing.job(),
                    composing.identity(),
                    new CompositionSessions.ToolCall(
                            request.destination(),
                            request.callId(),
                            name,
                            request.arguments(),
                            request.publicationContext(),
                            deadline));
        });
    }

    @PostMapping("/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(SandboxRun run, @Valid @RequestBody StopRequest request) {
        fenced(run, composing -> {
            sessions.stop(composing.job(), request.reason());
            return true;
        });
    }

    @PostMapping("/close")
    public CloseAnswer close(SandboxRun run, @Valid @RequestBody CloseRequest request) {
        return fenced(
                run,
                composing -> new CloseAnswer(sessions.close(composing.job(), composing.identity(), request.stops())));
    }

    private <T> T fenced(SandboxRun run, Function<ResultAdmissionService.Composing, T> operation) {
        try {
            return operation.apply(admission.composing(run.fence()));
        } catch (ResultAdmissionService.StaleAttemptException | AdmissionConflictException stale) {
            throw new StaleRunException(run.fence());
        } catch (ReviewCompositionContinuation.InputsUnavailableException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, unavailable.erased() ? "INPUTS_ERASED" : "INPUTS_GONE", unavailable);
        }
    }

    /** When the run ends whatever its lease; a run without a deadline is bounded by the composition's own timeout. */
    private static Instant notAfter(ResultAdmissionService.Composing composing) {
        return Objects.requireNonNullElse(composing.deadline(), Instant.MAX);
    }

    /** @param finished the destinations whose sessions the composition already ended */
    public record NextRequest(List<FeedbackDestination> finished) {
        public NextRequest {
            finished = List.copyOf(finished);
        }
    }

    /**
     * The next session of a composition, or the cause that stops every destination.
     *
     * @param deadline when a composition that starts now stops; the loop keeps the one its first call returned
     * @param lastAttempt whether this attempt is the review's last: a passing cause then withholds instead of handing on
     */
    public record NextAnswer(
            CompositionSessions.@Nullable Session session,
            @Nullable CompositionStop unavailable,
            Instant deadline,
            boolean lastAttempt) {}

    /**
     * One tool call of a composer session, as the model wrote it. It carries nothing the server must trust: what earlier
     * calls decided is read from the composition's tool trace and its prepared rows.
     *
     * @param publicationContext the session's public history, as the session gave it
     * @param deadline the composition's deadline
     */
    public record ToolRequest(
            FeedbackDestination destination,
            @NotBlank @Size(max = 256) String callId,
            JsonNode arguments,
            @Nullable String publicationContext,
            Instant deadline) {}

    /**
     * Why the review on the work stopped.
     *
     * @param reason a cause the composer loop observes, or {@code COMPLETE} when its accepted publication needs no edit
     */
    public record StopRequest(String reason) {
        public StopRequest {
            if (!"COMPLETE".equals(reason) && !CompositionStop.loopReported(reason))
                throw new IllegalArgumentException("Not a stop the composer loop reports");
        }
    }

    /** @param stops the cause each destination stopped with, as the loop observed it; the server records the reason */
    public record CloseRequest(Map<FeedbackDestination, CompositionStop> stops) {
        public CloseRequest {
            stops = Map.copyOf(stops);
            if (stops.values().stream().anyMatch(stop -> !CompositionStop.loopReported(stop.name())))
                throw new IllegalArgumentException("Not a stop the composer loop reports");
        }
    }

    /** @param handedOn the passing cause that left a destination open, so that another attempt composes it */
    public record CloseAnswer(@Nullable CompositionStop handedOn) {}
}
