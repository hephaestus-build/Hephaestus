package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextFolder;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * The turn's context folder ({@link TurnContextFolder}): {@code read}, {@code bash} and {@code load_context}, with the
 * turn's credential, while the attempt runs on this worker. Each answer is the tool's result or a refusal with
 * {@code ok: false} that the model reads. {@code .context/heph-1.0/workspace-contract.md} has the shapes.
 */
@RestController
@RequestMapping("/internal/llm/turn/{turnId}/sandbox")
@PreAuthorize("isAuthenticated()")
@ConditionalOnWorkerRole
@Profile(HephHostConfiguration.PROFILES)
@WorkspaceAgnostic("The turn credential binds the workspace, the developer and the conversation of every call")
public class HephTurnSandboxController {

    private final HephTurnContexts contexts;
    private final HephTurnDispatcher dispatcher;
    private final TurnContextFolder folder;

    public HephTurnSandboxController(
            HephTurnContexts contexts, HephTurnDispatcher dispatcher, TurnContextFolder folder) {
        this.contexts = contexts;
        this.dispatcher = dispatcher;
        this.folder = folder;
    }

    public record ReadRequest(
            @NotBlank @Size(max = 1024) String path,
            @Nullable @Positive Integer offset,
            @Nullable @Positive Integer limit) {}

    @PostMapping("/read")
    public ObjectNode read(
            @PathVariable UUID turnId, @Valid @RequestBody ReadRequest request, Authentication authentication) {
        TurnAttempt turn = HephTurnController.turn(dispatcher, turnId, authentication);
        return HephToolCalls.logged(
                turn,
                "read",
                () -> folder.read(
                        turn,
                        dispatcher.deadline(turn.turnId(), turn.attempt()),
                        contexts.folderDocuments(turn),
                        request.path(),
                        request.offset(),
                        request.limit()),
                HephTurnSandboxController::outcome);
    }

    public record ExecRequest(
            @NotBlank @Size(max = 65_536) String command,
            @Nullable @Positive Integer timeoutMs) {}

    @PostMapping("/exec")
    public ObjectNode exec(
            @PathVariable UUID turnId, @Valid @RequestBody ExecRequest request, Authentication authentication) {
        TurnAttempt turn = HephTurnController.turn(dispatcher, turnId, authentication);
        return HephToolCalls.logged(
                turn,
                "exec",
                () -> folder.exec(
                        turn,
                        dispatcher.deadline(turn.turnId(), turn.attempt()),
                        contexts.folderDocuments(turn),
                        request.command(),
                        request.timeoutMs()),
                HephTurnSandboxController::outcome);
    }

    public record LoadRequest(@NotBlank @Size(max = 512) String path) {}

    @PostMapping("/load")
    public ObjectNode load(
            @PathVariable UUID turnId, @Valid @RequestBody LoadRequest request, Authentication authentication) {
        TurnAttempt turn = HephTurnController.turn(dispatcher, turnId, authentication);
        return HephToolCalls.logged(
                turn,
                "load",
                () -> folder.load(
                        turn,
                        dispatcher.deadline(turn.turnId(), turn.attempt()),
                        contexts.folderDocuments(turn),
                        request.path()),
                HephTurnSandboxController::outcome);
    }

    /** {@code ok}, or the refusal code that the folder answered with. */
    private static String outcome(ObjectNode answer) {
        return answer.path("ok").asBoolean(true) ? "ok" : answer.path("error").asString("error");
    }
}
