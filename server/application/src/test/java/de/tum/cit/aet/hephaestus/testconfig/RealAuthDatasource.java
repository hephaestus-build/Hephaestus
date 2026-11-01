package de.tum.cit.aet.hephaestus.testconfig;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Shared {@code @DynamicPropertySource} datasource wiring for integration tests that need the
 * <b>real</b> security chain (genuine ES256 cookie-JWT + {@code RevocationAwareJwtDecoder}) and so
 * cannot extend {@link BaseIntegrationTest} — its {@link TestSecurityConfig} swaps in a mock JWT
 * decoder. Carries the same Postgres + HikariCP settings as {@link BaseIntegrationTest} so these
 * tests get the pool sizing that {@code REQUIRES_NEW} paths (e.g. {@link DatabaseTestUtils}) need,
 * instead of a hand-trimmed copy per test.
 */
public final class RealAuthDatasource {

    private RealAuthDatasource() {}

    public static void register(DynamicPropertyRegistry registry) {
        var postgres = PostgreSQLTestContainer.getInstance();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("spring.datasource.hikari.max-lifetime", () -> "300000");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "10000");
        registry.add("spring.datasource.hikari.idle-timeout", () -> "10000");
        registry.add("spring.datasource.hikari.leak-detection-threshold", () -> "10000");
    }
}
