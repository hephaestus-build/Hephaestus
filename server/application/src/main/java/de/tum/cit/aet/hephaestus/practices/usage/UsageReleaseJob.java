package de.tum.cit.aet.hephaestus.practices.usage;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks each hour whether a half-year of the public usage page is due. A period is due twice a year, so most runs
 * read one row and stop. The hourly check catches up after downtime, and it makes the first release follow within an
 * hour of an instance admin turning the page on.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Releases instance totals over every workspace (ADR 0153)")
@RequiredArgsConstructor
class UsageReleaseJob {

    private final UsageReleaseService service;

    @Scheduled(cron = "0 17 * * * *")
    @SchedulerLock(name = "usage-release", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void release() {
        service.releaseDue();
    }
}
