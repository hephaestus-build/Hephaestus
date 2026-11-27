package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.conversation.ErasedDigestTurns;
import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.ApplicationContext;

/** Uses the real run rows without the test context's installation-wide one-turn cap. */
public final class AgentRunsFixtures {
    private AgentRunsFixtures() {}

    public static AgentRuns withoutTurnCap(ApplicationContext beans) {
        var capacity = beans.getBean(AgentRuns.class).capacity();
        return new AgentRuns(
                beans.getBean(AgentRunRepository.class),
                new AgentCapacityProperties(
                        capacity.leaseTtl(),
                        capacity.turnLeaseTtl(),
                        null,
                        capacity.leaseRenewal(),
                        capacity.reviewRetries(),
                        capacity.bandAging(),
                        capacity.permitAging(),
                        capacity.sandboxStart(),
                        capacity.sandboxIdle(),
                        capacity.conversationSandboxIdle(),
                        capacity.modelOutageWindow()),
                new SimpleMeterRegistry(),
                beans.getBean(AuthorityGenerations.class),
                beans.getBean(AgentRunTiming.class),
                beans.getBean(ErasedDigestTurns.class));
    }
}
