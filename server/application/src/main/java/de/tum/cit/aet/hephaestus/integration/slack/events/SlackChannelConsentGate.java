package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single authority on whether a monitored Slack channel may have content ingested — a channel flows to the
 * conversation substrate <strong>only</strong> when its {@link ConsentState} is {@code ACTIVE}. A channel with no
 * allow-list row, or one in any pre-consent / paused / revoked state, is denied (fails closed).
 *
 * <p>The consent decision lives in exactly one place: the ingest write-path ({@link SlackIngestService}) and any
 * projector/content-source that must honour consent share this gate rather than re-deriving
 * {@code state == ACTIVE} independently.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackChannelConsentGate {

    private final SlackMonitoredChannelRepository monitoredChannelRepository;

    public SlackChannelConsentGate(SlackMonitoredChannelRepository monitoredChannelRepository) {
        this.monitoredChannelRepository = monitoredChannelRepository;
    }

    /**
     * @return {@code true} iff the channel's consent is {@code ACTIVE}. Fails closed: an absent row is treated
     *     as {@code PENDING} (denied).
     */
    @Transactional(readOnly = true)
    public boolean ingestAllowed(long workspaceId, String channelId) {
        return (monitoredChannelRepository
                        .findConsentState(workspaceId, channelId)
                        .orElse(ConsentState.PENDING)
                == ConsentState.ACTIVE);
    }
}
