package de.tum.cit.aet.hephaestus.agent.mentor.loop;

import de.tum.cit.aet.hephaestus.agent.mentor.context.ConversationContextDocuments;
import de.tum.cit.aet.hephaestus.agent.mentor.context.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsJobs;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the conversation loop needs for one attempt's context folder ({@link ConversationFolder}), with the attempt's
 * credential, and the renewal of its lease.
 */
@RestController
@RequestMapping("/internal/llm/turn/{turnId}")
@Hidden
@ConditionalOnWorkerRole
@Profile(ConversationAssignments.PROFILES)
@WorkspaceAgnostic("The turn credential binds the workspace, the developer and the conversation of every call")
public class ConversationToolsController {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ConversationAssignments assignments;
    private final ConversationFolder folder;
    private final ObjectMapper mapper;

    public ConversationToolsController(
            ConversationAssignments assignments, ConversationFolder folder, ObjectMapper mapper) {
        this.assignments = assignments;
        this.folder = folder;
        this.mapper = mapper;
    }

    /** {@code RUNNING}, or {@code STOPPED} once the developer stopped the turn; a lost lease is {@code 409}. */
    public record RenewalDTO(MentorTurn.Status status) {}

    @PostMapping("/renew")
    public RenewalDTO renew(@PathVariable UUID turnId, Authentication authentication) {
        return new RenewalDTO(assignments
                .renew(ConversationTurnController.token(turnId, authentication))
                .getStatus());
    }

    /**
     * @param generation the context the Job holds; a call that finds another generation asks for a new Job
     */
    public record ToolsEndpointDTO(String host, int port, String nonce, UUID generation) {}

    @PostMapping("/tools")
    public ToolsEndpointDTO acquire(@PathVariable UUID turnId, Authentication authentication) {
        TurnAttempt turn = ConversationTurnController.turn(assignments, turnId, authentication);
        ToolsJobs.Endpoint endpoint = folder.acquire(turn);
        return new ToolsEndpointDTO(endpoint.host(), endpoint.port(), endpoint.nonce(), turn.contextGeneration());
    }

    /** Deletes the attempt's Job of {@code generation}; a Job of a later generation stays. */
    @DeleteMapping("/tools/{generation}")
    public ResponseEntity<Void> discard(
            @PathVariable UUID turnId, @PathVariable UUID generation, Authentication authentication) {
        folder.discard(ConversationTurnController.turn(assignments, turnId, authentication), generation);
        return ResponseEntity.noContent().build();
    }

    /** The context folder's documents, as one tar, which the loop puts into its Job. */
    @GetMapping("/documents/archive")
    public ResponseEntity<byte[]> archive(@PathVariable UUID turnId, Authentication authentication) {
        TurnAttempt turn = ConversationTurnController.turn(assignments, turnId, authentication);
        byte[] archive = folder.archive(turn);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(archive.length)
                .header("X-Archive-Sha256", sha256Hex(archive))
                .header("X-Context-Generation", turn.contextGeneration().toString())
                .body(archive);
    }

    public record ReadRequest(@NotBlank @Size(max = 1024) String path) {}

    public record DocumentDTO(String text) {}

    @PostMapping("/documents/read")
    public ResponseEntity<DocumentDTO> read(
            @PathVariable UUID turnId, @Valid @RequestBody ReadRequest request, Authentication authentication) {
        TurnAttempt turn = ConversationTurnController.turn(assignments, turnId, authentication);
        return ConversationToolCalls.logged(
                turn,
                "read",
                () -> folder.document(turn, request.path())
                        .map(text -> ResponseEntity.ok()
                                .header(
                                        "X-Context-Generation",
                                        turn.contextGeneration().toString())
                                .body(new DocumentDTO(text)))
                        .orElseGet(() -> ResponseEntity.notFound().build()),
                found -> found.getStatusCode().is2xxSuccessful() ? "ok" : "missing");
    }

    public record LoadRequest(@NotBlank @Size(max = 512) String path) {}

    /**
     * {@code load_context}: the item as a tar, with what the model reads about it in {@code X-Tools-Load}, or a
     * refusal as JSON. The loop puts the tar into its Job at the item's path and runs its summary there.
     */
    @PostMapping("/documents/load")
    public ResponseEntity<?> load(
            @PathVariable UUID turnId, @Valid @RequestBody LoadRequest request, Authentication authentication) {
        TurnAttempt turn = ConversationTurnController.turn(assignments, turnId, authentication);
        String requested = request.path().strip();
        return ConversationToolCalls.logged(
                turn,
                "load",
                () -> switch (folder.load(turn, requested)) {
                    case ConversationContextDocuments.Load.Refused refused ->
                        refusal(refused.error(), refused.detail());
                    case ConversationContextDocuments.Load.Loaded loaded -> loaded(loaded);
                },
                answer -> answer.getBody() instanceof StreamingResponseBody ? "ok" : "refused");
    }

    private ResponseEntity<?> loaded(ConversationContextDocuments.Load.Loaded loaded) {
        try {
            return stream(loaded);
        } catch (RuntimeException failed) {
            // The body that deletes the archive never runs.
            try {
                Files.deleteIfExists(loaded.archive());
            } catch (IOException ignored) {
                failed.addSuppressed(ignored);
            }
            throw failed;
        }
    }

    private ResponseEntity<?> stream(ConversationContextDocuments.Load.Loaded loaded) {
        ObjectNode metadata = NODES.objectNode().put("ok", true).put("path", loaded.path());
        metadata.putObject("resolved")
                .put("ref", loaded.version().ref())
                .put("sha", loaded.version().id())
                .put("cite", loaded.version().cite());
        metadata.put("note", loaded.note());
        var summary = metadata.putArray("summary");
        loaded.summary().forEach(summary::add);
        StreamingResponseBody body = out -> {
            try (InputStream in = Files.newInputStream(loaded.archive())) {
                in.transferTo(out);
            } finally {
                Files.deleteIfExists(loaded.archive());
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(loaded.size())
                .header("X-Archive-Sha256", loaded.sha256())
                .header(
                        "X-Tools-Load",
                        Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(metadata)))
                .body(body);
    }

    private static ResponseEntity<?> refusal(String error, String detail) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(NODES.objectNode().put("ok", false).put("error", error).put("detail", detail));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
