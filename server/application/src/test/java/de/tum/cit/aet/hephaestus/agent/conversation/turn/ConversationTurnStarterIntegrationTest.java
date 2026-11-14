package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationLlmConfig;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.exception.ConversationRefusedException;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.DigestAdmission;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Admission runs when a conversation sandbox starts a turn, because a follow-up can wait while the member's choice changes. */
class ConversationTurnStarterIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnRepository turnRepository;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private PersonDataWriteFence writeFence;

    private final ConversationAdmission admission = mock(ConversationAdmission.class);
    private final LlmConnectionHealth connections = new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults());
    private Workspace workspace;
    private long accountId;
    private Conversation main;
    private ConversationTurnStarter starter;

    @BeforeEach
    void setUpStarter() {
        User developer = persistUser("ask-starter-developer");
        workspace = createWorkspace("ask-starter", "Hephaestus", "ask-org", AccountType.ORG, developer);
        accountId = accountId(developer);
        main = conversations.findOrCreateMain(workspace.getId(), accountId);
        turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "Question",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        starter = starter(metrics);
    }

    @Test
    void shouldFreezeTheAdmittedModelAndRatesOnTheAttemptWhenAdmissionAccepts() {
        when(admission.admit(workspace.getId(), accountId))
                .thenReturn(LlmCatalogTestFixtures.admittedConversationConfig());

        ConversationTurnStarter.StartedTurn started = ConversationTurnStarts.start(
                        beans, starter, workspace.getId(), main.getId())
                .orElseThrow();

        assertThat(started.accountId()).isEqualTo(accountId);
        ConversationTurn row = turnRepository.findById(started.turn().getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ConversationTurn.Status.RUNNING);
        assertThat(ConversationTurnAdmission.read(row.getAdmission())).hasValueSatisfying(admitted -> {
            assertThat(admitted.model()).isEqualTo("test-model");
            assertThat(admitted.price())
                    .isEqualTo(Objects.requireNonNull(
                            LlmCatalogTestFixtures.admittedConversationConfig().priceSnapshot()));
        });
        assertThat(ConversationTurnAdmission.read(row.getAdmission())
                        .orElseThrow()
                        .price()
                        .per1mInputUsd())
                .isEqualByComparingTo("10");
    }

    @Test
    void shouldFailTheTurnWithoutStartingItWhenTheModelsContextWindowIsTooSmallForHephaestus() {
        ConversationLlmConfig admitted = LlmCatalogTestFixtures.admittedConversationConfig();
        when(admission.admit(workspace.getId(), accountId))
                .thenReturn(new ConversationLlmConfig(
                        admitted.apiProtocol(),
                        admitted.baseUrl(),
                        admitted.upstreamModelId(),
                        10_000,
                        admitted.maxOutputTokens(),
                        admitted.reasoningEffort(),
                        admitted.connectionScope(),
                        admitted.connectionId(),
                        admitted.modelId(),
                        admitted.workspaceId(),
                        admitted.priceSnapshot(),
                        admitted.timeoutSeconds()));

        assertThat(ConversationTurnStarts.start(beans, starter, workspace.getId(), main.getId()))
                .isEmpty();

        ConversationTurn row = failedTurn();
        assertThat(row.getError()).isEqualTo(ConversationTurnStarter.MODEL_TOO_SMALL);
        assertThat(turns.isHeld(row)).isFalse();
    }

    @Test
    void shouldFailTheTurnWithoutStartingItWhenItsProviderConnectionIsPaused() {
        ConversationLlmConfig admitted = LlmCatalogTestFixtures.admittedConversationConfig();
        when(admission.admit(workspace.getId(), accountId)).thenReturn(admitted);
        for (int call = 0; call < 3; call++) {
            Objects.requireNonNull(connections.acquire(admitted.connectionScope(), admitted.connectionId()))
                    .failed(new IllegalStateException("upstream stalled"));
        }

        assertThat(ConversationTurnStarts.start(beans, starter, workspace.getId(), main.getId()))
                .isEmpty();

        assertThat(failedTurn().getError()).isEqualTo(ConversationTurnStarter.PROVIDER_PAUSED);
    }

    @Test
    void shouldTimeHowLongTheQuestionWaitedWhenItsFirstAttemptStarts() {
        when(admission.admit(workspace.getId(), accountId))
                .thenReturn(LlmCatalogTestFixtures.admittedConversationConfig());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        ConversationTurnStarts.start(beans, starter(meters), workspace.getId(), main.getId());

        assertThat(meters.get(AgentMetrics.CONVERSATION_TURN_QUEUE_WAIT).timer().count())
                .isEqualTo(1);
    }

    @Test
    void shouldFailTheTurnWithTheReasonWhenAdmissionDeclinesTheDeveloper() {
        when(admission.admit(workspace.getId(), accountId))
                .thenThrow(new ConversationRefusedException(ConversationRefusal.NO_AI));

        ConversationTurnStarts.start(beans, starter, workspace.getId(), main.getId());

        ConversationTurn row = failedTurn();
        assertThat(row.getError()).isEqualTo(ConversationRefusal.NO_AI.userMessage());
        assertThat(turns.isHeld(row)).isFalse();
    }

    private ConversationTurnStarter starter(MeterRegistry meters) {
        return new ConversationTurnStarter(
                turns,
                admission,
                beans.getBean(DigestAdmission.class),
                turnRepository,
                writeFence,
                meters,
                connections);
    }

    private ConversationTurn failedTurn() {
        return turnRepository
                .findByWorkspaceIdAndConversationIdAndStatus(
                        workspace.getId(), main.getId(), ConversationTurn.Status.FAILED)
                .getFirst();
    }
}
