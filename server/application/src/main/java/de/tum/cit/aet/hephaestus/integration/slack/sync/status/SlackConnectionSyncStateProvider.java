package de.tum.cit.aet.hephaestus.integration.slack.sync.status;

import de.tum.cit.aet.hephaestus.integration.core.framework.CronSchedules;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncDetails;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceCount;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.slack.sync.SlackSyncProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read-only Slack sync state built without vendor API calls. */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackConnectionSyncStateProvider implements ConnectionSyncStateProvider {

    private final SlackMonitoredChannelRepository monitoredChannelRepository;
    private final SlackMessageRepository messageRepository;
    private final SlackSyncProperties properties;
    private final SlackRateLimitTracker rateLimitTracker;

    public SlackConnectionSyncStateProvider(
            SlackMonitoredChannelRepository monitoredChannelRepository,
            SlackMessageRepository messageRepository,
            SlackSyncProperties properties,
            SlackRateLimitTracker rateLimitTracker) {
        this.monitoredChannelRepository = monitoredChannelRepository;
        this.messageRepository = messageRepository;
        this.properties = properties;
        this.rateLimitTracker = rateLimitTracker;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.SLACK;
    }

    @Override
    public ConnectionSyncDetails describe(IntegrationRef ref, long connectionId) {
        // webhookRegistered is null — "not tracked" — and not a derived TRUE. Every other integration
        // reports an observation: GitHub the App installation (null for a PAT, which has no webhook of
        // ours), GitLab the stored `gitlabWebhookId`, Outline the stored subscription id. Slack has no
        // equivalent fact to report: Events API subscriptions are declared once in the app manifest at
        // the *app* level, not created per installation, so no per-connection subscription id exists —
        // `SlackConfig` stores none, and there is no per-workspace API to ask. Reading TRUE off the
        // connection merely being ACTIVE restated "the install exists" in a column the admin reads as
        // "deliveries are wired up", which is a different claim and one we cannot make.
        Boolean webhookRegistered = null;

        // Slack reports no budget, ever — no remaining/limit headers exist, and its per-method tiers are
        // published as floors ("50+ per minute"), not quotas. The only rate-limit fact Slack can state is
        // "I threw a 429 and asked you to wait until T", so that is the only thing this snapshot carries;
        // it is null until Slack has actually done so. Inventing a gauge from the tier tables would be a
        // fabrication — see SlackRateLimitTracker.
        RateLimitSnapshot rateLimit = rateLimitTracker.snapshot(ref.workspaceId());

        return new ConnectionSyncDetails(
                webhookRegistered,
                CronSchedules.nextRun(properties.cron()),
                CronSchedules.interval(properties.cron()),
                rateLimit,
                null,
                false);
    }

    @Override
    public List<SyncResourceState> resources(IntegrationRef ref, long connectionId) {
        long workspaceId = ref.workspaceId();
        Map<String, Long> itemCountByChannelId = messageRepository.countGroupedByChannelId(workspaceId).stream()
                .collect(Collectors.toMap(
                        SlackMessageRepository.ChannelItemCount::getSlackChannelId,
                        SlackMessageRepository.ChannelItemCount::getItemCount));

        return monitoredChannelRepository
                .findByWorkspaceIdAndConsentStateNot(workspaceId, ConsentState.REVOKED)
                .stream()
                .map(channel -> toResourceState(channel, itemCountByChannelId))
                .toList();
    }

    private SyncResourceState toResourceState(SlackMonitoredChannel channel, Map<String, Long> itemCountByChannelId) {
        Long itemCount = itemCountByChannelId.getOrDefault(channel.getSlackChannelId(), 0L);
        String name = channel.getChannelName() != null ? channel.getChannelName() : channel.getSlackChannelId();
        Instant lastSyncedAt = toInstant(channel.getLastHistorySyncedTs());
        return new SyncResourceState(
                channel.getId(),
                channel.getSlackChannelId(),
                name,
                SyncResourceState.Type.CHANNEL,
                channel.getConsentState().name(),
                lastSyncedAt,
                itemCount,
                // A channel maps to exactly one entity class, so the breakdown is a single row rendered inline
                // (no expander); its watermark is the channel's own, not a stand-in for a missing per-class one.
                List.of(new SyncResourceCount(SyncResourceCount.KEY_MESSAGES, "Messages", itemCount, lastSyncedAt)),
                null,
                channel.getLastSyncError(),
                null,
                null);
    }

    private static @Nullable Instant toInstant(@Nullable String slackTs) {
        Long micros = SlackTs.toEpochMicros(slackTs);
        if (micros == null) {
            return null;
        }
        return Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L);
    }
}
