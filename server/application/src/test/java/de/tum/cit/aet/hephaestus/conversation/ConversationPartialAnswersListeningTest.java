package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ConversationPartialAnswersListeningTest extends BaseUnitTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConversationTimeline.PartialAnswersConfiguration.class)
            .withBean(ConversationLedgerNotifications.class, () -> mock(ConversationLedgerNotifications.class));

    @Test
    void shouldListenForPartialAnswersOnAServer() {
        runner.withPropertyValues("spring.profiles.active=default")
                .run(context -> assertThat(context).hasBean("conversationPartialAnswers"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "spring.profiles.active=specs",
                "spring.profiles.active=cds-training",
                "hephaestus.runtime.server.enabled=false"
            })
    void shouldNotListenInBuildProfilesOrWithoutTheServerRole(String setting) {
        runner.withPropertyValues("spring.profiles.active=default", setting)
                .run(context -> assertThat(context).doesNotHaveBean("conversationPartialAnswers"));
    }
}
