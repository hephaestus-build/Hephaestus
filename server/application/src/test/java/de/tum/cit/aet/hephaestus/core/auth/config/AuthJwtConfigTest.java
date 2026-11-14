package de.tum.cit.aet.hephaestus.core.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.jwt.AccessTokenAdmission;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtSigningKeyService;
import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.core.security.StoredSystemSecrets;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class AuthJwtConfigTest extends BaseUnitTest {

    private final JwtSigningKeyService keyService = mock(JwtSigningKeyService.class);
    private final StoredSystemSecrets storedSecrets = mock(StoredSystemSecrets.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AuthJwtConfig.class)
            .withBean(JwtSigningKeyService.class, () -> keyService)
            .withBean(StoredSystemSecrets.class, () -> storedSecrets)
            .withBean(AccessTokenAdmission.class, () -> mock(AccessTokenAdmission.class))
            .withBean(AuthEventLogger.class, () -> mock(AuthEventLogger.class))
            .withBean(AuthMetrics.class, () -> mock(AuthMetrics.class))
            .withBean(CacheManager.class, ConcurrentMapCacheManager::new)
            .withBean(Clock.class, Clock::systemUTC);

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldNotSeedSigningKeysInBuildProfiles(String profile) {
        contextRunner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ApplicationRunner.class);
            verify(keyService, never()).ensureActiveKey();
        });
    }

    /** The check comes first, so a server with another system key never seals a new signing key. */
    @Test
    void shouldCheckTheStoredKeysBeforeItSeedsASigningKey() {
        contextRunner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
            var order = inOrder(keyService, storedSecrets);
            order.verify(keyService).verifyActiveKeysOpen();
            order.verify(storedSecrets).verifyAllOpen();
            order.verify(keyService).ensureActiveKey();
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

    /** A system key that does not open the stored keys must stop the server instead of letting it refuse every session. */
    @Test
    void shouldStopStartupWhenTheSystemKeyDoesNotOpenTheStoredKeys() {
        doThrow(new IllegalStateException(
                        "hephaestus.security.encryption-key does not open the stored JWT signing key"))
                .when(keyService)
                .verifyActiveKeysOpen();
        contextRunner.run(context -> {
            var runner = context.getBean(ApplicationRunner.class);
            assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not open the stored JWT signing key");
            verify(keyService, never()).ensureActiveKey();
        });
    }
}
