package de.tum.cit.aet.hephaestus.agent.conversation.host;

import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.conversation.sandbox.TurnContextLoads;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.conversation.ConversationContextPathBuilder;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import java.time.Clock;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Builds the gateway beans that the test context leaves out with its sandbox runtime. */
final class ConversationTurnFixtures {
    private ConversationTurnFixtures() {}

    static TurnContextLoads folder(ApplicationContext beans) {
        return new TurnContextLoads(
                beans.getBean(ConversationTurnRepository.class),
                beans.getBeanProvider(ContextLoader.class),
                beans.getBean(Clock.class),
                beans.getBean(PersonDataCopyFence.class),
                beans.getBean(FabricLayout.class));
    }

    static ConversationTurns turns(ApplicationContext beans, AgentSandboxes sandboxes) {
        return turns(beans, sandboxes, beans.getBean(Clock.class));
    }

    static ConversationTurns turns(ApplicationContext beans, AgentSandboxes sandboxes, Clock clock) {
        return new ConversationTurns(
                beans.getBean(ConversationTurnRepository.class),
                beans.getBean(ConversationRepository.class),
                beans.getBean(PersonDataWriteFence.class),
                beans.getBean(PersonDataCopyFence.class),
                clock,
                beans.getBean(AgentRuns.class),
                beans.getBean(ConversationTurnService.class),
                sandboxes,
                beans.getBean(TransactionTemplate.class));
    }

    static ConversationTurnContexts contexts(ApplicationContext beans) {
        return contexts(beans, beans.getBean(ResultHistoryQuery.class), beans.getBean(ConversationClaims.class));
    }

    static ConversationTurnContexts contexts(
            ApplicationContext beans, ResultHistoryQuery results, ConversationClaims feedback) {
        TurnContextLoads folder = folder(beans);
        return new ConversationTurnContexts(
                beans.getBean(ConversationContextPathBuilder.class),
                // Created through the factory, so its transaction proxy applies as on a worker.
                beans.getAutowireCapableBeanFactory().createBean(TurnSituation.class),
                beans.getBean(ConversationTurnService.class),
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
