package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextLoads;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorConversationAccess;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorTurnAccess;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

/** The conversation sandbox's context and turn routes on the gateway. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAgentRuntime
@Profile(HephTurnConfiguration.PROFILES)
public class HephTurnConfiguration {
    static final String PROFILES = "!specs & !cds-training";

    @Bean
    TurnContextLoads turnContextLoads(
            MentorTurnAccess turns,
            ObjectProvider<ContextLoader> loaders,
            Clock clock,
            PersonDataCopyFence copies,
            FabricLayout layout) {
        return new TurnContextLoads(turns, loaders, clock, copies, layout);
    }

    @Bean
    HephTurns hephTurns(
            MentorTurnAccess rows,
            MentorConversationAccess conversations,
            PersonDataWriteFence writeFence,
            PersonDataCopyFence copyFence,
            Clock clock,
            AgentRuns runs,
            MentorTurnService turns,
            AgentSandboxes sandboxes,
            TransactionTemplate transactions) {
        return new HephTurns(rows, conversations, writeFence, copyFence, clock, runs, turns, sandboxes, transactions);
    }
}
