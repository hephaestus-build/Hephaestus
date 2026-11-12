package de.tum.cit.aet.hephaestus.agent.gateway.testing;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.gateway.AgentReviewDrain;
import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.context.ApplicationContext;

/** A gateway of an isolated test context uses its parent's real run rows and readiness. */
public final class AgentReviewDrainFixtures {
    private AgentReviewDrainFixtures() {}

    public static AgentReviewDrain started(ApplicationContext context) {
        var drain = new AgentReviewDrain(
                context.getBean(AgentRuns.class),
                context.getBean(AgentProperties.class),
                context.getBean(ApplicationAvailability.class),
                context.getBeanProvider(ToolsPods.class)
                        .getIfAvailable(() -> mock(ToolsPods.class, CALLS_REAL_METHODS)));
        drain.start();
        return drain;
    }
}
