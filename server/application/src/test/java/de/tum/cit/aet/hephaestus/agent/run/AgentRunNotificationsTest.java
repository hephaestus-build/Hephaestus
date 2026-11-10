package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AgentRunNotificationsTest extends BaseUnitTest {

    @Test
    void shouldStillNotifyEveryOtherSubscriberWhenOneSubscriberThrows() {
        List<AgentRunKind> heard = new ArrayList<>();
        List<Consumer<AgentRunKind>> subscribers = List.of(
                kind -> {
                    throw new IllegalStateException("a failing subscriber");
                },
                heard::add);

        AgentRunNotifications.publish(subscribers, AgentRunKind.REVIEW);

        assertThat(heard).containsExactly(AgentRunKind.REVIEW);
    }
}
