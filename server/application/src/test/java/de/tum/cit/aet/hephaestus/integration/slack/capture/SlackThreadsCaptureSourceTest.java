package de.tum.cit.aet.hephaestus.integration.slack.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.capture.ConversationContextRequest;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadProjection;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SlackThreadsCaptureSourceTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ThreadProjection projection;

    @Test
    void contributesProjectedDeveloperConversationPayload() throws Exception {
        SlackThreadsCaptureSource source = new SlackThreadsCaptureSource(projection, objectMapper);
        when(projection.buildPayload(11L, 22L))
                .thenReturn(objectMapper.createObjectNode().put("source", "slack"));

        Map<String, byte[]> files = new HashMap<>();
        source.contribute(new ConversationContextRequest(11L, 22L, UUID.randomUUID(), null), files);

        assertThat(files).containsOnlyKeys(SlackThreadsCaptureSource.OUTPUT_KEY);
        JsonNode payload =
                objectMapper.readTree(Objects.requireNonNull(files.get(SlackThreadsCaptureSource.OUTPUT_KEY)));
        assertThat(payload.path("source").asString()).isEqualTo("slack");
        verify(projection).buildPayload(11L, 22L);
    }
}
