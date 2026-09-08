package de.tum.cit.aet.hephaestus.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.achievement.AchievementService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache configuration using Caffeine for TTL support and Micrometer-bound metrics.
 *
 * <p>All caches expose Micrometer metrics:
 * <ul>
 *   <li>{@code cache.gets} — hit/miss count by result tag</li>
 *   <li>{@code cache.evictions} — eviction count</li>
 *   <li>{@code cache.size} — current entry count</li>
 * </ul>
 *
 */
@Configuration
public class CacheConfig {

    /** TTL for long-lived caches (contributors, PR templates, achievement progress). */
    private static final Duration LONG_TTL = Duration.ofSeconds(3600);

    /** TTL for mentor context caches. Short enough to be invisible per-turn, long enough to be warm across consecutive turns. */
    private static final Duration MENTOR_CONTEXT_TTL = Duration.ofMinutes(5);

    /** Bounds revoked-token replay load; expiry triggers a fresh database check, not acceptance. */
    private static final Duration AUTH_JWT_REVOKED_TTL = Duration.ofMinutes(15);

    /** Max entries for the long-lived caches. */
    private static final long LONG_MAX = 1000L;

    /** Max entries for mentor context caches — bounded per active user, not per workspace. */
    private static final long MENTOR_MAX = 512L;

    /** Max entries for the JWT revocation cache — bounded per active session. */
    private static final long AUTH_JWT_REVOKED_MAX = 10_000L;

    /**
     * Declarative cache specs. Order doesn't matter at runtime — the manager owns the lookup
     * map. Keep alphabetical for diff hygiene.
     */
    static final List<CacheSpec> SPECS = List.of(
            new CacheSpec(AchievementService.ACHIEVEMENT_PROGRESS_CACHE, LONG_TTL, LONG_MAX),
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
    public CacheManager cacheManager(MeterRegistry meterRegistry) {
        SimpleCacheManager manager = new SimpleCacheManager();
        List<CaffeineCache> caches = new ArrayList<>(SPECS.size());
        for (CacheSpec spec : SPECS) {
            caches.add(buildCache(spec, meterRegistry));
        }
        manager.setCaches(caches);
        // SimpleCacheManager defers populating its internal lookup map until afterPropertiesSet()
        // is invoked. Spring calls it automatically when the bean is wired; we call it eagerly
        // so the manager is fully ready before the @Bean method returns (matters for tests
        // that instantiate the configuration directly).
        manager.afterPropertiesSet();
        return manager;
    }

    /**
     * Build a single Caffeine-backed Spring cache from a {@link CacheSpec} and bind it to
     * Micrometer.
     */
    private static CaffeineCache buildCache(CacheSpec spec, MeterRegistry meterRegistry) {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> cache = Caffeine.newBuilder()
                .expireAfterWrite(spec.ttl())
                .maximumSize(spec.maxSize())
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, cache, spec.name(), List.of());
        return new CaffeineCache(spec.name(), cache);
    }

    /**
     * One row of the cache table.
     *
     * @param name    cache name; the value passed to {@code @Cacheable(value="...")}
     * @param ttl     expire-after-write duration
     * @param maxSize Caffeine size cap; entries beyond this are evicted by Window-TinyLFU
     */
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
