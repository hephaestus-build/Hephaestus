package de.tum.cit.aet.hephaestus.agent.conversation.host;

import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.conversation.sandbox.TurnContextLoads;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunAuthority;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.conversation.ConversationContextPathBuilder;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnContextStore;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationAccess;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.practices.spi.AssessmentPort;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import java.time.Clock;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Builds the gateway beans that the test context leaves out with its sandbox runtime. */
final class ConversationTurnFixtures {
    private ConversationTurnFixtures() {}

    static TurnContextLoads folder(ApplicationContext beans) {
        return new TurnContextLoads(
                beans.getBean(ConversationTurnAccess.class),
                beans.getBeanProvider(ContextLoader.class),
                beans.getBean(AgentRuns.class)::conversationDeadlineClock,
                beans.getBean(PersonDataCopyFence.class),
                beans.getBean(FabricLayout.class),
                beans.getBean(AgentRunAuthority.class));
    }

    static ConversationTurns turns(ApplicationContext beans, AgentSandboxes sandboxes) {
        return new ConversationTurns(
                beans.getBean(ConversationTurnAccess.class),
                beans.getBean(ConversationAccess.class),
                beans.getBean(PersonDataWriteFence.class),
                beans.getBean(PersonDataCopyFence.class),
                beans.getBean(AgentRuns.class),
                beans.getBean(ConversationTurnService.class),
                sandboxes,
                beans.getBean(TransactionTemplate.class));
    }

    static ConversationTurnContexts contexts(ApplicationContext beans) {
        return contexts(beans, beans.getBean(AssessmentPort.class), beans.getBean(ConversationClaims.class));
    }

    static ConversationTurnContexts contexts(
            ApplicationContext beans, AssessmentPort results, ConversationClaims feedback) {
        TurnContextLoads folder = folder(beans);
        return new ConversationTurnContexts(
                beans.getBean(ConversationContextPathBuilder.class),
                // Created through the factory, so its transaction proxy applies as on a worker.
                beans.getAutowireCapableBeanFactory().createBean(TurnSituation.class),
                beans.getBean(ConversationTurnContextStore.class),
                beans.getBean(ConversationEntryService.class),
                beans.getBean(ConversationService.class),
                beans.getBean(WorkspaceContextBuilder.class),
                results,
                feedback,
                beans.getBean(AccountDirectory.class),
                beans.getBean(ObjectMapper.class),
                beans.getBean(Clock.class),
                folder);
    }
}
