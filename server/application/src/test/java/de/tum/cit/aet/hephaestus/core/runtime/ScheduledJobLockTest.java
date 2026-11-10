package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.Application;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ClassUtils;

/**
 * Two server replicas share one database. Every {@code @Scheduled} method either takes a ShedLock, so that only one
 * replica runs it at a time, or is listed below with the reason it is safe on every replica. The rule reads only the
 * compiled classes, so it runs with the architecture tests, in pull request CI and before a push.
 * {@link ScheduledWorkAcrossReplicasIntegrationTest} proves the locks between two contexts.
 */
@Tag("architecture")
class ScheduledJobLockTest {

    /**
     * Jobs that run on every replica on purpose. ROW: the job claims or locks its rows, so a second pass finds
     * nothing to do. LOCAL: the job acts only on this process's memory, gauges or streams.
     */
    private static final Map<String, String> UNLOCKED = Map.ofEntries(
            Map.entry("AgentQueueHealthSampler#sample", "LOCAL"),
            Map.entry("ActionExecutor#runDue", "ROW"),
            Map.entry("ActionBacklogSampler#sample", "LOCAL"),
            Map.entry("WorkspaceConsumerReconciler#reconcile", "LOCAL"),
            Map.entry("WalArchiveMonitor#sample", "LOCAL"),
            Map.entry("FeedbackDeliveryFreshness#sample", "LOCAL"),
            Map.entry("AgentRunReaper#reap", "ROW"),
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
            Map.entry("FeedbackSendRecovery#recover", "ROW"),
            Map.entry("FilingService#fileDue", "ROW"),
            Map.entry("FilingService#measureWaiting", "LOCAL"),
            Map.entry("FollowService#promoteDue", "ROW"),
            Map.entry("RoutingService#routeAnswered", "ROW"));

    private static final URL MAIN_CLASSES =
            Application.class.getProtectionDomain().getCodeSource().getLocation();

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

    /** Every {@code @Scheduled} method of the application's own classes, by class and method name. */
    static Map<String, Method> scheduledJobs() {
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
