package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SlackMentorProjectorSubscriptionTest extends BaseUnitTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SlackMentorProjector.SubscriptionConfiguration.class)
            .withBean(SlackMentorProjector.class, () -> mock(SlackMentorProjector.class))
            .withPropertyValues("hephaestus.integration.slack.enabled=true");

    @Test
    void shouldSubscribeTheProjectorOnAServerWithSlack() {
        runner.withPropertyValues("spring.profiles.active=default")
                .run(context -> assertThat(context).hasBean("slackMentorProjectorSubscription"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "spring.profiles.active=test",
                "hephaestus.integration.slack.enabled=false",
                "hephaestus.runtime.server.enabled=false"
            })
    void shouldSubscribeNoProjectorInTestsWithoutSlackOrWithoutTheServerRole(String setting) {
        runner.withPropertyValues("spring.profiles.active=default", setting)
                .run(context -> assertThat(context).doesNotHaveBean("slackMentorProjectorSubscription"));
    }
}
