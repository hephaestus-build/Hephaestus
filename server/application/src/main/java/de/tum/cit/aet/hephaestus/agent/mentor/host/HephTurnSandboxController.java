package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextFolder;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.node.ObjectNode;

/**
 * The turn's context folder ({@link TurnContextFolder}), with the turn's credential, while the attempt runs on this
 * worker. The host serves {@code read}, {@code bash} and {@code load_context} itself: it reads the turn's documents
 * here, binds the attempt's tools Pod here and dials it, and puts what it loads there. Each JSON answer is the result
 * or a refusal with {@code ok: false} that the model reads.
 */
@RestController
@RequestMapping("/internal/llm/turn/{turnId}/sandbox")
@PreAuthorize("isAuthenticated()")
@ConditionalOnWorkerRole
@Profile(HephHostConfiguration.PROFILES)
@WorkspaceAgnostic("The turn credential binds the workspace, the developer and the conversation of every call")
public class HephTurnSandboxController {

    /** The header of a load's archive that carries what the model reads about it, as Base64url of its JSON. */
    public static final String LOAD_HEADER = "Hephaestus-Load";

    /** The SHA-256 of a load's archive, in hex: the host announces it to the tools side ahead of the bytes. */
    public static final String LOAD_SHA256_HEADER = "Hephaestus-Load-Sha256";

    static final MediaType ARCHIVE = MediaType.parseMediaType("application/x-tar");

    private final HephTurnContexts contexts;
    private final HephTurnDispatcher dispatcher;
    private final TurnContextFolder folder;

    public HephTurnSandboxController(
            HephTurnContexts contexts, HephTurnDispatcher dispatcher, TurnContextFolder folder) {
        this.contexts = contexts;
        this.dispatcher = dispatcher;
        this.folder = folder;
    }

    public record DocumentRequest(
            @NotBlank @Size(max = 1024) String path,
            @Nullable @Positive Integer offset,
            @Nullable @Positive Integer limit) {}

    /** One page of {@code INDEX.md}, {@code FIELDS.md} or a context document, from the attempt's memory. */
    @PostMapping("/document")
    public ObjectNode document(
            @PathVariable UUID turnId, @Valid @RequestBody DocumentRequest request, Authentication authentication) {
        TurnAttempt turn = HephTurnController.turn(dispatcher, turnId, authentication);
        return HephToolCalls.logged(
                turn,
                "read",
                () -> folder.document(
                        turn, contexts.folderDocuments(turn), request.path(), request.offset(), request.limit()),
                HephTurnSandboxController::outcome);
    }

    /** The attempt's built documents as one archive, which the host puts into its tools Pod. */
    @GetMapping("/documents")
    public ResponseEntity<byte[]> documents(@PathVariable UUID turnId, Authentication authentication) {
        TurnAttempt turn = HephTurnController.turn(dispatcher, turnId, authentication);
        return ResponseEntity.ok().contentType(ARCHIVE).body(folder.documentsArchive(contexts.folderDocuments(turn)));
    }

    /** The attempt's tools Pod: its address and nonce, or a refusal. */
    @PostMapping("/bind")
    public ObjectNode bind(@PathVariable UUID turnId, Authentication authentication) {
        TurnAttempt turn = HephTurnController.turn(dispatcher, turnId, authentication);
        return HephToolCalls.logged(
                turn,
                "bind",
                () -> folder.bind(turn, dispatcher.deadline(turn.turnId(), turn.attempt())),
                HephTurnSandboxController::outcome);
    }

    public record DiscardRequest(@NotBlank @Size(max = 253) String id) {}

    /** The host found the attempt's tools Pod broken: the next bind gets a new one. */
    @PostMapping("/discard")
    public ResponseEntity<Void> discard(
            @PathVariable UUID turnId, @Valid @RequestBody DiscardRequest request, Authentication authentication) {
        TurnAttempt turn = HephTurnController.turn(dispatcher, turnId, authentication);
        folder.discard(turn, request.id());
        return ResponseEntity.noContent().build();
    }

    public record LoadRequest(@NotBlank @Size(max = 512) String path) {}

    /**
     * {@code load_context}: the item as an archive, with what the model reads about it in {@link #LOAD_HEADER}, or a
     * refusal as JSON.
     */
    @PostMapping("/load")
    public ResponseEntity<?> load(
            @PathVariable UUID turnId, @Valid @RequestBody LoadRequest request, Authentication authentication) {
        TurnAttempt turn = HephTurnController.turn(dispatcher, turnId, authentication);
        TurnContextFolder.Load load = HephToolCalls.logged(
                turn,
                "load",
                () -> folder.load(turn, dispatcher.deadline(turn.turnId(), turn.attempt()), request.path()),
                answered -> outcome(
                        switch (answered) {
                            case TurnContextFolder.Refused refused -> refused.answer();
                            case TurnContextFolder.Prepared prepared -> prepared.answer();
                        }));
        return switch (load) {
            case TurnContextFolder.Refused refused ->
                ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(refused.answer());
            case TurnContextFolder.Prepared prepared -> archive(prepared);
        };
    }

    private static ResponseEntity<StreamingResponseBody> archive(TurnContextFolder.Prepared prepared) {
        String about = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(prepared.answer().toString().getBytes(StandardCharsets.UTF_8));
        StreamingResponseBody body = out -> {
            try {
                Files.copy(prepared.archive(), out);
            } finally {
                prepared.delete();
            }
        };
        return ResponseEntity.ok()
                .contentType(ARCHIVE)
                .contentLength(prepared.size())
                .header(LOAD_HEADER, about)
                .header(LOAD_SHA256_HEADER, prepared.sha256())
                .body(body);
    }

    /** {@code ok}, or the refusal code that the folder answered with. */
    private static String outcome(ObjectNode answer) {
        return answer.path("ok").asBoolean(true) ? "ok" : answer.path("error").asString("error");
    }
}
