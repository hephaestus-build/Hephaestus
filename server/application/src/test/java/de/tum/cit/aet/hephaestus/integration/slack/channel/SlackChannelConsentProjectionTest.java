package de.tum.cit.aet.hephaestus.integration.slack.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService.SlackChannelConsentChangedEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackChannelMessagePublishGate;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.KeyValue;
import io.nats.client.KeyValueManagement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * The bucket the receiver reads holds exactly the {@code ACTIVE} channels of active Slack connections: a consent
 * change updates its key, and a rebuild adds what is missing and removes what is no longer active.
 */
class SlackChannelConsentProjectionTest extends BaseUnitTest {

    private static final long WORKSPACE = 42L;

    @Mock
    private io.nats.client.Connection nats;

    @Mock
    private KeyValueManagement management;

    @Mock
    private KeyValue bucket;

    @Mock
    private SlackMonitoredChannelRepository channels;

    @Mock
    private ConnectionService connections;

    @Mock
    private Connection slack;

    private SlackChannelConsentProjection projection;

    @BeforeEach
    void setUp() throws Exception {
        when(nats.keyValueManagement()).thenReturn(management);
        when(management.getBucketNames()).thenReturn(List.of(SlackChannelMessagePublishGate.CONSENT_BUCKET));
        when(nats.keyValue(SlackChannelMessagePublishGate.CONSENT_BUCKET)).thenReturn(bucket);
        when(slack.getInstanceKey()).thenReturn("T1");
        when(connections.findActive(WORKSPACE, IntegrationKind.SLACK)).thenReturn(Optional.of(slack));
        projection = new SlackChannelConsentProjection(nats, channels, connections);
    }

    @Test
    void shouldPutTheKeyWhenAChannelBecomesActive() throws Exception {
        when(channels.findConsentState(WORKSPACE, "C1")).thenReturn(Optional.of(ConsentState.ACTIVE));

        projection.onConsentChanged(new SlackChannelConsentChangedEvent(WORKSPACE, "C1"));

        verify(bucket).put(eq("T1.C1"), any(byte[].class));
    }

    @Test
    void shouldDeleteTheKeyWhenAChannelIsPaused() throws Exception {
        when(channels.findConsentState(WORKSPACE, "C1")).thenReturn(Optional.of(ConsentState.PAUSED));

        projection.onConsentChanged(new SlackChannelConsentChangedEvent(WORKSPACE, "C1"));

        verify(bucket).delete("T1.C1");
    }

    @Test
    void shouldAddMissingAndRemoveStaleKeysWhenItRebuilds() throws Exception {
        when(channels.findDistinctWorkspaceIdsByConsentState("ACTIVE")).thenReturn(List.of(WORKSPACE));
        when(channels.findChannelIds(WORKSPACE, ConsentState.ACTIVE)).thenReturn(List.of("C1", "C2"));
        when(bucket.keys()).thenReturn(List.of("T1.C2", "T1.CERASED", "T9.CPURGED"));

        projection.rebuild();

        verify(bucket).put(eq("T1.C1"), any(byte[].class));
        verify(bucket, never()).put(eq("T1.C2"), any(byte[].class));
        verify(bucket).delete("T1.CERASED");
        verify(bucket).delete("T9.CPURGED");
    }
}
