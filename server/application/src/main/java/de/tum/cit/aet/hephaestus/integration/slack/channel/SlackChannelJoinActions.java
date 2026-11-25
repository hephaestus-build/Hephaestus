package de.tum.cit.aet.hephaestus.integration.slack.channel;

import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Enqueues the public-channel join in the transaction that registers the channel. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class SlackChannelJoinActions {
    private final AgentActions actions;
    private final AuthorityGenerations generations;
    private final SlackChannelConsentEventRepository events;
    private final ObjectMapper mapper;

    public static String lane(long workspaceId, String channelId) {
        return "slack_channel:" + workspaceId + ":" + channelId;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(SlackMonitoredChannel channel, long requestId) {
        long workspace = channel.getWorkspaceId();
        Long revision = events.findLatestSetupRevision(workspace, channel.getSlackChannelId());
        String key = "slack_join:" + workspace + ":" + channel.getId() + ":" + requestId;
        actions.enqueue(new ActionRequest(
                ActionKind.SLACK_JOIN,
                // A channel's own action, queued with the consent change that asks for it, checks no access here.
                generations.requestedNow(workspace),
                lane(workspace, channel.getSlackChannelId()),
                key,
                mapper.valueToTree(new SlackChannelJoin(
                        channel.getSlackChannelId(),
                        channel.getSlackTeamId(),
                        channel.getId(),
                        revision == null ? 0L : revision)),
                null,
                3,
                null,
                null));
    }
}
