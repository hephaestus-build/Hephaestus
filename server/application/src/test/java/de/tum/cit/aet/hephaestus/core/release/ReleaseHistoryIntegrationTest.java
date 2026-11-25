package de.tum.cit.aet.hephaestus.core.release;

import static de.tum.cit.aet.hephaestus.core.release.ReleaseFixtures.running;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Release history against real PostgreSQL and its clock, with the rollout that Kubernetes would report. */
@Tag("integration")
class ReleaseHistoryIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ReleaseActivationRepository repository;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    /** Each test gets its own deployment environment, so rows other tests wrote are not in its history. */
    private final String deployment = "test-" + UUID.randomUUID().toString().substring(0, 8);

    private final String cluster = UUID.randomUUID().toString();

    /** One server process, in the Deployment {@code uid} at {@code revision}, or outside Kubernetes. */
    private ReleaseHistory server(String version, @Nullable String uid, long revision, String environmentName) {
        var beans = new StaticListableBeanFactory(
                uid == null ? Map.of() : Map.of("rolloutReader", (RolloutReader) () -> new Rollout(uid, revision)));
        return new ReleaseHistory(
                running(version, environmentName),
                repository,
                transaction,
                environment,
                beans.getBeanProvider(RolloutReader.class));
    }

    private ReleaseHistory server(String version, long revision) {
        return server(version, cluster, revision, deployment);
    }

    private ReleaseHistory outsideKubernetes(String version) {
        return server(version, null, 0, deployment);
    }

    private List<ReleaseActivation> entries() {
        return outsideKubernetes("0.0.0").recent();
    }

    private List<String> history() {
        return entries().stream()
                .map(entry -> entry.getVersion() + "@" + entry.getRevision())
                .toList();
    }

    @Test
    void shouldRecordNothingWhenAnOldServerRestartsDuringARollout() {
        server("1.2.3", 1).onApplicationReady();
        server("1.3.0", 2).onApplicationReady();
        // An old server restarts, or reports for the first time, while the rollout to 1.3.0 runs.
        var old = server("1.2.3", 1);
        old.onApplicationReady();

        assertThat(history()).containsExactly("1.3.0@2", "1.2.3@1");
        assertThat(old.runningSince(entries())).isNull();
        var current = server("1.3.0", 2);
        current.onApplicationReady();
        assertThat(current.runningSince(entries()))
                .isEqualTo(entries().getFirst().getActivatedAt());
    }

    @Test
    void shouldRecordARollbackAsTheNewRevisionThatTheControllerGaveIt() {
        server("1.2.3", 1).onApplicationReady();
        server("1.3.0", 2).onApplicationReady();
        // `helm rollback` and `kubectl rollout undo` scale the earlier ReplicaSet up again as revision 3.
        var rolledBack = server("1.2.3", 3);
        rolledBack.onApplicationReady();
        server("1.2.3", 3).onApplicationReady();

        assertThat(history()).containsExactly("1.2.3@3", "1.3.0@2", "1.2.3@1");
        assertThat(rolledBack.runningSince(entries())).isNotNull();
    }

    @Test
    void shouldOrderARolloutThatWasRecordedLateByItsRevision() {
        server("1.3.0", 2).onApplicationReady();
        // The first reports of revision 1 failed; its first one that lands comes after revision 2 was recorded.
        server("1.2.3", 1).onApplicationReady();

        assertThat(history()).containsExactly("1.3.0@2", "1.2.3@1");
    }

    @Test
    void shouldPutAFreshInstallAfterTheRolloutsOfARestoredDatabase() {
        server("1.2.3", 1).onApplicationReady();
        server("1.3.0", 2).onApplicationReady();
        // A restore into a new cluster: its Deployment has a new UID and starts at revision 1 again.
        server("1.3.0", UUID.randomUUID().toString(), 1, deployment).onApplicationReady();

        assertThat(history()).containsExactly("1.3.0@1", "1.3.0@2", "1.2.3@1");
    }

    @Test
    void shouldRecordOneEntryWhenTheServersOfARolloutBecomeReadyTogether() throws Exception {
        var ready = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var reports = IntStream.range(0, 4)
                    .mapToObj(index -> CompletableFuture.runAsync(
                            () -> {
                                try {
                                    ready.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                server("1.4.0", 4).onApplicationReady();
                            },
                            executor))
                    .toArray(CompletableFuture[]::new);
            ready.countDown();
            CompletableFuture.allOf(reports).get(30, TimeUnit.SECONDS);
        }

        assertThat(history()).containsExactly("1.4.0@4");
    }

    @Test
    void shouldKeepLaterRolloutsAboveAServerStartThatCouldNotReadItsRollout() {
        server("1.2.3", 1).onApplicationReady();
        // The API access is gone for a while: a server starts without a revision, and revision 2 runs unread.
        outsideKubernetes("1.2.4").onApplicationReady();
        var restored = server("1.3.0", 3);
        restored.onApplicationReady();
        server("1.4.0", 4).onApplicationReady();

        assertThat(history()).containsExactly("1.4.0@4", "1.3.0@3", "1.2.4@null", "1.2.3@1");
        var current = server("1.4.0", 4);
        current.onApplicationReady();
        assertThat(current.runningSince(entries())).isNotNull();
        assertThat(restored.runningSince(entries())).isNull();
    }

    @Test
    void shouldKeepTheNewestRolloutAboveAServerStartRecordedAfterIt() {
        var current = server("1.3.0", 3);
        current.onApplicationReady();
        // A server without the Kubernetes API starts later. It cannot tell whether revision 3 still runs.
        outsideKubernetes("1.2.4").onApplicationReady();
        // The next read of revision 3 finds its row and leaves its time as it was.
        current.report();

        assertThat(history()).containsExactly("1.3.0@3", "1.2.4@null");
        assertThat(current.runningSince(entries())).isNotNull();
    }

    @Test
    void shouldRecordEachRevisionThatOneServerReadsWhileItsPodStays() {
        // `kubectl rollout undo` to a ReplicaSet that never scaled down revises it under its running Pods.
        var revision = new AtomicLong(2);
        RolloutReader reader = () -> new Rollout(cluster, revision.get());
        var server = new ReleaseHistory(
                running("1.2.3", deployment),
                repository,
                transaction,
                environment,
                new StaticListableBeanFactory(Map.of("rolloutReader", reader)).getBeanProvider(RolloutReader.class));
        server.onApplicationReady();
        server("1.3.0", 3).onApplicationReady();
        revision.set(4);
        server.report();

        assertThat(history()).containsExactly("1.2.3@4", "1.3.0@3", "1.2.3@2");
        assertThat(server.runningSince(entries())).isNotNull();
    }

    @Test
    void shouldRecordEachServerStartOutsideKubernetesOnceInTheOrderTheyStarted() {
        var first = outsideKubernetes("1.2.3");
        first.onApplicationReady();
        first.onApplicationReady();
        var second = outsideKubernetes("1.3.0");
        second.onApplicationReady();

        assertThat(history()).containsExactly("1.3.0@null", "1.2.3@null");
        assertThat(second.runningSince(entries())).isNotNull();
        assertThat(first.runningSince(entries())).isNull();
    }

    @Test
    void shouldKeepAHistoryPerDeploymentEnvironment() {
        server("1.2.3", 1).onApplicationReady();
        // A database copied from this environment into a preview keeps these rows; the preview records its own.
        server("1.3.0", cluster, 2, deployment + "-preview").onApplicationReady();

        assertThat(history()).containsExactly("1.2.3@1");
    }

    @Test
    void shouldKeepTheNewestRevisionWhileServerStartsFillTheRetention() {
        var scaledDown = server("1.3.0", 7);
        scaledDown.onApplicationReady();
        var recordedAt = entries().getFirst().getActivatedAt();
        // The Deployment is scaled to zero, and a server outside Kubernetes starts more often than the retention keeps.
        for (int index = 0; index < ReleaseHistory.KEPT_ACTIVATIONS; index++) {
            jdbc.update(
                    "INSERT INTO release_activation (environment, version, channel, activated_at)"
                            + " VALUES (?, '1.2.4', 'RELEASE', now() + make_interval(secs => ?))",
                    deployment,
                    index + 1);
        }
        outsideKubernetes("1.2.4").onApplicationReady();

        // The Deployment scales up again: revision 7 keeps its row and its time.
        server("1.3.0", 7).onApplicationReady();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_activation WHERE environment = ? AND revision = 7",
                        Long.class,
                        deployment))
                .isEqualTo(1);
        assertThat(entries().getFirst().getRevision()).isEqualTo(7L);
        assertThat(entries().getFirst().getActivatedAt()).isEqualTo(recordedAt);
    }

    @Test
    void shouldKeepTheNewestEntriesOfTheEnvironment() {
        for (int index = 0; index < ReleaseHistory.KEPT_ACTIVATIONS + 5; index++) {
            jdbc.update(
                    "INSERT INTO release_activation (environment, deployment_uid, revision, version, channel,"
                            + " activated_at) VALUES (?, ?, ?, '1.0.0', 'RELEASE', now() - make_interval(hours => ?))",
                    deployment,
                    cluster,
                    index + 1,
                    1000 - index);
        }

        server("1.3.0", ReleaseHistory.KEPT_ACTIVATIONS + 10).onApplicationReady();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_activation WHERE environment = ?", Long.class, deployment))
                .isEqualTo(ReleaseHistory.KEPT_ACTIVATIONS);
        // The oldest entries went; the one just recorded stays.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_activation WHERE environment = ? AND revision <= 6",
                        Long.class,
                        deployment))
                .isZero();
        assertThat(history().getFirst()).isEqualTo("1.3.0@" + (ReleaseHistory.KEPT_ACTIVATIONS + 10));
    }
}
