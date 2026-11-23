package de.tum.cit.aet.hephaestus.agent.gateway.testing;

import de.tum.cit.aet.hephaestus.agent.gateway.AgentNextWaits;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunNotifications;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import org.springframework.context.ApplicationContext;

/** The wait of {@code next} in a gateway of an isolated test context. */
public final class AgentNextWaitsFixtures {
    private AgentNextWaitsFixtures() {}

    /** Not started, so each {@code next} claims once and answers at once, as before requests waited. */
    public static AgentNextWaits immediate(ApplicationContext context) {
        return new AgentNextWaits(
                context.getBean(AgentRuns.class),
                context.getBean(AgentRunNotifications.class),
                new SandboxGatewayProperties(0, 1, 1, 1, 2));
    }
}
