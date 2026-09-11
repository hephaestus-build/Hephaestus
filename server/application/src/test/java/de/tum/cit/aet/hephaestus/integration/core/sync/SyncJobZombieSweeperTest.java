package de.tum.cit.aet.hephaestus.integration.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SyncJobZombieSweeperTest extends BaseUnitTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SyncJobZombieSweeper.class);

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldNotRequireDatabaseServicesInBuildProfiles(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SyncJobZombieSweeper.class));
    }

    @Test
    void shouldNotRequireDatabaseServicesWithoutTheServerRole() {
        runner.withPropertyValues("hephaestus.runtime.server.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SyncJobZombieSweeper.class));
    }

    @Test
    void shouldReapAbandonedJobsWhenTheServerIsReady() {
        var service = mock(SyncJobService.class);
        runner.withBean(SyncJobService.class, () -> service).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SyncJobZombieSweeper.class);
            context.publishEvent(mock(ApplicationReadyEvent.class));
            verify(service).reapAbandonedJobs();
        });
    }
}
