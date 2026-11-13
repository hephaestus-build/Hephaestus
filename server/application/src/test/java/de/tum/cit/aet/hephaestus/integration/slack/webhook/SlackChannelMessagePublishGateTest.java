package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class SlackChannelMessagePublishGateTest extends BaseUnitTest {

    @Mock
    private ConnectionService connections;

    @Mock
    private SlackMonitoredChannelRepository channels;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CHANNEL_MESSAGE =
            "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}";

    @Test
    void dropsChannelMessageWhenChannelIsNotActive() throws Exception {
        connectedTeam();
        when(channels.findConsentState(7L, "C1")).thenReturn(Optional.of(ConsentState.PAUSED));

        var decision = gate(true).evaluate(objectMapper.readTree(CHANNEL_MESSAGE), Map.of());

        assertThat(decision.publish()).isFalse();
        assertThat(decision.reason()).isEqualTo("slack-channel-not-active");
    }

    @Test
    void failsTheDeliveryWhenConsentCannotBeRead() throws Exception {
        when(connections.findWorkspaceIdsWithConnection(IntegrationKind.SLACK, IntegrationState.ACTIVE))
                .thenThrow(new CannotGetJdbcConnectionException("database down"));

        // Nothing is stored; Slack retries the delivery.
        assertThatThrownBy(() -> gate(true).evaluate(objectMapper.readTree(CHANNEL_MESSAGE), Map.of()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void dropsChannelMessageWhoseIdsAreNotSlackIdsWithoutALookup() throws Exception {
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1.>\"}}");

        var decision = gate(true).evaluate(payload, Map.of());

        assertThat(decision.publish()).isFalse();
        assertThat(decision.reason()).isEqualTo("slack-channel-message-malformed-id");
        verifyNoInteractions(connections, channels);
    }

    @Test
    void allowsMessageDeletedThroughRegardlessOfConsentState() throws Exception {
        // The delete must reach the tombstone in every consent state (PAUSED/REVOKED included), so the gate never
        // even reads consent for this subtype.
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"subtype\":\"message_deleted\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}");

        var decision = gate(true).evaluate(payload, Map.of());

        assertThat(decision.publish()).isTrue();
        verifyNoInteractions(connections, channels);
    }

    @Test
    void allowsActiveChannelMessage() throws Exception {
        connectedTeam();
        when(channels.findConsentState(7L, "G1")).thenReturn(Optional.of(ConsentState.ACTIVE));
        JsonNode payload = objectMapper.readTree(
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"group\",\"channel\":\"G1\"}}");

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
        var decision = gate(false).evaluate(objectMapper.readTree(CHANNEL_MESSAGE), Map.of());

        assertThat(decision.publish()).isFalse();
        assertThat(decision.reason()).isEqualTo("slack-channel-ingest-disabled");
    }

    private void connectedTeam() {
        when(connections.findWorkspaceIdsWithConnection(IntegrationKind.SLACK, IntegrationState.ACTIVE))
                .thenReturn(List.of(7L));
        when(connections.findSlackConfig(7L))
                .thenReturn(Optional.of(new ConnectionConfig.SlackConfig("T1", null, null, Set.of())));
    }

    private SlackChannelMessagePublishGate gate(boolean conversationIngestEnabled) {
        return new SlackChannelMessagePublishGate(connections, channels, conversationIngestEnabled);
    }
}
