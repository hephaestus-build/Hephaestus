package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelConsentGate;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class SlackChannelMessagePublishGateTest extends BaseUnitTest {

    @Mock
    private SlackWorkspaceResolver workspaceResolver;

    @Mock
    private SlackChannelConsentGate consentGate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dropsChannelMessageWhenChannelIsNotActive() throws Exception {
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}");
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(42L));
        when(consentGate.ingestAllowed(42L, "C1")).thenReturn(false);

        var decision = gate(true).evaluate(payload, Map.of());

        assertThat(decision.publish()).isFalse();
        assertThat(decision.reason()).isEqualTo("slack-channel-not-active");
    }

    @Test
    void dropsChannelMessageWhenWorkspaceIsUnknown() throws Exception {
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T-UNKNOWN\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}");
        when(workspaceResolver.resolveWorkspaceId("T-UNKNOWN")).thenReturn(Optional.empty());

        var decision = gate(true).evaluate(payload, Map.of());

        assertThat(decision.publish()).isFalse();
        assertThat(decision.reason()).isEqualTo("slack-channel-not-active");
        verifyNoInteractions(consentGate);
    }

    @Test
    void allowsMessageDeletedThroughRegardlessOfConsentState() throws Exception {
        // The delete must reach the tombstone in every consent state (PAUSED/REVOKED included), so the gate never
        // even resolves the workspace or consults the consent gate for this subtype.
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"subtype\":\"message_deleted\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}");

        var decision = gate(true).evaluate(payload, Map.of());

        assertThat(decision.publish()).isTrue();
        verifyNoInteractions(workspaceResolver, consentGate);
    }

    @Test
    void allowsActiveChannelMessage() throws Exception {
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"group\",\"channel\":\"G1\"}}");
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(42L));
        when(consentGate.ingestAllowed(42L, "G1")).thenReturn(true);

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

    private SlackChannelMessagePublishGate gate(boolean conversationIngestEnabled) {
        return new SlackChannelMessagePublishGate(workspaceResolver, consentGate, conversationIngestEnabled);
    }
}
