package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.FakeExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextFolder;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextGenerations;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnSandboxes;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.AttemptJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.mentor.MentorContextPathBuilder;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
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
final class HephTurnFixtures {

    private HephTurnFixtures() {}

    /** The worker's turn contexts, from beans that exist without the worker role. */
    static HephTurnContexts contexts(ApplicationContext beans) {
        return contexts(beans, beans.getBean(ResultHistoryQuery.class), beans.getBean(FeedbackStore.class));
    }

    /** The worker's turn contexts with the results and the feedback store a test chooses. */
    static HephTurnContexts contexts(ApplicationContext beans, ResultHistoryQuery results, FeedbackStore feedback) {
        return contexts(beans, results, feedback, folder(beans, new TurnSandboxes(new FakeExecSandboxes(), 0)));
    }

    /** The worker's turn contexts with the context folder a test chooses. */
    static HephTurnContexts contexts(ApplicationContext beans, TurnContextFolder folder) {
        return contexts(beans, beans.getBean(ResultHistoryQuery.class), beans.getBean(FeedbackStore.class), folder);
    }

    /** A context folder on these sandboxes, with the context loaders of the test context. */
    static TurnContextFolder folder(ApplicationContext beans, TurnSandboxes sandboxes) {
        return new TurnContextFolder(sandboxes, beans.getBeanProvider(ContextLoader.class), beans.getBean(Clock.class));
    }

    private static HephTurnContexts contexts(
            ApplicationContext beans, ResultHistoryQuery results, FeedbackStore feedback, TurnContextFolder folder) {
        return contexts(beans, results, feedback, folder, new TurnContextGenerations());
    }

    /** The worker's turn contexts with the context generations a test changes. */
    static HephTurnContexts contexts(ApplicationContext beans, TurnContextGenerations generations) {
        return contexts(
                beans,
                beans.getBean(ResultHistoryQuery.class),
                beans.getBean(FeedbackStore.class),
                folder(beans, new TurnSandboxes(new FakeExecSandboxes(), 0)),
                generations);
    }

    private static HephTurnContexts contexts(
            ApplicationContext beans,
            ResultHistoryQuery results,
            FeedbackStore feedback,
            TurnContextFolder folder,
            TurnContextGenerations generations) {
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
                folder,
                generations);
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
                Set.of(WorkerJwtIssuer.LLM_PROXY_SCOPE),
                "jti",
                now,
                now.plusSeconds(300),
                null));
        return authentication;
    }
}
