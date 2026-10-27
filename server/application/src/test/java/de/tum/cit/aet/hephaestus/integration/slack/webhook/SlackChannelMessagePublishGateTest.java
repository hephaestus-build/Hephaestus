package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.webhook.ProjectedKeys;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.KeyValueManagement;
import io.nats.client.api.KeyValueEntry;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class SlackChannelMessagePublishGateTest extends BaseUnitTest {

    @Mock
    private Connection nats;

    @Mock
    private KeyValue consent;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dropsChannelMessageWhenChannelIsNotActive() throws Exception {
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}");
        stubBucket();
        when(consent.get("T1.C1")).thenReturn(null);

        var decision = gate(true).evaluate(payload, Map.of());

        assertThat(decision.publish()).isFalse();
        assertThat(decision.reason()).isEqualTo("slack-channel-not-active");
    }

    @Test
    void failsTheDeliveryWhenTheConsentBucketIsMissing() throws Exception {
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}");
        JetStreamApiException streamNotFound = mock(JetStreamApiException.class);
        stubBucket();
        when(consent.get("T1.C1")).thenThrow(streamNotFound);

        // Nothing is published, and Slack retries once the server has created the bucket.
        assertThatThrownBy(() -> gate(true).evaluate(payload, Map.of()))
                .isInstanceOf(ProjectedKeys.UnavailableException.class);
    }

    @Test
    void failsTheDeliveryWhenConsentCannotBeRead() throws Exception {
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}");
        stubBucket();
        when(consent.get("T1.C1")).thenThrow(new IOException("timeout"));

        // Nothing is published; Slack retries the delivery.
        assertThatThrownBy(() -> gate(true).evaluate(payload, Map.of()))
                .isInstanceOf(ProjectedKeys.UnavailableException.class);
    }

    @Test
    void dropsChannelMessageWhoseIdsAreNotSlackIdsWithoutALookup() throws Exception {
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1.>\"}}");

        var decision = gate(true).evaluate(payload, Map.of());

        assertThat(decision.publish()).isFalse();
        assertThat(decision.reason()).isEqualTo("slack-channel-message-malformed-id");
        verifyNoInteractions(nats);
    }

    @Test
    void allowsMessageDeletedThroughRegardlessOfConsentState() throws Exception {
        // The delete must reach the tombstone in every consent state (PAUSED/REVOKED included), so the gate never
        // even reads consent for this subtype.
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"subtype\":\"message_deleted\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}");

        var decision = gate(true).evaluate(payload, Map.of());

        assertThat(decision.publish()).isTrue();
        verifyNoInteractions(nats);
    }

    @Test
    void allowsActiveChannelMessage() throws Exception {
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"group\",\"channel\":\"G1\"}}");
        stubBucket();
        when(consent.get("T1.G1")).thenReturn(mock(KeyValueEntry.class));

        var decision = gate(true).evaluate(payload, Map.of());

        assertThat(decision.publish()).isTrue();
    }

    @Test
    void leavesDmMessagesOnTheDurablePath() throws Exception {
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"im\",\"channel\":\"D1\"}}");

        var decision = gate(true).evaluate(payload, Map.of());

        assertThat(decision.publish()).isTrue();
    }

    @Test
    void dropsChannelMessagesWhenConversationIngestIsDisabled() throws Exception {
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}");

        var decision = gate(false).evaluate(payload, Map.of());

        assertThat(decision.publish()).isFalse();
        assertThat(decision.reason()).isEqualTo("slack-channel-ingest-disabled");
    }

    private void stubBucket() throws Exception {
        when(nats.keyValueManagement()).thenReturn(mock(KeyValueManagement.class));
        when(nats.keyValue(SlackChannelMessagePublishGate.CONSENT_BUCKET)).thenReturn(consent);
    }

    private SlackChannelMessagePublishGate gate(boolean conversationIngestEnabled) {
        return new SlackChannelMessagePublishGate(nats, conversationIngestEnabled);
    }
}
