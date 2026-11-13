package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.Application;
import de.tum.cit.aet.hephaestus.agent.handler.AgentHandlerTestDoubles;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateNonceCleanupJob;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateNonceStore;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubWebhookRedelivery;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.testconfig.SharedTestDoubles;
import de.tum.cit.aet.hephaestus.testconfig.StubInstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.testconfig.TestAsyncConfiguration;
import de.tum.cit.aet.hephaestus.testconfig.TestSecurityConfig;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.ExtendedLockConfigurationExtractor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

/**
 * Two server replicas share one database. {@link ScheduledJobLockTest} holds every {@code @Scheduled} method to a
 * ShedLock or a stated reason; this test proves the locks: a second application context on the same database plays
 * the other replica.
 */
class ScheduledWorkAcrossReplicasIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OAuthStateNonceStore nonces;

    @Autowired
    private WorkspaceRepository workspaces;

    private static ConfigurableApplicationContext secondReplica;

    @Autowired
    private LockProvider firstLocks;

    @Autowired
    private ExtendedLockConfigurationExtractor extractor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OAuthStateNonceCleanupJob nonceCleanup;

    @Autowired
    private GitHubWebhookRedelivery redelivery;

    @BeforeAll
    static void startSecondReplica(@Autowired Environment first) {
        var application = new SpringApplication(
                Application.class,
                TestSecurityConfig.class,
                TestAsyncConfiguration.class,
                RecordingScmEventListener.class,
                StubInstallationRepositoryEnumerator.class,
                SharedTestDoubles.class,
                AgentHandlerTestDoubles.class);
        application.setAdditionalProfiles("test");
        secondReplica = application.run(
                "--spring.datasource.url=" + first.getRequiredProperty("spring.datasource.url"),
                "--spring.datasource.username=" + first.getRequiredProperty("spring.datasource.username"),
                "--spring.datasource.password=" + first.getRequiredProperty("spring.datasource.password"),
                "--spring.datasource.hikari.maximum-pool-size=4",
                "--server.port=0",
                "--management.server.port=0",
                "--hephaestus.llm.display-currency=",
                "--spring.main.banner-mode=off");
    }

    @AfterAll
    static void stopSecondReplica() {
        if (secondReplica != null) {
            secondReplica.close();
        }
    }

    /**
     * The lock each job's annotation configures, held by one context, keeps the other context out, and its row
     * stays held for at least the job's floor after the run. The floor is read from the row, not waited for.
     */
    @Test
    void shouldHoldEveryJobLockAgainstTheOtherContextForAtLeastItsFloor() {
        LockProvider secondLocks = secondReplica.getBean(LockProvider.class);
        List<String> names = new ArrayList<>();
        try {
            for (var job : ScheduledJobLockTest.scheduledJobs().entrySet()) {
                if (!job.getValue().isAnnotationPresent(SchedulerLock.class)) {
                    continue;
                }
                LockConfiguration annotated = extractor
                        .getLockConfiguration(new ScheduledMethodRunnable(new Object(), job.getValue()))
                        .orElseThrow();
                assertThat(annotated.getLockAtLeastFor())
                        .as("%s keeps its lock past the other replica's tick", job.getKey())
                        .isGreaterThanOrEqualTo(Duration.ofSeconds(1))
                        .isLessThan(annotated.getLockAtMostFor());
                // The real durations under a fresh name, so locks that other tests left behind do not interfere.
                String name =
                        annotated.getName() + "-" + UUID.randomUUID().toString().substring(0, 8);
                names.add(name);
                var tick = new LockConfiguration(
                        Instant.now(), name, annotated.getLockAtMostFor(), annotated.getLockAtLeastFor());

                SimpleLock held = firstLocks.lock(tick).orElseThrow();
                assertThat(secondLocks.lock(tick))
                        .as("%s while the first context runs it", job.getKey())
                        .isEmpty();
                held.unlock();

                Double floor = jdbc.queryForObject(
                        "SELECT EXTRACT(EPOCH FROM lock_until - locked_at) FROM shedlock WHERE name = ?",
                        Double.class,
                        name);
                assertThat(floor)
                        .as("%s stays locked for its floor after the run", job.getKey())
                        .isGreaterThanOrEqualTo(annotated.getLockAtLeastFor().toMillis() / 1000.0);
            }
        } finally {
            names.forEach(name -> jdbc.update("DELETE FROM shedlock WHERE name = ?", name));
        }
        assertThat(names).isNotEmpty();
    }

    /** The real proxied job of one context, while the other context holds its lock, does not run its body. */
    @Test
    void shouldSkipARealJobWhileTheOtherContextHoldsItsLock() {
        String name = "oauth-state-nonce-cleanup";
        String nonce = UUID.randomUUID().toString().substring(0, 32);
        LockProvider secondLocks = secondReplica.getBean(LockProvider.class);
        try {
            jdbc.update("DELETE FROM shedlock WHERE name = ?", name);
            // Far past any retention, so the job deletes it whenever its body runs.
            var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                    "nonce-job-" + UUID.randomUUID().toString().substring(0, 8)));
            nonces.issue(
                    nonce,
                    workspace.getId(),
                    IntegrationKind.GITHUB,
                    Instant.now().minus(Duration.ofDays(3650)));
            SimpleLock held = secondLocks
                    .lock(new LockConfiguration(Instant.now(), name, Duration.ofMinutes(1), Duration.ZERO))
                    .orElseThrow();

            nonceCleanup.cleanupExpired();

            assertThat(nonceExists(nonce))
                    .as("the first context skipped the run")
                    .isTrue();
            held.unlock();

            nonceCleanup.cleanupExpired();

            assertThat(nonceExists(nonce))
                    .as("the first context ran it once the lock was free")
                    .isFalse();
        } finally {
            jdbc.update("DELETE FROM oauth_state_nonce WHERE nonce = ?", nonce);
            jdbc.update("DELETE FROM shedlock WHERE name = ?", name);
        }
    }

    /**
     * A GitHub redelivery run that starts while another replica's run holds the lock does not start its body, so
     * two runs never redeliver the same events. A run that starts takes the lock, which moves {@code locked_at}.
     */
    @Test
    void shouldSkipAGitHubRedeliveryRunWhileTheOtherContextHoldsItsLock() {
        String name = "github-webhook-redelivery";
        LockProvider secondLocks = secondReplica.getBean(LockProvider.class);
        try {
            jdbc.update("DELETE FROM shedlock WHERE name = ?", name);
            SimpleLock held = secondLocks
                    .lock(new LockConfiguration(Instant.now(), name, Duration.ofMinutes(1), Duration.ZERO))
                    .orElseThrow();
            Instant heldAt = lockedAt(name);

            redelivery.redeliverFailed();

            assertThat(lockedAt(name)).as("the run did not take the lock").isEqualTo(heldAt);
            held.unlock();

            redelivery.redeliverFailed();

            assertThat(lockedAt(name))
                    .as("the run took the lock once it was free")
                    .isAfter(heldAt);
        } finally {
            jdbc.update("DELETE FROM shedlock WHERE name = ?", name);
        }
    }

    private Instant lockedAt(String name) {
        return Objects.requireNonNull(
                        jdbc.queryForObject("SELECT locked_at FROM shedlock WHERE name = ?", Timestamp.class, name))
                .toInstant();
    }

    private boolean nonceExists(String nonce) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM oauth_state_nonce WHERE nonce = ?)", Boolean.class, nonce));
    }
}
