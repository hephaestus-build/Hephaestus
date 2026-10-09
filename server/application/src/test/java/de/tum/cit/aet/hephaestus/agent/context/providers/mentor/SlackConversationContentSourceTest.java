package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadProjection;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SlackConversationContentSourceTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ConversationThreadProjection projection;

    @Test
    void supportsOnlyHephTurnRequestsAndIsOptional() {
        SlackConversationContentSource source = new SlackConversationContentSource(projection, objectMapper);

        assertThat(source.required()).isFalse();
        assertThat(source.supports(new ContextRequest.HephTurnRequest(1L, 2L, UUID.randomUUID())))
                .isTrue();
        assertThat(source.supports(new ContextRequest.PracticeReviewRequest(new AgentJob())))
                .isFalse();
    }

    @Test
    void contributesProjectedDeveloperConversationPayload() throws Exception {
        SlackConversationContentSource source = new SlackConversationContentSource(projection, objectMapper);
        when(projection.buildPayload(11L, 22L))
                .thenReturn(objectMapper.createObjectNode().put("source", "slack"));

        Map<String, byte[]> files = new HashMap<>();
        source.contribute(new ContextRequest.HephTurnRequest(11L, 22L, UUID.randomUUID()), files);

        assertThat(files).containsOnlyKeys(SlackConversationContentSource.OUTPUT_KEY);
        JsonNode payload = objectMapper.readTree(files.get(SlackConversationContentSource.OUTPUT_KEY));
        assertThat(payload.path("source").asString()).isEqualTo("slack");
        verify(projection).buildPayload(11L, 22L);
    }
}
