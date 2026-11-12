package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermit;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
        var meters = new SimpleMeterRegistry();
        var survivor = new LlmCallPermits(
                pools,
                rows,
                transactions,
                PROPERTIES,
                LlmCallPermitsTestFixtures.PERMIT_AGING,
                "survivor-" + UUID.randomUUID(),
                meters,
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
                        AgentRunBand.BATCH,
                        new LlmCallPermits.Caller(2L, UUID.randomUUID(), 0, null),
                        () -> false,
                        Instant.now().plus(PROPERTIES.permitLease()).plusSeconds(3)))) {
                    Duration age = Duration.between(lastRenewal, databaseNow());
                    assertThat(age).isGreaterThanOrEqualTo(PROPERTIES.permitLease());
                    assertThat(age).isLessThan(PROPERTIES.permitLease().plusSeconds(3));
                    assertThat(rows.countActive(poolId)).isEqualTo(1);
                    assertThat(rows.rates(poolId).getRequests()).isEqualTo(2);
                    assertThat(replacement.isRevoked()).isFalse();
                    assertThat(meters.get(AgentMetrics.LLM_POOL_PERMITS_EXPIRED)
                                    .tag("pool", pool.getSlug())
                                    .counter()
                                    .count())
                            .isEqualTo(1);
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

    /**
     * A lost database connection hangs on its socket and raises no error, and the worker's other tasks that read the
     * database hang beside it. The worker must still cut its call before the lease ends. The fake call here ends on the
     * permit's signal; {@code LlmProxyIntegrationTest} proves that the real transports end on it.
     */
    @Test
    void shouldCloseAPartitionedWorkersTransportBeforeItsLeaseExpires(TestReporter reporter) throws Exception {
        var pool = new LlmCallPool();
        pool.setSlug("partition-" + UUID.randomUUID());
        pool.setDisplayName("Partition recovery");
        pool.setMaxConcurrentCalls(1);
        long poolId = pools.save(pool).getId();
        var survivor = new LlmCallPermits(
                pools,
                rows,
                transactions,
                PROPERTIES,
                LlmCallPermitsTestFixtures.PERMIT_AGING,
                "survivor-" + UUID.randomUUID(),
                new SimpleMeterRegistry(),
                mock(LlmModelResolver.class));
        var postgres = PostgreSQLTestContainer.getInstance();
        Path output = directory.resolve("partitioned-worker.log");
        try (var provider = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var database = new DatabaseRelay(postgres.getHost(), postgres.getFirstMappedPort())) {
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
            builder.environment()
                    .put(
                            "CRASH_TEST_JDBC_URL",
                            postgres.getJdbcUrl()
                                    .replaceFirst(
                                            "//[^/]+/",
                                            "//"
                                                    + InetAddress.getLoopbackAddress()
                                                            .getHostAddress() + ":" + database.port() + "/"));
            builder.environment().put("CRASH_TEST_JDBC_USER", postgres.getUsername());
            builder.environment().put("CRASH_TEST_JDBC_PASSWORD", postgres.getPassword());
            Process worker = builder.start();
            try (Socket upstream = provider.accept()) {
                upstream.setSoTimeout(30_000);
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
                database.blackHole();
                // A renewal already on its way may still land.
                TimeUnit.SECONDS.sleep(1);
                Instant lastRenewal = renewedAt(poolId);
                upstream.getInputStream().readAllBytes();
                Duration closedAfter = Duration.between(lastRenewal, databaseNow());
                assertThat(closedAfter).isLessThan(PROPERTIES.permitLease());
                assertThat(worker.isAlive()).as(Files.readString(output)).isTrue();
                assertThat(rows.countActive(poolId)).isEqualTo(1);
                survivor.start();
                try (var replacement = Objects.requireNonNull(survivor.acquire(
                        poolId,
                        AgentRunBand.BATCH,
                        new LlmCallPermits.Caller(2L, UUID.randomUUID(), 0, null),
                        () -> false,
                        Instant.now().plus(PROPERTIES.permitLease()).plusSeconds(3)))) {
                    Duration reclaimedAfter = Duration.between(lastRenewal, databaseNow());
                    assertThat(reclaimedAfter).isGreaterThanOrEqualTo(PROPERTIES.permitLease());
                    assertThat(replacement.isRevoked()).isFalse();
                    assertThat(rows.countActive(poolId)).isEqualTo(1);
                    assertThat(rows.rates(poolId).getRequests()).isEqualTo(2);
                    reporter.publishEntry("transportClosedAfterMs", Long.toString(closedAfter.toMillis()));
                    reporter.publishEntry("reclaimedAfterMs", Long.toString(reclaimedAfter.toMillis()));
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

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
    }

    private Instant renewedAt(long poolId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT renewed_at FROM llm_call_permit WHERE pool_id=? AND state='ACTIVE'",
                        Timestamp.class,
                        poolId))
                .toInstant();
    }

    /** A TCP relay to the database that can stop passing bytes while it keeps every socket open, as a lost route does. */
    private static final class DatabaseRelay implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private volatile boolean blackHole;

        DatabaseRelay(String host, int port) throws IOException {
            Thread.ofVirtual().name("database-relay").start(() -> {
                try {
                    while (true) {
                        Socket client = listener.accept();
                        sockets.add(client);
                        if (blackHole) continue;
                        Socket server = new Socket(host, port);
                        sockets.add(server);
                        pump(client, server);
                        pump(server, client);
                    }
                } catch (IOException closed) {
                    // The test ended.
                }
            });
        }

        int port() {
            return listener.getLocalPort();
        }

        void blackHole() {
            blackHole = true;
        }

        private void pump(Socket from, Socket to) {
            Thread.ofVirtual().name("database-relay-pump").start(() -> {
                byte[] buffer = new byte[8192];
                try {
                    for (int read = from.getInputStream().read(buffer);
                            read != -1;
                            read = from.getInputStream().read(buffer)) {
                        if (blackHole) continue;
                        to.getOutputStream().write(buffer, 0, read);
                        to.getOutputStream().flush();
                    }
                } catch (IOException closed) {
                    // The test ended.
                }
            });
        }

        @Override
        public void close() throws IOException {
            listener.close();
            for (Socket socket : sockets) socket.close();
        }
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
                        LlmCallPermitsTestFixtures.PERMIT_AGING,
                        "crashed-" + UUID.randomUUID(),
                        new SimpleMeterRegistry(),
                        mock(LlmModelResolver.class));
                permits.start();
                try (var permit = Objects.requireNonNull(permits.acquire(
                                Long.parseLong(args[0]),
                                AgentRunBand.BATCH,
                                new LlmCallPermits.Caller(1L, UUID.randomUUID(), 0, null),
                                () -> false,
                                Instant.now().plusSeconds(60)));
                        var call = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(args[1]))) {
                    assertThat(permit.isRevoked()).isFalse();
                    // The proxy ends its exchange on this signal.
                    var unused = permit.revoked()
                            .doOnSuccess(revoked -> closeQuietly(call))
                            .subscribe();
                    call.getOutputStream()
                            .write("POST /fake-provider HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n"
                                    .getBytes(StandardCharsets.UTF_8));
                    call.getOutputStream().flush();
                    try {
                        while (call.getInputStream().read() != -1) {
                            /* The fake provider holds the body open. */
                        }
                    } catch (SocketException revoked) {
                        // The permit's revocation closed the call. The process stays up, like a partitioned worker.
                        TimeUnit.MINUTES.sleep(5);
                    }
                } finally {
                    permits.stop();
                }
            } finally {
                factory.destroy();
            }
        }

        private static void closeQuietly(Socket call) {
            try {
                call.close();
            } catch (IOException ignored) {
                // Nothing is left to close.
            }
        }
    }
}
