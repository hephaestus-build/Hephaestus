package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Removes the mirrors under this process's root that their workspace no longer keeps. The server and each worker
 * hold their own root, so each one reconciles its own; a role that was offline catches up on its next pass. A
 * workspace keeps a mirror while it monitors the repository or still indexes it, as a stopped GitLab project is kept
 * until its work is removed. Repository removal and workspace purge therefore reach every role's mirrors.
 */
@Component
@ConditionalOnCaptureRole
@WorkspaceAgnostic("Walks every workspace's mirrors; each check filters on the mirror's own workspace")
@RequiredArgsConstructor
public class GitMirrorRetention {
    private static final Logger log = LoggerFactory.getLogger(GitMirrorRetention.class);

    /** A mirror whose lock a fetch holds longer than this waits for the next pass. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(5);

    private final GitRepositoryManager mirrors;
    private final RepositoryRepository repositories;

    /** @return how many mirrors were deleted */
    public int removeUnretainedMirrors() {
        if (!mirrors.isEnabled()) return 0;
        int removed = 0;
        for (RepositoryKey repository : mirrors.localRepositories()) {
            try {
                // Most mirrors are kept; check before waiting for a mirror lock that a long fetch may hold.
                if (isRetained(repository)) continue;
                if (mirrors.deleteUnretainedRepository(repository, LOCK_WAIT, () -> isRetained(repository))) removed++;
            } catch (RuntimeException e) {
                // One mirror that cannot be removed now must not keep the others; the next pass retries it.
                log.warn(
                        "Mirror removal failed: workspaceId={}, repositoryId={}",
                        repository.workspaceId(),
                        repository.repositoryId(),
                        e);
            }
        }
        return removed;
    }

    private boolean isRetained(RepositoryKey repository) {
        return repositories.isMonitored(repository.workspaceId(), repository.repositoryId())
                || repositories.isIndexedByWorkspace(repository.workspaceId(), repository.repositoryId());
    }

    /** Its own scheduler thread: the worker role does not enable {@code @Scheduled}, and a sweep must not wait on it. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnCaptureRole
    @Profile("!test & !specs & !cds-training")
    static class ScheduleConfiguration {
        @Bean
        ScheduledTaskRegistrar gitMirrorRetentionTasks(GitMirrorRetention retention) {
            var tasks = new ScheduledTaskRegistrar();
            tasks.addFixedDelayTask(new FixedDelayTask(
                    retention::removeUnretainedMirrors, Duration.ofMinutes(1), Duration.ofMinutes(1)));
            return tasks;
        }
    }
}
