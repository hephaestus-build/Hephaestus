package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.ConversationToolsPodRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.ConversationToolsPods;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.FakeToolsPods;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextFolder;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextGenerations;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.mentor.MentorContextPathBuilder;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnProgress;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Builds the worker-side Heph beans the test context leaves out, because the worker role is off there. */
final class HephTurnFixtures {

    private HephTurnFixtures() {}

    /** The worker's turn contexts, from beans that exist without the worker role. */
    static HephTurnContexts contexts(ApplicationContext beans) {
        return contexts(beans, beans.getBean(ResultHistoryQuery.class), beans.getBean(ConversationClaims.class));
    }

    /** The worker's turn contexts with the results and the feedback store a test chooses. */
    static HephTurnContexts contexts(
            ApplicationContext beans, ResultHistoryQuery results, ConversationClaims feedback) {
        return contexts(beans, results, feedback, folder(beans, toolsPods(beans, new FakeToolsPods())));
    }

    /** The worker's turn contexts with the context folder a test chooses. */
    static HephTurnContexts contexts(ApplicationContext beans, TurnContextFolder folder) {
        return contexts(
                beans, beans.getBean(ResultHistoryQuery.class), beans.getBean(ConversationClaims.class), folder);
    }

    /** The installation's tools Pods on this runtime, in the test database, with no pool. */
    static ConversationToolsPods toolsPods(ApplicationContext beans, ToolsPods runtime) {
        return new ConversationToolsPods(
                runtime,
                beans.getBean(ConversationToolsPodRepository.class),
                beans.getBean(TransactionTemplate.class),
                0,
                new SimpleMeterRegistry());
    }

    /** A context folder on these tools Pods, with the context loaders of the test context. */
    static TurnContextFolder folder(ApplicationContext beans, ConversationToolsPods toolsPods) {
        return new TurnContextFolder(
                toolsPods,
                beans.getBean(MentorTurnRepository.class),
                beans.getBeanProvider(ContextLoader.class),
                beans.getBean(Clock.class));
    }

    /**
     * A worker's turns, from beans that exist without the worker role. Its starter admits every turn with the test
     * catalog's model. It subscribes to the supplied ledger notifications when constructed.
     *
     * @param cap the most turns that run at once in the installation
     */
    static HephTurns turns(
            ApplicationContext beans,
            MentorLedgerNotifications notifications,
            ProxyCallsInFlight callsInFlight,
            ConversationToolsPods toolsPods,
            AttemptTokenIssuer issuer,
            Clock clock,
            MeterRegistry meters,
            int cap,
            Duration pullWait) {
        MentorTurnService turns = beans.getBean(MentorTurnService.class);
        return new HephTurns(
                MentorTurnStarts.admitting(beans, beans.getBean(MeterRegistry.class), cap),
                turns,
                beans.getBean(MentorTurnRepository.class),
                beans.getBean(MentorRedaction.class),
                beans.getBean(MentorTurnCompletion.class),
                beans.getBean(MentorTurnProgress.class),
                notifications,
                callsInFlight,
                toolsPods,
                issuer,
                beans.getBean(TurnRunsAdapter.class),
                clock,
                meters,
                pullWait);
    }

    private static HephTurnContexts contexts(
            ApplicationContext beans,
            ResultHistoryQuery results,
            ConversationClaims feedback,
            TurnContextFolder folder) {
        return contexts(beans, results, feedback, folder, new TurnContextGenerations());
    }

    /** The worker's turn contexts with the context generations a test changes. */
    static HephTurnContexts contexts(ApplicationContext beans, TurnContextGenerations generations) {
        return contexts(
                beans,
                beans.getBean(ResultHistoryQuery.class),
                beans.getBean(ConversationClaims.class),
                folder(beans, toolsPods(beans, new FakeToolsPods())),
                generations);
    }

    private static HephTurnContexts contexts(
            ApplicationContext beans,
            ResultHistoryQuery results,
            ConversationClaims feedback,
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

    /** The attempt's token as the gateway filter hands it to the controller, with five minutes of work left. */
    static Authentication authenticated(TurnAttempt turn) {
        return authenticated(turn, Instant.now().plusSeconds(300));
    }

    /** The attempt's token as the gateway filter hands it to the controller, whose work ends at {@code deadline}. */
    static Authentication authenticated(TurnAttempt turn, Instant deadline) {
        return authenticated(token(turn, deadline));
    }

    /** The attempt's verified token, whose work ends at {@code deadline}. */
    static AttemptJwt token(TurnAttempt turn, Instant deadline) {
        Instant now = Instant.now();
        // A token's work never ends before it was issued.
        Instant issued = deadline.isBefore(now) ? deadline.minusSeconds(60) : now;
        return new AttemptJwt(
                AttemptJwt.Kind.TURN,
                turn.turnId(),
                turn.workspaceId(),
                turn.attempt(),
                turn.runId(),
                Set.of(AttemptTokenIssuer.LLM_PROXY_SCOPE),
                "jti",
                issued,
                deadline.plus(TurnRunsAdapter.GRACE).plus(TurnRunsAdapter.GRACE),
                deadline);
    }

    /** The token as the gateway filter hands it to the controller. */
    static Authentication authenticated(AttemptJwt token) {
        var authentication = new TestingAuthenticationToken("mentor-turn:" + token.id(), "[REDACTED]", List.of());
        authentication.setDetails(token);
        return authentication;
    }
}
