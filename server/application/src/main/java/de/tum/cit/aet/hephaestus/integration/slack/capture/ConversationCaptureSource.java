package de.tum.cit.aet.hephaestus.integration.slack.capture;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.chat.item.ConversationThreadProjection;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Slack threads the developer took part in, for a Heph turn. The server builds the context of a turn, so this
 * source exists wherever capture runs. {@code SlackConversationProjector} bounds the document.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class ConversationCaptureSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "slack_conversations.json";

    private final ConversationThreadProjection projection;
    private final ObjectMapper objectMapper;

    public ConversationCaptureSource(ConversationThreadProjection projection, ObjectMapper objectMapper) {
        this.projection = projection;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MentorContextRequest request) {
        return true;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
        ObjectNode payload = projection.buildPayload(request.workspaceId(), request.developerId());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize Slack conversations context", e);
        }
    }
}
