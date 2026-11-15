package de.tum.cit.aet.hephaestus.integration.slack.channel;

import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.action.ActionVariants;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import java.time.Duration;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Public-channel membership is a fenced, durable effect; unknown writes read membership before any retry. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class SlackJoinAdapter implements ActionAdapter {
    private static final Duration REREAD = Duration.ofMinutes(1);
    private final ObjectProvider<SlackMessageService> clients;
    private final SlackMonitoredChannelRepository channels;
    private final SlackChannelConsentEventRepository events;
    private final ConnectionService connections;
    private final ObjectMapper mapper;

    @Override
    public ActionKind kind() {
        return ActionKind.SLACK_JOIN;
    }

    @Override
    public ActionVariants variants() {
        return ActionVariants.one(SlackChannelJoin.class);
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        long workspace = Objects.requireNonNull(attempt.workspaceId());
        var join = mapper.treeToValue(attempt.payload(), SlackChannelJoin.class);
        var client = clients.getIfAvailable();
        Long revision = events.findLatestSetupRevision(workspace, join.channelId());
        boolean current = channels.findByWorkspaceIdAndSlackChannelId(workspace, join.channelId())
                        .filter(channel -> channel.getId() == join.channelRowId()
                                && channel.getConsentState() != ConsentState.REVOKED
                                && channel.getSlackTeamId().equals(join.teamId()))
                        .isPresent()
                && (revision == null ? 0L : revision) == join.setupRevision()
                && connections
                        .findSlackConfig(workspace)
                        .filter(config -> join.teamId().equals(config.teamId()))
                        .isPresent();
        if (client == null || !current)
            return attempt.writeStarted()
                    ? new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED)
                    : new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        var lookup = client.lookupConversationDetailed(workspace, join.channelId());
        if (lookup instanceof SlackMessageService.ConversationLookup.Unavailable)
            return attempt.writeStarted() ? new ActionResult.Reread(REREAD) : new ActionResult.Retry(REREAD);
        if (!(lookup instanceof SlackMessageService.ConversationLookup.Found found)
                || found.info().archived()) return new ActionResult.Failed(ActionStopReason.RECIPIENT_UNAVAILABLE);
        if (found.info().member()) return new ActionResult.Done(join.channelId());
        if (found.info().privateChannel()) return new ActionResult.Failed(ActionStopReason.RECIPIENT_UNAVAILABLE);
        if (attempt.writeStarted()) return new ActionResult.Retry(Duration.ZERO);
        if (!attempt.fence().beginWrite()) return new ActionResult.Abandoned();
        try {
            client.joinPublicChannel(workspace, join.channelId());
            return new ActionResult.Done(join.channelId());
        } catch (SlackSendException failed) {
            if ("silent_mode_engaged".equals(failed.slackError()))
                return new ActionResult.Cancelled(ActionStopReason.SILENCED);
            if (failed.isRateLimited())
                return new ActionResult.Defer(
                        Duration.ofMillis(Math.max(REREAD.toMillis(), failed.retryAfterMillis())));
            return failed.isOutcomeUnknown()
                    ? new ActionResult.Reread(REREAD)
                    : new ActionResult.Failed(ActionStopReason.REJECTED);
        }
    }
}
