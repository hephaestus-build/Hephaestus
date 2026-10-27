package de.tum.cit.aet.hephaestus.integration.slack.channel;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.webhook.ProjectedKeys;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService.SlackChannelConsentChangedEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackChannelMessagePublishGate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes which Slack channels are {@code ACTIVE} to the NATS key-value bucket the receiver's
 * {@link SlackChannelMessagePublishGate} reads, so channel content without consent is dropped before it is stored and
 * the receiver needs no database. A key is {@code <teamId>.<channelId>}, it exists only while the channel is
 * {@code ACTIVE}, and its value is empty; the bucket holds Slack ids and nothing else.
 *
 * <p>A consent change through {@link SlackChannelConsentService} updates its key after commit. A rebuild at start and
 * every minute repairs what that cannot see: an erasure, a disconnected or purged workspace, or an update lost to a
 * NATS outage. Updates and rebuilds run one at a time, so a rebuild never restores a key that a later change removed.
 * The server still checks consent when it stores a message ({@code SlackIngestService}), so a stale key never stores
 * content.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(
        name = {"hephaestus.integration.slack.enabled", "hephaestus.sync.nats.enabled"},
        havingValue = "true")
public class SlackChannelConsentProjection {

    private static final Logger log = LoggerFactory.getLogger(SlackChannelConsentProjection.class);

    private final ProjectedKeys consent;
    private final SlackMonitoredChannelRepository channels;
    private final ConnectionService connections;

    public SlackChannelConsentProjection(
            io.nats.client.Connection nats, SlackMonitoredChannelRepository channels, ConnectionService connections) {
        this.consent = new ProjectedKeys(nats, SlackChannelMessagePublishGate.CONSENT_BUCKET);
        this.channels = channels;
        this.connections = connections;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public synchronized void onConsentChanged(SlackChannelConsentChangedEvent event) {
        Optional<String> team = teamId(event.workspaceId());
        if (team.isEmpty() || !SlackChannelMessagePublishGate.isSlackId(event.slackChannelId())) {
            return;
        }
        String key = SlackChannelMessagePublishGate.consentKey(team.get(), event.slackChannelId());
        boolean active = channels.findConsentState(event.workspaceId(), event.slackChannelId())
                .filter(ConsentState.ACTIVE::equals)
                .isPresent();
        try {
            if (active) {
                consent.add(key);
            } else {
                consent.remove(key);
            }
        } catch (ProjectedKeys.UnavailableException e) {
            log.warn(
                    "Slack consent projection update failed; the next rebuild repairs it: bucket={}, cause={}",
                    e.bucket(),
                    e.causeClass());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @WorkspaceAgnostic("Projects the ACTIVE Slack channels of every workspace; each key carries its Slack team")
    public synchronized void rebuild() {
        Set<String> active = new HashSet<>();
        for (long workspaceId : channels.findDistinctWorkspaceIdsByConsentState(ConsentState.ACTIVE.name())) {
            teamId(workspaceId)
                    .ifPresent(team -> channels.findChannelIds(workspaceId, ConsentState.ACTIVE).stream()
                            .filter(SlackChannelMessagePublishGate::isSlackId)
                            .forEach(channel -> active.add(SlackChannelMessagePublishGate.consentKey(team, channel))));
        }
        try {
            consent.replaceAll(active);
        } catch (ProjectedKeys.UnavailableException e) {
            log.warn("Slack consent projection rebuild failed: bucket={}, cause={}", e.bucket(), e.causeClass());
        }
    }

    private Optional<String> teamId(long workspaceId) {
        return connections
                .findActive(workspaceId, IntegrationKind.SLACK)
                .map(Connection::getInstanceKey)
                .filter(SlackChannelMessagePublishGate::isSlackId);
    }
}
