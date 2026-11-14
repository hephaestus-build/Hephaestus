package de.tum.cit.aet.hephaestus.agent.conversation.host;

import de.tum.cit.aet.hephaestus.agent.conversation.sandbox.TurnContextLoads;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.conversation.ConversationRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

/** The conversation sandbox's context and turn routes on the gateway. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAgentRuntime
@Profile(ConversationTurnConfiguration.PROFILES)
public class ConversationTurnConfiguration {
    static final String PROFILES = "!specs & !cds-training";

    @Bean
    TurnContextLoads turnContextLoads(
            ConversationTurnRepository turns,
            ObjectProvider<ContextLoader> loaders,
            Clock clock,
            PersonDataCopyFence copies,
            FabricLayout layout) {
        return new TurnContextLoads(turns, loaders, clock, copies, layout);
    }

    @Bean
    ConversationTurns conversationTurns(
            ConversationTurnRepository rows,
            ConversationRepository conversations,
            PersonDataWriteFence writeFence,
            PersonDataCopyFence copyFence,
            Clock clock,
            AgentRuns runs,
            ConversationTurnService turns,
            AgentSandboxes sandboxes,
            TransactionTemplate transactions) {
        return new ConversationTurns(
                rows, conversations, writeFence, copyFence, clock, runs, turns, sandboxes, transactions);
    }
}
