package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermit;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

class LlmCallPermitCrashIntegrationTest extends BaseIntegrationTest {
    private static final LlmCallPoolProperties PROPERTIES =
            new LlmCallPoolProperties(Duration.ofSeconds(20), Duration.ofSeconds(30));

    @Autowired
    private LlmCallPoolRepository pools;

    @Autowired
    private LlmCallPermitRepository rows;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    private Path directory;

    @Test
    void shouldReclaimAKilledWorkersGrantOnceTheLeaseExpires(TestReporter reporter) throws Exception {
        var pool = new LlmCallPool();
        pool.setSlug("crash-" + UUID.randomUUID());
        pool.setDisplayName("Crash recovery");
        pool.setMaxConcurrentCalls(1);
        long poolId = pools.save(pool).getId();
        var survivor = new LlmCallPermits(
                pools,
                rows,
                transactions,
                PROPERTIES,
                "survivor-" + UUID.randomUUID(),
                new SimpleMeterRegistry(),
                mock(LlmModelResolver.class));
        var postgres = PostgreSQLTestContainer.getInstance();
        Path output = directory.resolve("worker.log");
        try (var provider = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            provider.setSoTimeout(30_000);
            String java = ProcessHandle.current().info().command().orElseThrow();
            var builder = new ProcessBuilder(
                            java,
                            "-cp",
                            System.getProperty("java.class.path"),
                            Worker.class.getName(),
                            Long.toString(poolId),
                            Integer.toString(provider.getLocalPort()))
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().put("CRASH_TEST_JDBC_URL", postgres.getJdbcUrl());
            builder.environment().put("CRASH_TEST_JDBC_USER", postgres.getUsername());
            builder.environment().put("CRASH_TEST_JDBC_PASSWORD", postgres.getPassword());
            Process worker = builder.start();
            try (Socket upstream = provider.accept()) {
                upstream.setSoTimeout(5_000);
                assertThat(upstream.getInputStream().read()).isEqualTo((int) 'P');
                upstream.getOutputStream()
                        .write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\ndata: started\n\n"
                                .getBytes(StandardCharsets.UTF_8));
                upstream.getOutputStream().flush();
                Instant firstRenewal = renewedAt(poolId);
                await().atMost(PROPERTIES.renewEvery().plusSeconds(5)).untilAsserted(() -> {
                    assertThat(worker.isAlive()).as(Files.readString(output)).isTrue();
                    assertThat(renewedAt(poolId)).isAfter(firstRenewal);
                });
                assertThat(rows.countActive(poolId)).isEqualTo(1);
                // Forced termination runs neither finally nor shutdown hooks.
                worker.destroyForcibly();
                assertThat(worker.waitFor(5, TimeUnit.SECONDS)).isTrue();
                Instant lastRenewal = renewedAt(poolId);
                upstream.getInputStream().readAllBytes();
                assertThat(rows.countActive(poolId)).isEqualTo(1);
                survivor.start();
                try (var replacement = Objects.requireNonNull(survivor.acquire(
                        poolId,
                        LlmCallClass.EVENT,
                        new LlmCallPermits.Caller(2L, UUID.randomUUID(), 0, null),
                        () -> false,
                        lastRenewal.plus(PROPERTIES.permitLease()).plusSeconds(3)))) {
                    Duration age = Duration.between(lastRenewal, Instant.now());
                    assertThat(age).isGreaterThanOrEqualTo(PROPERTIES.permitLease());
                    assertThat(age).isLessThan(PROPERTIES.permitLease().plusSeconds(3));
                    assertThat(rows.countActive(poolId)).isEqualTo(1);
                    assertThat(rows.rates(poolId).getRequests()).isEqualTo(2);
                    assertThat(replacement.isRevoked()).isFalse();
                    reporter.publishEntry("crashRecoveryAgeMs", Long.toString(age.toMillis()));
                    reporter.publishEntry(
                            "permitLeaseMs",
                            Long.toString(PROPERTIES.permitLease().toMillis()));
                }
            } catch (Exception failed) {
                throw new IllegalStateException(Files.readString(output), failed);
            } finally {
                worker.destroyForcibly();
                assertThat(worker.waitFor(5, TimeUnit.SECONDS)).isTrue();
                survivor.stop();
            }
        }
    }

    private Instant renewedAt(long poolId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT renewed_at FROM llm_call_permit WHERE pool_id=? AND state='ACTIVE'",
                        Timestamp.class,
                        poolId))
                .toInstant();
    }

    /** Isolated worker runs the production repositories, admission and renewal against the test database. */
    public static final class Worker {
        public static void main(String[] args) throws Exception {
            var source = new DriverManagerDataSource(
                    Objects.requireNonNull(System.getenv("CRASH_TEST_JDBC_URL")),
                    Objects.requireNonNull(System.getenv("CRASH_TEST_JDBC_USER")),
                    Objects.requireNonNull(System.getenv("CRASH_TEST_JDBC_PASSWORD")));
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setManagedTypes(
                    PersistenceManagedTypes.of(LlmCallPool.class.getName(), LlmCallPermit.class.getName()));
            factory.afterPropertiesSet();
            try {
                var entityManagerFactory = Objects.requireNonNull(factory.getObject());
                var repositories = new JpaRepositoryFactory(
                        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
                var permits = new LlmCallPermits(
                        repositories.getRepository(LlmCallPoolRepository.class),
                        repositories.getRepository(LlmCallPermitRepository.class),
                        new TransactionTemplate(new JpaTransactionManager(entityManagerFactory)),
                        PROPERTIES,
                        "crashed-" + UUID.randomUUID(),
                        new SimpleMeterRegistry(),
                        mock(LlmModelResolver.class));
                permits.start();
                try (var permit = Objects.requireNonNull(permits.acquire(
                                Long.parseLong(args[0]),
                                LlmCallClass.EVENT,
                                new LlmCallPermits.Caller(1L, UUID.randomUUID(), 0, null),
                                () -> false,
                                Instant.now().plusSeconds(10)));
                        var call = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(args[1]))) {
                    assertThat(permit.isRevoked()).isFalse();
                    call.getOutputStream()
                            .write("POST /fake-provider HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n"
                                    .getBytes(StandardCharsets.UTF_8));
                    call.getOutputStream().flush();
                    while (call.getInputStream().read() != -1) {
                        /* The fake provider holds the body open. */
                    }
                } finally {
                    permits.stop();
                }
            } finally {
                factory.destroy();
            }
        }
    }
}
