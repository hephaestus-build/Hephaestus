package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MentorItemResources;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalDeliveryReconciler;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.TurnJwt;
import de.tum.cit.aet.hephaestus.mentor.MentorContextPathBuilder;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import java.util.List;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.ObjectMapper;

/** Builds the worker-side Heph beans the test context leaves out, because the worker role is off there. */
final class HephTurnFixtures {

    private HephTurnFixtures() {}

    /** The worker's turn contexts, from beans that exist without the worker role. */
    static HephTurnContexts contexts(ApplicationContext beans) {
        return new HephTurnContexts(
                beans.getBean(MentorContextPathBuilder.class),
                // Created through the factory, so its transaction proxy applies as on a worker.
                beans.getAutowireCapableBeanFactory().createBean(HephSituation.class),
                beans.getBean(MentorTurnService.class),
                beans.getBean(MentorEntryService.class),
                beans.getBean(MentorConversationService.class),
                beans.getBean(WorkspaceContextBuilder.class),
                beans.getBean(MentorItemResources.class),
                beans.getBean(ConversationalDeliveryReconciler.class),
                beans.getBean(AccountPreferencesQuery.class),
                beans.getBean(ObjectMapper.class));
    }

    /** The credential as the gateway filter hands it to the controller. */
    static Authentication authenticated(TurnJwt turn) {
        var authentication = new TestingAuthenticationToken("mentor-turn:" + turn.turnId(), "[REDACTED]", List.of());
        authentication.setDetails(turn);
        return authentication;
    }
}
