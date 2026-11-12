package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Instance calls have an outbox claim and a deadline instead of a run's settlement trigger. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Recovery settles only instance draft calls, which belong to no workspace")
class InstanceCallRecovery {
    private static final String INTERVAL = "PT30S";

    private final LlmUsageRecorder recorder;

    @Scheduled(fixedDelayString = INTERVAL, initialDelayString = "PT10S")
    @SchedulerLock(name = "llm-instance-call-recovery", lockAtMostFor = "PT5M", lockAtLeastFor = INTERVAL)
    public void recover() {
        recorder.recoverInstanceCalls();
    }
}
