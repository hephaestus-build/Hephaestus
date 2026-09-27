package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CurrentThreadHistoryContentSourceTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    ChatMessageRepository chatMessageRepository;

    @Test
    void contributesCurrentThreadMessagesInOrder() throws Exception {
        UUID threadId = UUID.randomUUID();
        ChatMessage user =
                message(ChatMessage.Role.USER, "What was my first message?", Instant.parse("2026-01-01T00:00:00Z"));
        ChatMessage assistant = message(
                ChatMessage.Role.ASSISTANT, "You first asked about PR #12.", Instant.parse("2026-01-01T00:00:02Z"));
        when(chatMessageRepository.findContextMessages(1L, 2L, threadId, null)).thenReturn(List.of(user, assistant));

        CurrentThreadHistoryContentSource source =
                new CurrentThreadHistoryContentSource(chatMessageRepository, objectMapper);
        Map<String, byte[]> files = new HashMap<>();
        source.contribute(new ContextRequest.MentorChatRequest(1L, 2L, threadId), files);

        JsonNode root = objectMapper.readTree(files.get(CurrentThreadHistoryContentSource.OUTPUT_KEY));
        assertThat(root.get("threadId").asString()).isEqualTo(threadId.toString());
        JsonNode messages = root.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role").asString()).isEqualTo("USER");
        assertThat(messages.get(0).get("text").asString()).isEqualTo("What was my first message?");
        assertThat(messages.get(1).get("role").asString()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).get("text").asString()).isEqualTo("You first asked about PR #12.");
    }

    @Test
    @DisplayName("omits legacy assistant text parts that are leaked internal analysis")
    void omitsLeakedInternalAnalysisFromAssistantHistory() throws Exception {
        UUID threadId = UUID.randomUUID();
        ChatMessage assistant = message(
                ChatMessage.Role.ASSISTANT,
                List.of(
                        "User wants to see Slack messages in context. We need to fetch it.",
                        "I see two recent Slack threads in the data."),
                Instant.parse("2026-01-01T00:00:00Z"));
        when(chatMessageRepository.findContextMessages(1L, 2L, threadId, null)).thenReturn(List.of(assistant));

        CurrentThreadHistoryContentSource source =
                new CurrentThreadHistoryContentSource(chatMessageRepository, objectMapper);
        Map<String, byte[]> files = new HashMap<>();
        source.contribute(new ContextRequest.MentorChatRequest(1L, 2L, threadId), files);

        JsonNode messages = objectMapper
                .readTree(files.get(CurrentThreadHistoryContentSource.OUTPUT_KEY))
                .get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("text").asString()).isEqualTo("I see two recent Slack threads in the data.");
    }

    @Test
    void remembersTheFeedbackAReplyShowedAndNothingForAnObservationItOnlyNamed() throws Exception {
        UUID threadId = UUID.randomUUID();
        ChatMessage assistant =
                message(ChatMessage.Role.ASSISTANT, "Let me look.", Instant.parse("2026-01-01T00:00:00Z"));
        var parts = (tools.jackson.databind.node.ArrayNode) assistant.getParts();
        parts.addObject()
                .put("type", "data-observation")
                .put("id", "named")
                .putObject("data")
                .put("observationId", UUID.randomUUID().toString());
        parts.addObject()
                .put("type", "data-observation")
                .put("id", "shown")
                .putObject("data")
                .put("observationId", UUID.randomUUID().toString())
                .put("text", "Name the trade-off in the description.");
        parts.addObject().put("type", "text").put("text", "What made you choose it?");
        when(chatMessageRepository.findContextMessages(1L, 2L, threadId, null)).thenReturn(List.of(assistant));

        CurrentThreadHistoryContentSource source =
                new CurrentThreadHistoryContentSource(chatMessageRepository, objectMapper);
        Map<String, byte[]> files = new HashMap<>();
        source.contribute(new ContextRequest.MentorChatRequest(1L, 2L, threadId), files);

        assertThat(objectMapper
                        .readTree(files.get(CurrentThreadHistoryContentSource.OUTPUT_KEY))
                        .at("/messages/0/text")
                        .asString())
                .isEqualTo("Let me look.\nName the trade-off in the description.\nWhat made you choose it?");
    }

    private ChatMessage message(ChatMessage.Role role, String text, Instant createdAt) {
        return message(role, List.of(text), createdAt);
    }

    private ChatMessage message(ChatMessage.Role role, List<String> texts, Instant createdAt) {
        ChatMessage message = new ChatMessage();
        message.setId(UUID.randomUUID());
        message.setRole(role);
        message.setStatus(ChatMessage.Status.completed);
        message.setCreatedAt(createdAt);
        var parts = objectMapper.createArrayNode();
        for (String text : texts) {
            parts.addObject().put("type", "text").put("text", text);
        }
        message.setParts(parts);
        return message;
    }
}
