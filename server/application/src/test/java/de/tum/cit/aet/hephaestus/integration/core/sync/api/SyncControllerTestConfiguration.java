package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.core.connection.api.ConnectionAdminService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationSyncRunner;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;

@TestConfiguration(proxyBeanMethods = false)
public class SyncControllerTestConfiguration {

    @Bean
    SyncControllerTestDriver syncControllerTestDriver() {
        return new SyncControllerTestDriver(mock(ConnectionSyncStateProvider.class), mock(IntegrationSyncRunner.class));
    }

    @Bean
    @Primary
    SyncStatusService testSyncStatusService(
            ConnectionAdminService connectionAdminService,
            SyncJobService syncJobService,
            SyncJobRepository syncJobRepository,
            ConnectionActivityRepository connectionActivityRepository,
            @Qualifier("syncJobExecutor") AsyncTaskExecutor taskExecutor,
            SyncControllerTestDriver driver) {
        return new SyncStatusService(
                connectionAdminService,
                syncJobService,
                syncJobRepository,
                connectionActivityRepository,
                taskExecutor,
                List.of(driver.stateProvider()),
                List.of(driver.runner()),
                org.mockito.Mockito.mock(SyncTargetProvider.class));
    }

    public record SyncControllerTestDriver(ConnectionSyncStateProvider stateProvider, IntegrationSyncRunner runner) {}
}
