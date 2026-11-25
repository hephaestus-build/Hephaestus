package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProofReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProofState;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReadersRead;
import de.tum.cit.aet.hephaestus.integration.core.spi.VisibilityClass;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceSyncTargetProvider;
import de.tum.cit.aet.hephaestus.workspace.metrics.WorkspaceMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The GitHub sync proves who can see a monitored repository. An answer that narrows always applies; an answer that
 * widens applies only if no answer applied since its read began. The tests interleave the reads step by step.
 */
class RepositoryReadersProofIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final Instant REQUESTED = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS);
    private static final long REPOSITORY = 456L;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceSyncTargetProvider provider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private RepositoryReadersMeter meter;

    @Autowired
    private MeterRegistry meterRegistry;

    /** A monitor of {@link #REPOSITORY} in a new workspace whose GitHub connection reads it. */
    private RepositoryToMonitor monitor(String slug) {
        long installation = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 2);
        Workspace workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces, connections, WorkspaceTestFixtures.installationWorkspace(installation, slug), installation);
        return monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(workspace, "course/" + slug, REPOSITORY));
    }

    private String proof(RepositoryToMonitor monitor) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT visibility_class || ' ' || proof_state || ' ' || proof_reason FROM repository_to_monitor"
                        + " WHERE id = ?",
                String.class,
                monitor.getId()));
    }

    private long gitHub() {
        return Objects.requireNonNull(ensureGitHubProvider().getId());
    }

    private long scope(RepositoryToMonitor monitor) {
        return Objects.requireNonNull(monitor.getWorkspace()).getId();
    }

    /** A read of the monitored repository that begins now, as the GitHub sync begins one before it asks GitHub. */
    private ReadersRead read(RepositoryToMonitor monitor) {
        return provider.beginReadersProof(monitor.getId(), REPOSITORY, gitHub(), REQUESTED);
    }

    @Test
    void shouldOpenARepositoryThatGitHubShowedPublicAndCloseItWhenGitHubShowsItNotPublic() {
        var monitor = monitor("public-then-private");

        provider.recordRepositoryPublic(read(monitor));
        assertThat(proof(monitor)).isEqualTo("WORKSPACE PROVEN PUBLIC_ON_PROVIDER");
        assertThat(monitors.findById(monitor.getId()).orElseThrow().getReaders().getReadersSyncedAt())
                .as("the proof counts from the request of the read that gave it")
                .isEqualTo(REQUESTED);

        provider.recordRepositoryNotPublic(read(monitor));
        assertThat(proof(monitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
    }

    /** Two syncs overlap: the one that read "public" answers after the one that read "not public". */
    @Test
    void shouldKeepANarrowingAnswerWhenAnOverlappingPublicAnswerArrivesLater() {
        var monitor = monitor("overlap");
        ReadersRead publicRead = read(monitor);
        ReadersRead privateRead = read(monitor);

        provider.recordRepositoryNotPublic(privateRead);
        provider.recordRepositoryPublic(publicRead);

        assertThat(proof(monitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
    }

    /** The replica whose clock runs ahead stamps a later request time, and still does not undo the loss of access. */
    @Test
    void shouldKeepALossOfAccessWhateverTheReplicaClocksSay() {
        var monitor = monitor("clock-skew");
        var other = monitor("clock-skew-elsewhere");
        provider.recordRepositoryPublic(read(other));
        ReadersRead started = read(monitor);

        provider.recordRepositoryUnavailable(scope(monitor), monitor.getId());
        provider.recordRepositoryPublic(new ReadersRead(
                started.syncTargetId(),
                REPOSITORY,
                gitHub(),
                started.generation(),
                Instant.now().plusSeconds(86_400)));

        assertThat(proof(monitor)).isEqualTo("UNPROVEN FAILED RESOURCE_UNAVAILABLE");
        assertThat(proof(other))
                .as("another workspace keeps its proof")
                .isEqualTo("WORKSPACE PROVEN PUBLIC_ON_PROVIDER");
    }

    /** A queued sync read the repository that the monitor named before its identity was repaired. */
    @Test
    void shouldNotProveAMonitorThatNamesAnotherRepositoryThanTheRead() {
        var monitor = monitor("identity-moved");
        ReadersRead readOfTheOldRepository = read(monitor);
        jdbc.update("UPDATE repository_to_monitor SET native_id = ? WHERE id = ?", REPOSITORY + 1, monitor.getId());

        provider.recordRepositoryPublic(readOfTheOldRepository);

        assertThat(proof(monitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
    }

    @Test
    void shouldNotLetAnotherWorkspaceMarkThisMonitorUnavailable() {
        var monitor = monitor("unavailable-scope");
        var stranger = monitor("unavailable-stranger");
        provider.recordRepositoryPublic(read(monitor));

        provider.recordRepositoryUnavailable(scope(stranger), monitor.getId());

        assertThat(proof(monitor)).isEqualTo("WORKSPACE PROVEN PUBLIC_ON_PROVIDER");
    }

    /**
     * Another replica holds the monitor's row lock while it applies a narrowing answer. A public answer from a read
     * that began earlier waits for that lock, then sees the committed generation and does not apply.
     */
    @Test
    void shouldWaitForAnotherReplicasAnswerOnTheSameMonitor() throws Exception {
        var monitor = monitor("contended");
        ReadersRead publicRead = read(monitor);
        var holderPid = new CompletableFuture<Integer>();
        var release = new CountDownLatch(1);
        var otherReplica = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            jdbc.queryForObject(
                    "SELECT id FROM repository_to_monitor WHERE id = ? FOR UPDATE", Long.class, monitor.getId());
            jdbc.update(
                    "UPDATE repository_to_monitor SET proof_generation = proof_generation + 1 WHERE id = ?",
                    monitor.getId());
            holderPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            awaitLatch(release);
        }));
        CompletableFuture<Void> widening;
        try {
            int holder = holderPid.get(30, TimeUnit.SECONDS);
            widening = CompletableFuture.runAsync(() -> provider.recordRepositoryPublic(publicRead));
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                                    Long.class,
                                    holder))
                            .as("the public answer waits for the other replica's row lock")
                            .isEqualTo(1L));
        } finally {
            release.countDown();
        }
        otherReplica.get(30, TimeUnit.SECONDS);
        widening.get(30, TimeUnit.SECONDS);

        assertThat(proof(monitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
    }

    /**
     * Another replica commits a narrowing answer while this transaction already holds the monitor entity; this
     * transaction then applies an older public read. The provider reads the committed row again under its lock.
     */
    @Test
    void shouldReadTheCommittedProofAlsoWhenTheCallerAlreadyHoldsTheMonitor() {
        var monitor = monitor("held-monitor");
        ReadersRead publicRead = read(monitor);

        transactions.executeWithoutResult(status -> {
            var held = monitors.findById(monitor.getId()).orElseThrow();
            assertThat(held.getReaders().getProofGeneration()).isEqualTo(publicRead.generation());
            CompletableFuture.runAsync(() -> provider.recordRepositoryNotPublic(read(monitor)))
                    .join();

            provider.recordRepositoryPublic(publicRead);
        });

        assertThat(proof(monitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void shouldShowOperatorsHowManyRepositoriesWaitForTheirReaders() {
        monitor("meter-waiting");

        meter.measure();

        long waiting = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM repository_to_monitor WHERE visibility_class = 'UNPROVEN'"
                        + " AND proof_reason = 'AWAITING_READERS'",
                Long.class));
        assertThat(waiting).isPositive();
        assertThat(meterRegistry
                        .get(WorkspaceMetrics.MONITORED_REPOSITORY_READERS)
                        .tags(
                                "visibility_class",
                                "UNPROVEN",
                                "proof_state",
                                "PENDING",
                                "proof_reason",
                                "AWAITING_READERS")
                        .gauge()
                        .value())
                .isEqualTo((double) waiting);
    }

    /** A proof that waits shows its age; the alert reads the oldest one. */
    @Test
    void shouldShowOperatorsHowLongTheLongestWaitingProofHasWaited() {
        var monitor = monitor("meter-age");
        jdbc.update(
                "UPDATE repository_to_monitor SET proof_changed_at = now() - interval '2 days' WHERE id = ?",
                monitor.getId());

        meter.measure();

        assertThat(meterRegistry
                        .get(WorkspaceMetrics.MONITORED_REPOSITORY_READERS_WAITING)
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo((double) Duration.ofDays(2).toSeconds());
    }

    /**
     * A proof that no sync renewed waits as much as a pending one: it stays proven in the table, and readers treat it as
     * unproven once it is too old. The gauge counts it from its last renewal.
     */
    @Test
    void shouldShowAProvenProofThatNoSyncRenewedAsWaiting() {
        var monitor = monitor("meter-unrenewed");
        provider.recordRepositoryPublic(read(monitor));
        jdbc.update(
                "UPDATE repository_to_monitor SET readers_synced_at = now() - interval '400 days' WHERE id = ?",
                monitor.getId());

        meter.measure();

        assertThat(proof(monitor)).isEqualTo("WORKSPACE PROVEN PUBLIC_ON_PROVIDER");
        assertThat(meterRegistry
                        .get(WorkspaceMetrics.MONITORED_REPOSITORY_READERS_WAITING)
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo((double) Duration.ofDays(400).toSeconds());
    }

    /**
     * The gauge reads across workspaces, so it carries only counts: no repository name, no workspace and no person,
     * and each label takes one of a closed set of values.
     */
    @Test
    void shouldExposeOnlyCountsUnderClosedLabelsInTheOperatorGauge() {
        monitor("meter-labels");

        meter.measure();

        assertThat(Arrays.stream(RepositoryReadersCount.class.getRecordComponents())
                        .map(component ->
                                component.getName() + ":" + component.getType().getSimpleName()))
                .containsExactly(
                        "visibilityClass:VisibilityClass",
                        "proofState:ProofState",
                        "proofReason:ProofReason",
                        "monitors:long",
                        "oldestActiveWait:Instant");
        var gauges = meterRegistry
                .find(WorkspaceMetrics.MONITORED_REPOSITORY_READERS)
                .gauges();
        assertThat(gauges).isNotEmpty();
        Set<String> classes = names(VisibilityClass.values());
        Set<String> states = names(ProofState.values());
        Set<String> reasons = names(ProofReason.values());
        for (Gauge gauge : gauges) {
            var tags = gauge.getId().getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue));
            assertThat(tags).containsOnlyKeys("visibility_class", "proof_state", "proof_reason");
            assertThat(classes).contains(tags.get("visibility_class"));
            assertThat(states).contains(tags.get("proof_state"));
            assertThat(reasons).contains(tags.get("proof_reason"));
        }
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }
}
