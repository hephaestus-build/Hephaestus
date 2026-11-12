package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MentorPartialAnswersListeningTest extends BaseUnitTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MentorTimeline.PartialAnswersConfiguration.class)
            .withBean(MentorLedgerNotifications.class, () -> mock(MentorLedgerNotifications.class));

    @Test
    void shouldListenForPartialAnswersOnAServer() {
        runner.withPropertyValues("spring.profiles.active=default")
                .run(context -> assertThat(context).hasBean("mentorPartialAnswers"));
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
                .run(context -> assertThat(context).doesNotHaveBean("mentorPartialAnswers"));
    }
}
