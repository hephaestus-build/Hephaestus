package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes old correction records. It runs whether the correction job is enabled or not, so retention always holds. */
@Component
@ConditionalOnServerRole
@Profile("!specs & !cds-training")
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance-wide retention of the correction record")
public class CommitIdentityChangeRetentionJob {

    static final Duration RETENTION = Duration.ofDays(90);

    private final CommitIdentityChangeRepository changes;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
    @SchedulerLock(name = "commit-identity-change-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void deleteExpired() {
        changes.deleteOlderThan(clock.instant().minus(RETENTION));
    }
}
