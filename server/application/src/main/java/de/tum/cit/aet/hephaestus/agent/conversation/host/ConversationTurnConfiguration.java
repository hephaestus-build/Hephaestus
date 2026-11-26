package de.tum.cit.aet.hephaestus.agent.conversation.host;

import de.tum.cit.aet.hephaestus.agent.conversation.sandbox.TurnContextLoads;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunAuthority;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationAccess;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
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
            ConversationTurnAccess turns,
            ObjectProvider<ContextLoader> loaders,
            AgentRuns runs,
            PersonDataCopyFence copies,
            FabricLayout layout,
            AgentRunAuthority authority) {
        return new TurnContextLoads(turns, loaders, runs::conversationDeadlineClock, copies, layout, authority);
    }

    @Bean
    ConversationTurns conversationTurns(
            ConversationTurnAccess rows,
            ConversationAccess conversations,
            PersonDataWriteFence writeFence,
            PersonDataCopyFence copyFence,
            AgentRuns runs,
            ConversationTurnService turns,
            AgentSandboxes sandboxes,
            TransactionTemplate transactions) {
        return new ConversationTurns(rows, conversations, writeFence, copyFence, runs, turns, sandboxes, transactions);
    }
}
