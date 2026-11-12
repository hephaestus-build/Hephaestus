package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextLoads;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.mentor.MentorContextPathBuilder;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import java.time.Clock;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Builds the gateway beans that the test context leaves out with its sandbox runtime. */
final class HephTurnFixtures {
    private HephTurnFixtures() {}

    static TurnContextLoads folder(ApplicationContext beans) {
        return new TurnContextLoads(
                beans.getBean(MentorTurnRepository.class),
                beans.getBeanProvider(ContextLoader.class),
                beans.getBean(Clock.class),
                beans.getBean(PersonDataCopyFence.class),
                beans.getBean(FabricLayout.class));
    }

    static HephTurns turns(ApplicationContext beans, AgentSandboxes sandboxes) {
        return turns(beans, sandboxes, beans.getBean(Clock.class));
    }

    static HephTurns turns(ApplicationContext beans, AgentSandboxes sandboxes, Clock clock) {
        return new HephTurns(
                beans.getBean(MentorTurnRepository.class),
                beans.getBean(MentorConversationRepository.class),
                beans.getBean(PersonDataWriteFence.class),
                beans.getBean(PersonDataCopyFence.class),
                clock,
                beans.getBean(AgentRuns.class),
                beans.getBean(MentorTurnService.class),
                sandboxes,
                beans.getBean(TransactionTemplate.class));
    }

    static HephTurnContexts contexts(ApplicationContext beans) {
        return contexts(beans, beans.getBean(ResultHistoryQuery.class), beans.getBean(ConversationClaims.class));
    }

    static HephTurnContexts contexts(
            ApplicationContext beans, ResultHistoryQuery results, ConversationClaims feedback) {
        TurnContextLoads folder = folder(beans);
        return new HephTurnContexts(
                beans.getBean(MentorContextPathBuilder.class),
                // Created through the factory, so its transaction proxy applies as on a worker.
                beans.getAutowireCapableBeanFactory().createBean(HephSituation.class),
                beans.getBean(MentorTurnService.class),
                beans.getBean(MentorEntryService.class),
                beans.getBean(MentorConversationService.class),
                beans.getBean(WorkspaceContextBuilder.class),
                results,
                feedback,
                beans.getBean(AccountDirectory.class),
                beans.getBean(ObjectMapper.class),
                beans.getBean(Clock.class),
                folder);
    }
}
