package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.Outgoing;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.Posted;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import java.util.function.BooleanSupplier;
import org.mockito.stubbing.Answer;

/** Runs a mocked Slack transport only after the real outbox has admitted its write. */
public final class SlackPostTestSupport {
    private SlackPostTestSupport() {}

    public static Answer<Posted> admittedPost(Answer<Posted> send) {
        return invocation -> {
            BooleanSupplier admit = invocation.getArgument(2);
            if (!admit.getAsBoolean()) {
                Outgoing post = invocation.getArgument(1);
                throw new SlackSendException(invocation.getArgument(0), post.channelId(), "not_admitted");
            }
            return send.answer(invocation);
        };
    }
}
