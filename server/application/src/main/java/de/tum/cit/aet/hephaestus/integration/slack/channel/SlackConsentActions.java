package de.tum.cit.aet.hephaestus.integration.slack.channel;

import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Enqueues the announcement with its activation audit record, in the same transaction. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class SlackConsentActions {
    // Slack timestamps and activation audit timestamps come from different clocks.
    private static final Duration READBACK_CLOCK_MARGIN = Duration.ofMinutes(15);

    private final AgentActions actions;
    private final SlackChannelConsentEventRepository events;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(SlackMonitoredChannel channel, SlackChannelConsentEvent request, boolean retry) {
        long workspace = channel.getWorkspaceId();
        long requestId = Objects.requireNonNull(request.getId());
        Long latestSetup = events.findLatestSetupRevision(workspace, channel.getSlackChannelId());
        long setup = latestSetup == null ? 0L : latestSetup;
        UUID marker = Objects.requireNonNull(channel.getConsentAnnouncementId());
        var firstActivation =
                Objects.requireNonNull(events.findFirstActivationTime(workspace, channel.getSlackChannelId(), setup));
        var payload = new SlackConsentAnnouncement(
                channel.getSlackChannelId(),
                channel.getSlackTeamId(),
                channel.getId(),
                requestId,
                marker,
                SlackTs.ofInstant(firstActivation.minus(READBACK_CLOCK_MARGIN)),
                retry);
        actions.enqueue(new ActionRequest(
                ActionKind.SLACK_CONSENT,
                workspace,
                SlackChannelJoinActions.lane(workspace, channel.getSlackChannelId()),
                SlackConsentAnnouncement.idempotencyKey(workspace, requestId),
                mapper.valueToTree(payload),
                null,
                3,
                null,
                null));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(long workspaceId, String channelId) {
        actions.cancelPending(workspaceId, SlackChannelJoinActions.lane(workspaceId, channelId));
    }
}
