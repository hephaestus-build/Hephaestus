package de.tum.cit.aet.hephaestus.core.auth.ratelimit;

import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the {@link AuthRateLimitFilter} and its PostgreSQL bucket store.
 *
 * <p>The store is a Bucket4j {@code ProxyManager} (SELECT … FOR UPDATE on the
 * {@code auth_rate_limit_bucket} table created by the consolidated auth Liquibase changelog). Buckets
 * are persisted, so the limits are shared across all server replicas with no extra infrastructure: the
 * store reuses the existing DataSource (no Redis; ADR 0017 constraint). Worker-only pods exclude this
 * configuration; their gateway resolver lives in
 * {@link de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConfiguration}.
 */
@ConditionalOnServerRole
@Configuration
public class AuthRateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitConfig.class);

    /** Table backing the distributed buckets (see the matching Liquibase changelog). */
    static final String BUCKET_TABLE = "auth_rate_limit_bucket";

    /**
     * Cluster-shared bucket store. The builder defaults to {@code BIGINT} keys; we remap to
     * {@code STRING} because our keys are heterogeneous (IP strings + decimal account ids).
     *
     * <p>{@code expirationAfterWrite} populates the {@code expires_at} column so
     * {@link AuthRateLimitBucketSweeper} can prune stale rows — without it the table grows without
     * bound on the pre-auth, IP-keyed hot path (one permanent row per distinct/rotating client IP).
     * {@code basedOnTimeForRefillingBucketUpToMax} keeps a bucket until it would have fully refilled
     * to max — i.e. until the limit no longer constrains anyone — so eviction never resets a still
     * meaningful limit; the one-minute argument is slack on top of that refill time.
     */
    @Bean
    ProxyManager<String> authRateLimitProxyManager(DataSource dataSource) {
        ProxyManager<String> proxyManager = Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table(BUCKET_TABLE)
                .expirationAfterWrite(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(1)))
                .build();
        log.info("Auth rate limiting: PostgreSQL-backed (table={}), limits shared across replicas.", BUCKET_TABLE);
        return proxyManager;
    }

    @Bean
    BucketResolver postgresBucketResolver(ProxyManager<String> proxyManager) {
        return (key, config) -> proxyManager.getProxy(key, () -> config);
    }

    /** Prunes expired buckets so {@code auth_rate_limit_bucket} stays bounded. */
    @Bean
    AuthRateLimitBucketSweeper authRateLimitBucketSweeper(ProxyManager<String> proxyManager) {
        return new AuthRateLimitBucketSweeper(proxyManager);
    }

    @Bean
    AuthRateLimitFilter authRateLimitFilter(
            AuthRateLimitProperties properties,
            BucketResolver bucketResolver,
            ObjectMapper objectMapper,
            AuthMetrics metrics) {
        return new AuthRateLimitFilter(properties, bucketResolver, objectMapper, metrics);
    }
}
