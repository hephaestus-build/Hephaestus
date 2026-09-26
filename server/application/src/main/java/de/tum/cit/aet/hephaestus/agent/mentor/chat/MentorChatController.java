package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.mentor.MentorAgentProperties;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SSE entry point for mentor chat. Sets the AI-SDK protocol header and submits a turn to the
 * mentor virtual-thread executor; the orchestration lives in {@link MentorChatService}.
 */
@WorkspaceScopedController
@RequestMapping("/mentor/chat")
@Tag(name = "Mentor Chat", description = "Mentor chat SSE stream")
@RequiredArgsConstructor
@Hidden
public class MentorChatController {

    private static final Logger log = LoggerFactory.getLogger(MentorChatController.class);
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(10).toMillis();

    private final MentorChatStarter mentorChatStarter;
    private final ObjectMapper objectMapperBean;
    private final MentorAgentProperties mentorAgentProperties;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Send one mentor-chat turn; stream the response as AI SDK UIMessage chunks")
    @PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
    public SseEmitter chat(
            WorkspaceContext workspaceContext,
            @Valid @RequestBody MentorChatRequestBody body,
            HttpServletResponse response) {
        if (!workspaceContext.mentorEnabled()) {
            throw new EntityNotFoundException("Mentor chat", workspaceContext.slug());
        }

        // Set protocol header before any SseEmitter#send — the first send commits the response.
        applyProtocolHeaders(response);

        String userMessage = extractUserMessage(body.message());
        if (userMessage == null || userMessage.isBlank()) {
            SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
            shortCircuitError(emitter, "User message text is empty.");
            return emitter;
        }
        int maxPromptChars = mentorAgentProperties.maxPromptChars();
        if (userMessage.length() > maxPromptChars) {
            SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
            shortCircuitError(emitter, "User message is too long (max " + maxPromptChars + " characters).");
            return emitter;
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        UUID threadId = body.id() != null ? body.id() : UUID.randomUUID();
        UUID clientUserMessageId = extractMessageId(body.message());
        MentorTurnRequest serviceRequest =
                MentorTurnRequest.web(workspaceContext.id(), threadId, userMessage, clientUserMessageId);
        mentorChatStarter.start(serviceRequest, emitter);
        return emitter;
    }

    private static void applyProtocolHeaders(HttpServletResponse response) {
        response.setHeader(UIMessageChunk.RESPONSE_HEADER, UIMessageChunk.PROTOCOL_VERSION);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
    }

    private static @Nullable String extractUserMessage(@Nullable JsonNode message) {
        if (message == null
                || !message.isObject()
                || !message.has("parts")
                || !message.get("parts").isArray()) {
            return null;
        }
        for (JsonNode part : message.get("parts")) {
            if (part.isObject() && "text".equals(textOrNull(part, "type"))) {
                String text = textOrNull(part, "text");
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * Extract the client-supplied UIMessage id (mint per outbound message in the AI-SDK
     * transport). Persistence requires it so the client's optimistic row resolves to the
     * server-side id for vote/regenerate. Null/non-UUID values fall back to a server-side mint.
     */
    private static @Nullable UUID extractMessageId(@Nullable JsonNode message) {
        if (message == null || !message.isObject()) return null;
        String raw = textOrNull(message, "id");
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            log.debug("Discarding non-UUID client message id '{}'", raw);
            return null;
        }
    }

    private static @Nullable String textOrNull(JsonNode node, String field) {
        return node.has(field) && node.get(field).isString() ? node.get(field).asString() : null;
    }

    /** Emit one error chunk + the AI-SDK [DONE] sentinel; complete the emitter. */
    private void shortCircuitError(SseEmitter emitter, String errorText) {
        try {
            String json = objectMapperBean.writeValueAsString(new UIMessageChunk.Error(errorText));
            emitter.send(SseEmitter.event().data(json));
            emitter.send(SseEmitter.event().data("[DONE]"));
        } catch (Exception ignored) {
            log.debug("Short-circuit emitter send failed; client disconnected");
        }
        emitter.complete();
    }
}
