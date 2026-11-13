package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/** Starts Heph turns in a test as a worker does, with an admission that admits every turn on the test model. */
public final class MentorTurnStarts {

    private MentorTurnStarts() {}

    /** A starter that admits every turn on the test model. */
    public static MentorTurnStarter admitting(ApplicationContext beans, MeterRegistry metrics) {
        MentorAdmission admission = mock(MentorAdmission.class);
        when(admission.admit(anyLong(), anyLong())).thenReturn(LlmCatalogTestFixtures.admittedMentorConfig());
        return new MentorTurnStarter(
                beans.getBean(MentorTurnService.class),
                admission,
                beans.getBean(MentorTurnRepository.class),
                beans.getBean(PersonDataWriteFence.class),
                metrics,
                new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()));
    }

    /** Claims, admits and bounds this fixture's subject in one transaction, as its sandbox's pull does. */
    public static Optional<MentorTurnStarter.StartedTurn> start(
            ApplicationContext beans, MentorTurnStarter starter, long workspaceId, UUID conversationId) {
        return Objects.requireNonNull(beans.getBean(TransactionTemplate.class).execute(status -> {
            var account = beans.getBean(MentorConversationRepository.class)
                    .findAccountIdByIdAndWorkspaceId(conversationId, workspaceId);
            if (account.isEmpty() || !beans.getBean(PersonDataWriteFence.class).holdForAccountWrite(account.get()))
                return Optional.empty();
            return beans.getBean(AgentRuns.class)
                    .claimSubject(workspaceId, TurnRunsAdapter.subject(conversationId), Set.of(AgentRunKind.TURN))
                    .flatMap(lease -> starter.startClaimed(lease.fence()).map(started -> {
                        beans.getBean(TurnRunsAdapter.class)
                                .limit(
                                        workspaceId,
                                        started.turn().getId(),
                                        lease.runId(),
                                        lease.epoch(),
                                        beans.getBean(Clock.class)
                                                .instant()
                                                .plusSeconds(started.llm().timeoutSeconds()));
                        return started;
                    }));
        }));
    }

    /** Asks a question in the app and starts its turn. */
    public static MentorTurn askAndStart(
            ApplicationContext beans, MeterRegistry metrics, long workspaceId, UUID conversationId, String question) {
        String requestId = UUID.randomUUID().toString();
        beans.getBean(MentorTurnService.class)
                .submit(
                        workspaceId,
                        conversationId,
                        new MentorTurnService.Question(MentorTurn.Surface.APP, question, requestId, null, null));
        MentorTurn started = start(beans, admitting(beans, metrics), workspaceId, conversationId)
                .orElseThrow()
                .turn();
        if (!started.getRequestId().equals(requestId)) {
            throw new IllegalStateException("Another queued turn started first");
        }
        return started;
    }
}
