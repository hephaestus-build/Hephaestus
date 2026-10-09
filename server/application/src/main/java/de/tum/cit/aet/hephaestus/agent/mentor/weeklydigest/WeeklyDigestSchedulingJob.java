package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes due weekly digests. A developer is due on a Monday in their own time zone
 * ({@code WeeklyDigestComposer.isDue}), so the job runs all week and finds work only on the world's Mondays.
 */
@Component
@ConditionalOnServerRole
@Profile("!specs & !cds-training")
@RequiredArgsConstructor
@WorkspaceAgnostic("Weekly digests are composed per opted-in account across its workspaces")
class WeeklyDigestSchedulingJob {

    private final WeeklyDigestComposer composer;

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
    @SchedulerLock(name = "weekly-digest", lockAtMostFor = "PT14M", lockAtLeastFor = "PT1M")
    public void run() {
        composer.composeDue();
    }
}
