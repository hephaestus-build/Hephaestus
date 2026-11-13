package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runless calls have a durable deadline or an outbox claim instead of a run's settlement trigger. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Recovery settles only runless draft and synthetic probe calls; it does not claim workspace work")
class InstanceCallRecovery {
    private static final String INTERVAL = "PT30S";

    private final LlmUsageRecorder recorder;

    @Scheduled(fixedDelayString = INTERVAL, initialDelayString = "PT10S")
    @SchedulerLock(name = "llm-instance-call-recovery", lockAtMostFor = "PT5M", lockAtLeastFor = INTERVAL)
    public void recover() {
        recorder.recoverInstanceCalls();
    }
}
