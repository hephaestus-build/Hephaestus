package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.DigestAdmission;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.conversation.ConversationRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
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

/** Starts Hephaestus turns in a test as a worker does, with an admission that admits every turn on the test model. */
public final class ConversationTurnStarts {

    private ConversationTurnStarts() {}

    /** A starter that admits every turn on the test model. */
    public static ConversationTurnStarter admitting(ApplicationContext beans, MeterRegistry metrics) {
        return admitting(beans, metrics, beans.getBean(DigestAdmission.class));
    }

    /** A starter with a local digest policy, without another Spring application context. */
    public static ConversationTurnStarter admitting(
            ApplicationContext beans, MeterRegistry metrics, DigestAdmission digests) {
        ConversationAdmission admission = mock(ConversationAdmission.class);
        when(admission.admit(anyLong(), anyLong())).thenReturn(LlmCatalogTestFixtures.admittedConversationConfig());
        return new ConversationTurnStarter(
                beans.getBean(ConversationTurnService.class),
                admission,
                digests,
                beans.getBean(ConversationTurnRepository.class),
                beans.getBean(PersonDataWriteFence.class),
                metrics,
                new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()));
    }

    /** Claims, admits and bounds this fixture's subject in one transaction, as its sandbox's pull does. */
    public static Optional<ConversationTurnStarter.StartedTurn> start(
            ApplicationContext beans, ConversationTurnStarter starter, long workspaceId, UUID conversationId) {
        return Objects.requireNonNull(beans.getBean(TransactionTemplate.class).execute(status -> {
            var account = beans.getBean(ConversationRepository.class)
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
    public static ConversationTurn askAndStart(
            ApplicationContext beans, MeterRegistry metrics, long workspaceId, UUID conversationId, String question) {
        String requestId = UUID.randomUUID().toString();
        beans.getBean(ConversationTurnService.class)
                .submit(
                        workspaceId,
                        conversationId,
                        new ConversationTurnService.Question(
                                ConversationTurn.Surface.APP, question, requestId, null, null));
        ConversationTurn started = start(beans, admitting(beans, metrics), workspaceId, conversationId)
                .orElseThrow()
                .turn();
        if (!started.getRequestId().equals(requestId)) {
            throw new IllegalStateException("Another queued turn started first");
        }
        return started;
    }
}
