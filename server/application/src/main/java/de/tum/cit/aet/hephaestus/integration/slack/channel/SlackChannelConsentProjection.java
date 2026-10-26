package de.tum.cit.aet.hephaestus.integration.slack.channel;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService.SlackChannelConsentChangedEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackChannelMessagePublishGate;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.KeyValueManagement;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.StorageType;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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
 * the receiver needs no database. A key is {@code <teamId>.<channelId>} and exists only while the channel is
 * {@code ACTIVE}; the bucket holds Slack ids and nothing else.
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

    private final io.nats.client.Connection nats;
    private final SlackMonitoredChannelRepository channels;
    private final ConnectionService connections;

    /** Guarded by {@code this}. */
    private @Nullable KeyValue bucket;

    public SlackChannelConsentProjection(
            io.nats.client.Connection nats, SlackMonitoredChannelRepository channels, ConnectionService connections) {
        this.nats = nats;
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
            KeyValue kv = bucket();
            if (active) {
                kv.put(key, ConsentState.ACTIVE.name());
            } else {
                kv.delete(key);
            }
        } catch (IOException | JetStreamApiException e) {
            log.warn("Slack consent projection update failed; the next rebuild repairs it: {}", e.toString());
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
            KeyValue kv = bucket();
            List<String> stored = kv.keys();
            for (String key : stored) {
                if (!active.contains(key)) {
                    kv.delete(key);
                }
            }
            for (String key : active) {
                if (!stored.contains(key)) {
                    kv.put(key, ConsentState.ACTIVE.name());
                }
            }
        } catch (IOException | JetStreamApiException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Slack consent projection rebuild failed: {}", e.toString());
        }
    }

    private Optional<String> teamId(long workspaceId) {
        return connections
                .findActive(workspaceId, IntegrationKind.SLACK)
                .map(Connection::getInstanceKey)
                .filter(SlackChannelMessagePublishGate::isSlackId);
    }

    private KeyValue bucket() throws IOException, JetStreamApiException {
        if (bucket == null) {
            KeyValueManagement management = nats.keyValueManagement();
            if (!management.getBucketNames().contains(SlackChannelMessagePublishGate.CONSENT_BUCKET)) {
                management.create(KeyValueConfiguration.builder()
                        .name(SlackChannelMessagePublishGate.CONSENT_BUCKET)
                        .storageType(StorageType.File)
                        .maxHistoryPerKey(1)
                        .build());
            }
            bucket = nats.keyValue(SlackChannelMessagePublishGate.CONSENT_BUCKET);
        }
        return bucket;
    }
}
