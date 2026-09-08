package de.tum.cit.aet.hephaestus.core.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtSigningKeyService;
import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class AuthJwtConfigTest extends BaseUnitTest {

    private final JwtSigningKeyService keyService = mock(JwtSigningKeyService.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AuthJwtConfig.class)
            .withBean(JwtSigningKeyService.class, () -> keyService)
            .withBean(IssuedJwtRepository.class, () -> mock(IssuedJwtRepository.class))
            .withBean(AuthMetrics.class, () -> mock(AuthMetrics.class))
            .withBean(CacheManager.class, ConcurrentMapCacheManager::new)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void shouldNotSeedSigningKeysDuringCdsContextRefresh() {
        contextRunner.withPropertyValues("spring.profiles.active=cds-training").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ApplicationRunner.class);
            verify(keyService, never()).ensureActiveKey();
        });
    }

    @Test
    void shouldSeedSigningKeysDuringProductionStartup() {
        contextRunner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            verify(keyService).assertProdKeysSealed();
            context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
            verify(keyService).ensureActiveKey();
        });
    }

    @Test
    void shouldDeferSeedingWhenTheDatabaseIsUnavailable() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(keyService)
                .ensureActiveKey();
        contextRunner.run(context -> {
            context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
            verify(keyService).ensureActiveKey();
        });
    }

    @Test
    void shouldRejectUnsealedProductionKeysDuringContextRefresh() {
        doThrow(new IllegalStateException("unsealed signing key"))
                .when(keyService)
                .assertProdKeysSealed();
        contextRunner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("unsealed signing key");
            verify(keyService, never()).ensureActiveKey();
        });
    }
}
