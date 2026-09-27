package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.MentorChatRequest;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorVisibleTextSanitizer;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
public class CurrentThreadHistoryContentSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "current_thread_history.json";

    private static final int MAX_MESSAGES = 40;

    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof MentorChatRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        MentorChatRequest req = (MentorChatRequest) request;
        ObjectNode root = objectMapper.createObjectNode();
        root.put("threadId", req.threadId().toString());
        root.put("maxMessages", MAX_MESSAGES);

        List<ChatMessage> messages = chatMessageRepository.findContextMessages(
                req.workspaceId(), req.developerId(), req.threadId(), req.currentUserMessageId());
        int from = Math.max(0, messages.size() - MAX_MESSAGES);
        ArrayNode arr = root.putArray("messages");
        for (ChatMessage message : messages.subList(from, messages.size())) {
            String text = visibleText(message.getParts());
            if (text.isBlank()) {
                continue;
            }
            ObjectNode node = arr.addObject();
            node.put("role", message.getRole().name());
            node.put("createdAt", message.getCreatedAt().toString());
            node.put("text", text);
        }
        root.put("totalMessages", arr.size());

        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(root));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize current thread history context", e);
        }
    }

    private static String visibleText(@Nullable JsonNode parts) {
        if (parts == null || !parts.isArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode part : parts) {
            String type = part.path("type").asString("");
            String text;
            if ("text".equals(type)) {
                text = part.path("text").asString();
                if (MentorVisibleTextSanitizer.isLeakedInternalAnalysis(text)) {
                    continue;
                }
            } else if (UIMessageChunk.DataObservation.PART_TYPE.equals(type)) {
                // Feedback the reply showed; a link stored before links carried text showed nothing.
                text = part.path("data").path("text").asString("");
                if (text.isBlank()) {
                    continue;
                }
            } else {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(text);
        }
        return out.toString();
    }
}
