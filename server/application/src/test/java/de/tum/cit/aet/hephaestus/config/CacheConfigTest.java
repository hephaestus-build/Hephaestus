package de.tum.cit.aet.hephaestus.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleCacheManager;

class CacheConfigTest extends BaseUnitTest {

    private static final List<String> EXPECTED_NAMES = List.of("auth_jwt_revoked");

    @Test
    @DisplayName("cacheManager exposes exactly the declared caches by name")
    void registersAllSpecsAsCaches() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SimpleCacheManager manager = new CacheConfig().cacheManager(registry);
        manager.afterPropertiesSet();

        Collection<String> names = manager.getCacheNames();
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_NAMES);

        for (String name : EXPECTED_NAMES) {
            Cache cache = manager.getCache(name);
            assertThat(cache).as("cache %s missing", name).isNotNull();
        }
    }

    @Test
    void cacheSpecValidation() {
        assertThatThrownBy(() -> new CacheConfig.CacheSpec("", Duration.ofMinutes(1), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CacheConfig.CacheSpec("ok", Duration.ZERO, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CacheConfig.CacheSpec("ok", Duration.ofMinutes(1), 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
