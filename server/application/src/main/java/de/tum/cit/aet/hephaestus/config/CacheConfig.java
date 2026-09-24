package de.tum.cit.aet.hephaestus.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    private static final Duration LONG_TTL = Duration.ofSeconds(3600);

    private static final Duration MENTOR_CONTEXT_TTL = Duration.ofMinutes(5);

    /** Only revoked verdicts are cached; expiry triggers a database recheck, never admission. */
    private static final Duration AUTH_JWT_REVOKED_TTL = Duration.ofMinutes(15);

    private static final long LONG_MAX = 1000L;

    private static final long MENTOR_MAX = 512L;

    private static final long AUTH_JWT_REVOKED_MAX = 10_000L;

    static final List<CacheSpec> SPECS = List.of(
            // Name mirrors core.auth.jwt.RevocationAwareJwtDecoder.CACHE_NAME (kept as a literal to
            // avoid a config→core.auth internal-type dependency; the decoder owns the canonical const).
            new CacheSpec("auth_jwt_revoked", AUTH_JWT_REVOKED_TTL, AUTH_JWT_REVOKED_MAX),
            new CacheSpec("contributors", LONG_TTL, LONG_MAX),
            new CacheSpec("mentor_authored_work_context", MENTOR_CONTEXT_TTL, MENTOR_MAX),
            new CacheSpec("mentor_practice_context", MENTOR_CONTEXT_TTL, MENTOR_MAX),
            new CacheSpec("mentor_user_context", MENTOR_CONTEXT_TTL, MENTOR_MAX),
            new CacheSpec("mentor_workspace_context", MENTOR_CONTEXT_TTL, MENTOR_MAX),
            new CacheSpec("pullRequestTemplates", LONG_TTL, LONG_MAX));

    @Bean
    public SimpleCacheManager cacheManager(MeterRegistry meterRegistry) {
        SimpleCacheManager manager = new SimpleCacheManager();
        List<CaffeineCache> caches = new ArrayList<>(SPECS.size());
        for (CacheSpec spec : SPECS) {
            caches.add(buildCache(spec, meterRegistry));
        }
        manager.setCaches(caches);
        return manager;
    }

    private static CaffeineCache buildCache(CacheSpec spec, MeterRegistry meterRegistry) {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> cache = Caffeine.newBuilder()
                .expireAfterWrite(spec.ttl())
                .maximumSize(spec.maxSize())
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, cache, spec.name(), List.of());
        return new CaffeineCache(spec.name(), cache);
    }

    public record CacheSpec(String name, Duration ttl, long maxSize) {
        public CacheSpec {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("cache name must not be blank");
            }
            if (ttl == null || ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("ttl must be positive, got: " + ttl);
            }
            if (maxSize <= 0) {
                throw new IllegalArgumentException("maxSize must be positive, got: " + maxSize);
            }
        }
    }
}
