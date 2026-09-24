package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@Profile("!specs & !cds-training")
@RequiredArgsConstructor
@WorkspaceAgnostic("Survey reminders and summaries are requested by the instance for currently eligible accounts")
class SurveyEmailSchedulingJob {
    private final SurveyEmailInvitationService invitations;

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @SchedulerLock(name = "survey-email-scheduling", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void schedule() {
        invitations.scheduleReminders();
        invitations.scheduleSummaries();
    }
}
