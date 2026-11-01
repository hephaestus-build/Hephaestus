package de.tum.cit.aet.hephaestus.agent.mentor.loop;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.mentor.context.ConversationContextDocuments;
import de.tum.cit.aet.hephaestus.agent.mentor.context.TurnAttempt;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.mentor.MentorContextPathBuilder;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnAttempts;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStore;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.ObjectMapper;

/** Builds the worker-side Heph beans the test context leaves out, because the worker role is off there. */
final class ConversationTurnFixtures {

    private ConversationTurnFixtures() {}

    /** The worker's turn contexts, from beans that exist without the worker role. */
    static ConversationTurnContexts contexts(ApplicationContext beans) {
        return contexts(beans, beans.getBean(ResultHistoryQuery.class), beans.getBean(FeedbackStore.class));
    }

    /** The worker's turn contexts with the results and the feedback store a test chooses. */
    static ConversationTurnContexts contexts(
            ApplicationContext beans, ResultHistoryQuery results, FeedbackStore feedback) {
        return new ConversationTurnContexts(
                beans.getBean(MentorContextPathBuilder.class),
                // Created through the factory, so its transaction proxy applies as on a worker.
                beans.getAutowireCapableBeanFactory().createBean(ConversationSituation.class),
                beans.getBean(MentorTurnService.class),
                beans.getBean(MentorTurnAttempts.class),
                beans.getBean(MentorEntryService.class),
                beans.getBean(MentorConversationService.class),
                beans.getBean(WorkspaceContextBuilder.class),
                results,
                feedback,
                beans.getBean(AccountDirectory.class),
                beans.getBean(ObjectMapper.class),
                beans.getBean(Clock.class),
                documents(beans));
    }

    /** The captured documents of a turn, with the context loaders of the test context. */
    static ConversationContextDocuments documents(ApplicationContext beans) {
        return new ConversationContextDocuments(beans.getBeanProvider(ContextLoader.class));
    }

    /** The attempt's token as the gateway filter hands it to the controller. */
    static Authentication authenticated(TurnAttempt turn) {
        var authentication = new TestingAuthenticationToken("mentor-turn:" + turn.turnId(), "[REDACTED]", List.of());
        Instant now = Instant.now();
        authentication.setDetails(new AttemptJwt(
                AttemptJwt.Kind.TURN,
                turn.turnId(),
                turn.workspaceId(),
                turn.attempt(),
                turn.leaseId(),
                Set.of(AttemptTokenIssuer.LLM_PROXY_SCOPE),
                "jti",
                now,
                now.plusSeconds(300),
                null));
        return authentication;
    }
}
