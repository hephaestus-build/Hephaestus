package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.integration.core.connection.ModelLinkHosts;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelActivation;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackMessages;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackReplies;
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
                mock(MentorSlackReplies.class),
                mock(MentorSlackMessages.class),
                mock(MentorTurnCompletion.class),
                mock(ModelLinkHosts.class),
                mapper);
        return new SlackConsentAdapter(posts, activation, beans.getBeanProvider(SlackMessageService.class), mapper);
    }
}
