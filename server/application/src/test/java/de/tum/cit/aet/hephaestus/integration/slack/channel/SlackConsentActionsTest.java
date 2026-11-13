package de.tum.cit.aet.hephaestus.integration.slack.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class SlackConsentActionsTest extends BaseUnitTest {
    @Mock
    private AgentActions actions;

    @Mock
    private SlackChannelConsentEventRepository events;

    @Test
    void readbackIncludesAProviderPostWhenTheServerClockRunsAhead() {
        var mapper = JsonMapper.builder().build();
        var channel = new SlackMonitoredChannel();
        channel.setId(11L);
        channel.setWorkspaceId(7L);
        channel.setSlackTeamId("T1");
        channel.setSlackChannelId("C1");
        channel.setConsentAnnouncementId(UUID.randomUUID());
        var request = new SlackChannelConsentEvent(7L, "C1", ConsentState.FAILED, ConsentState.ACTIVATING, null, null);
        ReflectionTestUtils.setField(request, "id", 42L);
        when(events.findLatestSetupRevision(7L, "C1")).thenReturn(10L);
        when(events.findFirstActivationTime(7L, "C1", 10L)).thenReturn(Instant.parse("2026-11-13T12:02:00Z"));

        new SlackConsentActions(actions, events, mapper).enqueue(channel, request, true);

        ArgumentCaptor<ActionRequest> captured = ArgumentCaptor.captor();
        verify(actions).enqueue(captured.capture());
        var payload = mapper.treeToValue(captured.getValue().payload(), SlackConsentAnnouncement.class);
        String providerPost = SlackTs.ofInstant(Instant.parse("2026-11-13T12:00:01Z"));
        assertThat(SlackTs.compare(providerPost, payload.oldestTs())).isPositive();
        assertThat(payload.marker()).isEqualTo(channel.getConsentAnnouncementId());
    }
}
