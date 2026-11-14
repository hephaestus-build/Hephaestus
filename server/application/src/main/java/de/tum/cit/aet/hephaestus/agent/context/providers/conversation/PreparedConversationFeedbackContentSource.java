package de.tum.cit.aet.hephaestus.agent.context.providers.conversation;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.ConversationContextRequest;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * At most three pieces of READY conversation feedback that Hephaestus may show in this conversation, and how many wait
 * ({@code waiting}, among the developer's twelve latest pieces). A piece whose
 * conversation copy is already bound to a conversation shows only there: {@code threadId} is that conversation
 * conversation's id, the main conversation or one Hephaestus thread, and the request's {@code threadId} is the turn's.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PreparedConversationFeedbackContentSource implements ContentSource {
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "prepared_conversation_feedback.json";
    private final FeedbackHistoryQuery feedback;
    private final HistoryCopies copies;
    private final ConversationConsentGate consentGate;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ConversationContextRequest request) {
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ConversationContextRequest request, Map<String, byte[]> files) {
        var root = objectMapper.createObjectNode().put("readAt", Instant.now().toString());
        consentGate.writeUntrustedEnvelope(root);
        var rows = root.putArray("preparedConversationFeedback");
        int waiting = 0;
        for (var entry : feedback.forDeveloper(
                request.workspaceId(), request.developerId(), Instant.now().minus(90, ChronoUnit.DAYS), 12)) {
            if (entry.destination() != FeedbackDestination.CONVERSATION
                    || entry.state() != FeedbackState.READY
                    || (entry.threadId() != null && !request.threadId().equals(entry.threadId()))
                    || entry.body() == null
                    || !copies.record(copies.of(entry))) continue;
            waiting++;
            if (rows.size() == 3) continue;
            var row = rows.addObject()
                    .put("feedbackId", entry.feedbackId().toString())
                    .put("body", entry.body());
            row.set("practiceSlugs", objectMapper.valueToTree(entry.practiceSlugs()));
            row.set("resultIds", objectMapper.valueToTree(entry.resultIds()));
            row.set("problemIds", objectMapper.valueToTree(entry.problemIds()));
        }
        root.put("waiting", waiting);
        files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(root));
    }
}
