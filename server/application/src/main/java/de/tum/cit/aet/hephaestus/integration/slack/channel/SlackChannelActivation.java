package de.tum.cit.aet.hephaestus.integration.slack.channel;

import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService.SlackChannelActivatedEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles a consent announcement against the channel's consent state. It reads and writes the database only, so
 * the announcement adapter can settle a row even when Slack is disabled.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class SlackChannelActivation {

    private final SlackMonitoredChannelRepository channels;
    private final SlackChannelConsentEventRepository events;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public boolean isCurrent(long workspaceId, SlackConsentAnnouncement announcement) {
        return channels.findByWorkspaceIdAndSlackChannelId(workspaceId, announcement.channelId())
                .filter(channel -> sameActivation(workspaceId, channel, announcement))
                .isPresent();
    }

    private boolean sameActivation(
            long workspaceId, SlackMonitoredChannel channel, SlackConsentAnnouncement announcement) {
        return channel.getId() == announcement.channelRowId()
                && channel.getSlackTeamId().equals(announcement.teamId())
                && channel.getConsentState() == ConsentState.ACTIVATING
                && Objects.equals(channel.getConsentAnnouncementId(), announcement.marker())
                && Objects.equals(
                        events.findLatestActivationRevision(workspaceId, announcement.channelId()),
                        announcement.requestId());
    }

    /** Only the fenced action's settlement confirms activation or records a retryable domain failure. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ended(long workspaceId, long requestId, ActionState outcome, @Nullable Long postedMicros) {
        var request = events.findActivationRequest(workspaceId, requestId);
        if (request.isEmpty()) return;
        String channelId = request.get().getSlackChannelId();
        var found = channels.findForUpdate(workspaceId, channelId);
        if (found.isEmpty()
                || found.get().getConsentState() != ConsentState.ACTIVATING
                || !Objects.equals(events.findLatestActivationRevision(workspaceId, channelId), requestId)) return;
        var channel = found.get();
        ConsentState next = outcome == ActionState.DONE ? ConsentState.ACTIVE : ConsentState.FAILED;
        if (next == ConsentState.ACTIVE) {
            long micros = Objects.requireNonNull(postedMicros);
            Instant postedAt = Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L);
            channel.setConsentAnnouncedAt(postedAt);
            // An earlier post confirms disclosure, not permission to backfill a failed activation's gap.
            Instant requestedAt = Objects.requireNonNull(request.get().getCreatedAt());
            Instant ingestAfter = request.get().getFromState() == ConsentState.FAILED && requestedAt.isAfter(postedAt)
                    ? requestedAt
                    : postedAt;
            channel.setIngestAfter(ingestAfter);
            channel.setLastHistorySyncedTs(SlackTs.ofInstant(ingestAfter));
        }
        channel.setConsentState(next);
        channels.save(channel);
        events.save(new SlackChannelConsentEvent(
                workspaceId,
                channelId,
                ConsentState.ACTIVATING,
                next,
                null,
                next == ConsentState.FAILED ? "Consent announcement was not confirmed" : null));
        if (next == ConsentState.ACTIVE)
            eventPublisher.publishEvent(new SlackChannelActivatedEvent(workspaceId, channelId));
    }
}
