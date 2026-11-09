package de.tum.cit.aet.hephaestus.integration.slack.channel;

import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.core.lock.ClusterLocks;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService.SlackChannelConsentChangedEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Connection;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** While another server updates or rebuilds the projection, this one leaves the bucket to the next rebuild. */
class SlackChannelConsentProjectionLockTest extends BaseUnitTest {

    @Mock
    private Connection nats;

    @Mock
    private SlackMonitoredChannelRepository channels;

    @Mock
    private ConnectionService connections;

    @Test
    void shouldNotTouchTheBucketWhileAnotherServerHoldsTheProjection() {
        var projection = new SlackChannelConsentProjection(nats, channels, connections, ClusterLocks.held());

        projection.onConsentChanged(new SlackChannelConsentChangedEvent(42L, "C1"));
        projection.rebuild();

        verifyNoInteractions(nats, channels, connections);
    }
}
