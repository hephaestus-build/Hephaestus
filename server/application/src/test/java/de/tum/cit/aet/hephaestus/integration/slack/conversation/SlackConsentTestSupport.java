package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnCompletion;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackMessages;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackReplies;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.integration.core.connection.ModelLinkHosts;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelActivation;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.Map;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

/** Builds the real consent adapter around the lifecycle fixture's Slack client and domain service. */
public final class SlackConsentTestSupport {
    private SlackConsentTestSupport() {}

    public static ActionAdapter adapter(
            SlackMessageService client, SlackChannelActivation activation, ObjectMapper mapper) {
        var beans = new StaticListableBeanFactory(Map.of("slack", client));
        var posts = new SlackPostAdapter(
                beans.getBeanProvider(SlackMessageService.class),
                beans.getBeanProvider(SlackWeeklyDigestPointer.class),
                mock(ConversationSlackReplies.class),
                mock(ConversationSlackMessages.class),
                mock(ConversationTurnCompletion.class),
                mock(ModelLinkHosts.class),
                mapper,
                mock(WorkspaceSummaryQuery.class),
                new OutboundEgressGuard(() -> false));
        return new SlackConsentAdapter(posts, activation, beans.getBeanProvider(SlackMessageService.class), mapper);
    }
}
