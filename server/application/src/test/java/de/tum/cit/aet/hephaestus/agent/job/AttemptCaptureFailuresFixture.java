package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** The admission's capture-failure writer for a test context that runs without the agent runtime. */
public final class AttemptCaptureFailuresFixture {
    private AttemptCaptureFailuresFixture() {}

    public static AttemptCaptureFailures from(ApplicationContext context) {
        return new AttemptCaptureFailures(
                context.getBean(ReviewJobRepository.class),
                context.getBean(AgentRuns.class),
                context.getBean(ReviewJobTelemetry.class),
                context.getBean(ObjectMapper.class),
                context.getBean(PlatformTransactionManager.class));
    }
}
