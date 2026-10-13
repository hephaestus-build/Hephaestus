package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.HephTurnRequest;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadProjection;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class SlackConversationContentSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "slack_conversations.json";

    private final ConversationThreadProjection projection;
    private final ObjectMapper objectMapper;

    public SlackConversationContentSource(ConversationThreadProjection projection, ObjectMapper objectMapper) {
        this.projection = projection;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof HephTurnRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        HephTurnRequest req = (HephTurnRequest) request;
        ObjectNode payload = projection.buildPayload(req.workspaceId(), req.developerId());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize Slack conversations context", e);
        }
    }
}
