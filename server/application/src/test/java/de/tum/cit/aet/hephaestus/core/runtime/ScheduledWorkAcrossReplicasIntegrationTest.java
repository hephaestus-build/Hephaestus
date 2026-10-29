package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.Application;
import de.tum.cit.aet.hephaestus.agent.handler.AgentHandlerTestDoubles;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateNonceCleanupJob;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubWebhookRedelivery;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.testconfig.SharedTestDoubles;
import de.tum.cit.aet.hephaestus.testconfig.StubInstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.testconfig.TestAsyncConfiguration;
import de.tum.cit.aet.hephaestus.testconfig.TestSecurityConfig;
import java.lang.reflect.Method;
import java.net.URL;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
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
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.util.ClassUtils;

/**
 * Two server replicas share one database. Every {@code @Scheduled} method either takes a ShedLock, so that only one
 * replica runs it at a time, or is listed below with the reason it is safe on every replica. A second application
 * context on the same database plays the other replica.
 */
class ScheduledWorkAcrossReplicasIntegrationTest extends BaseIntegrationTest {

    /**
     * Jobs that run on every replica on purpose. ROW: the job claims or locks its rows, so a second pass finds
     * nothing to do. LOCAL: the job acts only on this process's memory, gauges or streams.
     */
    private static final Map<String, String> UNLOCKED = Map.ofEntries(
            Map.entry("AgentQueueHealthSampler#sample", "LOCAL"),
            Map.entry("WalArchiveMonitor#sample", "LOCAL"),
            Map.entry("ReviewJobZombieSweeper#reapStaleRunningJobs", "ROW"),
            Map.entry("ReviewJobZombieSweeper#recoverOrphanedJobs", "ROW"),
            Map.entry("ReviewJobZombieSweeper#purgeStaleWorkerRegistrations", "ROW"),
            Map.entry("MentorTurnQueueSampler#sample", "LOCAL"),
            Map.entry("MentorLiveHub#heartbeat", "LOCAL"),
            Map.entry("ContributorService#evictContributorsCache", "LOCAL"),
            Map.entry("ReleaseCheckService#poll", "LOCAL"),
            Map.entry("CredentialRotationService#rotateBatch", "ROW"),
            Map.entry("SyncJobService#refreshLeases", "LOCAL"),
            Map.entry("SyncJobService#flushBufferedProgress", "LOCAL"),
            Map.entry("SyncJobZombieSweeper#hourlySweep", "ROW"),
            Map.entry("SyncEventHub#sendHeartbeats", "LOCAL"),
            Map.entry("OutlineRateLimitTracker#evictStaleEntries", "LOCAL"),
            Map.entry("ScopedRateLimitTracker#evictStaleEntries", "LOCAL"),
            Map.entry("GitLabRateLimitTracker#evictStaleEntries", "LOCAL"),
            Map.entry("SlackRateLimitTracker#evictStaleEntries", "LOCAL"),
            Map.entry("ConversationFeedbackTtlSweeper#sweep", "ROW"),
            Map.entry("FeedbackSendRecovery#recover", "ROW"));

    private static final URL MAIN_CLASSES =
            Application.class.getProtectionDomain().getCodeSource().getLocation();

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
                "--hephaestus.features.flags.gitlab-workspace-creation=true",
                "--hephaestus.llm.display-currency=",
                "--spring.main.banner-mode=off");
    }

    @AfterAll
    static void stopSecondReplica() {
        if (secondReplica != null) {
            secondReplica.close();
        }
    }

    @Test
    void shouldLockOrJustifyEveryScheduledJob() {
        Map<String, Method> jobs = scheduledJobs();
        List<String> unclassified = jobs.entrySet().stream()
                .filter(job -> !job.getValue().isAnnotationPresent(SchedulerLock.class))
                .map(Map.Entry::getKey)
                .filter(id -> !UNLOCKED.containsKey(id))
                .sorted()
                .toList();
        List<String> stale = UNLOCKED.keySet().stream()
                .filter(id -> !jobs.containsKey(id) || jobs.get(id).isAnnotationPresent(SchedulerLock.class))
                .sorted()
                .toList();
        Map<String, Long> lockNames = jobs.values().stream()
                .map(method -> method.getAnnotation(SchedulerLock.class))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(SchedulerLock::name, Collectors.counting()));

        assertThat(unclassified)
                .as("scheduled jobs with neither a lock nor a reason")
                .isEmpty();
        assertThat(stale)
                .as("reasons for jobs that no longer exist or now hold a lock")
                .isEmpty();
        assertThat(lockNames)
                .as("lock names shared by two jobs")
                .allSatisfy((name, count) -> assertThat(count).isOne());
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
            for (var job : scheduledJobs().entrySet()) {
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
            jdbc.update(
                    "INSERT INTO oauth_state_nonce (nonce, workspace_id, kind, issued_at)"
                            + " VALUES (?, 0, 'GITHUB', now() - interval '10 years')",
                    nonce);
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

    /** Every {@code @Scheduled} method in the application, keyed {@code SimpleClassName#method}. */
    private static Map<String, Method> scheduledJobs() {
        // Overridden rather than filtered: the default check also drops beans whose conditions this profile turns off.
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) {
                return reader.getAnnotationMetadata().hasAnnotatedMethods(Scheduled.class.getName());
            }
        };
        Map<String, Method> jobs = new HashMap<>();
        for (var candidate : scanner.findCandidateComponents(Application.class.getPackageName())) {
            Class<?> type = ClassUtils.resolveClassName(Objects.requireNonNull(candidate.getBeanClassName()), null);
            if (!MAIN_CLASSES.equals(type.getProtectionDomain().getCodeSource().getLocation())) {
                continue;
            }
            MethodIntrospector.selectMethods(type, (MethodIntrospector.MetadataLookup<Boolean>) method ->
                            AnnotatedElementUtils.hasAnnotation(method, Scheduled.class) ? Boolean.TRUE : null)
                    .keySet()
                    .forEach(method -> jobs.put(type.getSimpleName() + "#" + method.getName(), method));
        }
        return jobs;
    }
}
