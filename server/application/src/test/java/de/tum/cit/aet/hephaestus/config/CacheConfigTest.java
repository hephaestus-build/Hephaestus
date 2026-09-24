package de.tum.cit.aet.hephaestus.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleCacheManager;

class CacheConfigTest extends BaseUnitTest {

    private static final List<String> EXPECTED_NAMES = List.of(
            "auth_jwt_revoked",
            "contributors",
            "mentor_authored_work_context",
            "mentor_practice_context",
            "mentor_user_context",
            "mentor_workspace_context",
            "pullRequestTemplates");

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
    @DisplayName("mentor context caches share a 5-minute TTL and 512-entry cap")
    void mentorContextsHaveCorrectTtlAndSize() {
        List<String> mentorCaches = CacheConfig.SPECS.stream()
                .map(CacheConfig.CacheSpec::name)
                .filter(name -> name.startsWith("mentor_"))
                .toList();
        assertThat(mentorCaches)
                .as("expected all four mentor context caches")
                .containsExactlyInAnyOrder(
                        "mentor_authored_work_context",
                        "mentor_practice_context",
                        "mentor_user_context",
                        "mentor_workspace_context");
        for (String name : mentorCaches) {
            CacheConfig.CacheSpec spec = findSpec(name);
            assertThat(spec.ttl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(spec.maxSize()).isEqualTo(512L);
        }
    }

    @Test
    void contributorAndTemplateCachesHaveOneHourTtlAndThousandEntryLimit() {
        for (String name : List.of("contributors", "pullRequestTemplates")) {
            CacheConfig.CacheSpec spec = findSpec(name);
            assertThat(spec.ttl()).isEqualTo(Duration.ofSeconds(3600));
            assertThat(spec.maxSize()).isEqualTo(1000L);
        }
    }

    @Test
    void cacheSpecValidation() {
        Assertions.assertThatThrownBy(() -> new CacheConfig.CacheSpec("", Duration.ofMinutes(1), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThatThrownBy(() -> new CacheConfig.CacheSpec("ok", Duration.ZERO, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThatThrownBy(() -> new CacheConfig.CacheSpec("ok", Duration.ofMinutes(1), 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CacheConfig.CacheSpec findSpec(String name) {
        return CacheConfig.SPECS.stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing spec: " + name));
    }
}
