package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.Posted;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.mockito.stubbing.Answer;

/** Runs a mocked Slack transport only after the real outbox has admitted its write. */
public final class SlackPostTestSupport {
    private SlackPostTestSupport() {}

    public static Answer<@Nullable Posted> admittedPost(Answer<Posted> send) {
        return invocation -> {
            BooleanSupplier admit = invocation.getArgument(5);
            return admit.getAsBoolean() ? send.answer(invocation) : null;
        };
    }
}
