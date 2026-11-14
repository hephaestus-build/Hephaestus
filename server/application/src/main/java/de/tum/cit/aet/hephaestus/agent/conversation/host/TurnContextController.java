package de.tum.cit.aet.hephaestus.agent.conversation.host;

import de.tum.cit.aet.hephaestus.agent.conversation.sandbox.TurnContextLoads;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.node.ObjectNode;

/**
 * The turn's context folder ({@link TurnContextLoads}) for the run's sandbox. The driver serves {@code read}, {@code
 * bash} and {@code load_context} in the sandbox itself: it extracts the turn's documents and each load that it fetches
 * here. A load's refusal is JSON with {@code ok: false}, which the model reads.
 */
@RestController
@RequestMapping("/internal/agent/runs/{runId}/turn/sandbox")
@PreAuthorize("isAuthenticated()")
@ConditionalOnAgentRuntime
@Profile(ConversationTurnConfiguration.PROFILES)
@WorkspaceAgnostic("The sandbox's run names the workspace, and its turn names the developer and the conversation")
public class TurnContextController {

    /** The header of a load's archive that carries what the model reads about it, as Base64url of its JSON. */
    public static final String LOAD_HEADER = "Hephaestus-Load";

    /** The SHA-256 of a load's archive, in hex, for verification before extraction in the sandbox. */
    public static final String LOAD_SHA256_HEADER = "Hephaestus-Load-Sha256";

    static final MediaType ARCHIVE = MediaType.parseMediaType("application/x-tar");

    private final ConversationTurns turns;
    private final ConversationTurnContexts contexts;
    private final TurnContextLoads folder;
    private final Clock clock;

    public TurnContextController(
            ConversationTurnContexts contexts, ConversationTurns turns, TurnContextLoads folder, Clock clock) {
        this.turns = turns;
        this.contexts = contexts;
        this.folder = folder;
        this.clock = clock;
    }

    public record LoadRequest(@NotBlank @Size(max = 512) String path) {}

    /** An item listed in the context index is read on demand, then staged in the sandbox for Pi's read tool. */
    @PostMapping("/document")
    public ResponseEntity<byte[]> document(SandboxRun run, @Valid @RequestBody LoadRequest request) {
        return turns.write(run, false, turn -> {
            var bytes = contexts.document(turn, request.path())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(bytes);
        });
    }

    /**
     * {@code load_context}: the item as an archive, with what the model reads about it in {@link #LOAD_HEADER}, or a
     * refusal as JSON.
     */
    @PostMapping("/load")
    public WebAsyncTask<@Nullable ResponseEntity<?>> load(
            SandboxRun run, @Valid @RequestBody LoadRequest request, HttpServletResponse response) {
        Instant deadline = turns.workDeadline(run);
        // Spring cancels the response task at the work deadline, also when a client trickles archive reads.
        return new WebAsyncTask<>(
                Math.max(1, Duration.between(clock.instant(), deadline).toMillis()),
                () -> load(run, request, response, deadline));
    }

    private @Nullable ResponseEntity<?> load(
            SandboxRun run, LoadRequest request, HttpServletResponse response, Instant deadline) throws IOException {
        var turn = turns.write(run, false, held -> held);
        var admission = turns.write(run, false, ignored -> folder.admit(turn));
        if (admission.isPresent())
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(admission.get().answer());
        var load = ConversationToolCalls.logged(
                turn,
                "load",
                () -> folder.prepare(turn, deadline, request.path()),
                answered -> outcome(
                        switch (answered) {
                            case TurnContextLoads.Refused rejection -> rejection.answer();
                            case TurnContextLoads.Prepared prepared -> prepared.answer();
                        }));
        if (load instanceof TurnContextLoads.Refused refused)
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(refused.answer());
        try (TurnContextLoads.Prepared prepared = (TurnContextLoads.Prepared) load) {
            turns.write(run, false, ignored -> Boolean.TRUE);
            archive(prepared, response);
            return null;
        }
    }

    /** The response task owns source cleanup, including cancellation before streaming or during a blocked write. */
    private static void archive(TurnContextLoads.Prepared prepared, HttpServletResponse response) throws IOException {
        String about = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(prepared.answer().toString().getBytes(StandardCharsets.UTF_8));
        response.setContentType(ARCHIVE.toString());
        response.setContentLengthLong(prepared.size());
        response.setHeader(LOAD_HEADER, about);
        response.setHeader(LOAD_SHA256_HEADER, prepared.sha256());
        prepared.writeTo(response.getOutputStream());
        response.flushBuffer();
    }

    /** {@code ok}, or the refusal code that the folder answered with. */
    private static String outcome(ObjectNode answer) {
        return answer.path("ok").asBoolean(true) ? "ok" : answer.path("error").asString("error");
    }
}
