package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.exception.MentorRefusedException;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Admission runs when a worker starts a turn, because a follow-up can wait while the member's choice changes. */
class MentorTurnStarterIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String WORKER = "worker-starter";

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorConversationRepository conversationRepository;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MeterRegistry metrics;

    private final MentorAdmission admission = mock(MentorAdmission.class);
    private final LlmConnectionHealth connections = new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults());
    private Workspace workspace;
    private User developer;
    private MentorTurnStarter starter;

    @BeforeEach
    void setUpStarter() {
        developer = persistUser("heph-starter-developer");
        workspace = createWorkspace("heph-starter", "Heph", "heph-org", AccountType.ORG, developer);
        MentorConversation main =
                conversations.findOrCreateMain(workspace.getId(), developer.getId(), Set.of(developer.getId()));
        turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, "Question", UUID.randomUUID().toString(), null, null));
        starter = new MentorTurnStarter(turns, admission, metrics, connections);
    }

    @Test
    void shouldFreezeTheAdmittedModelAndRatesOnTheAttemptWhenAdmissionAccepts() {
        when(admission.admit(workspace.getId(), developer.getId()))
                .thenReturn(LlmCatalogTestFixtures.admittedMentorConfig());

        MentorTurnStarter.StartedTurn started =
                starter.startNext(WORKER, Duration.ofMinutes(5)).orElseThrow();

        assertThat(started.developerId()).isEqualTo(developer.getId());
        MentorTurn row = turnRepository.findById(started.turn().getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(MentorTurnAdmission.read(row.getAdmission())).hasValueSatisfying(admitted -> {
            assertThat(admitted.model()).isEqualTo("test-model");
            assertThat(admitted.price())
                    .isEqualTo(Objects.requireNonNull(
                            LlmCatalogTestFixtures.admittedMentorConfig().priceSnapshot()));
        });
    }

    @Test
    void shouldFailTheTurnWithoutStartingItWhenTheModelsContextWindowIsTooSmallForHeph() {
        MentorLlmConfig admitted = LlmCatalogTestFixtures.admittedMentorConfig();
        when(admission.admit(workspace.getId(), developer.getId()))
                .thenReturn(new MentorLlmConfig(
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

        assertThat(starter.startNext(WORKER, Duration.ofMinutes(5))).isEmpty();

        MentorTurn row = failedTurn();
        assertThat(row.getError()).isEqualTo(MentorTurnStarter.MODEL_TOO_SMALL);
        assertThat(row.isLeased()).isFalse();
    }

    @Test
    void shouldFailTheTurnWithoutStartingItWhenItsProviderConnectionIsPaused() {
        MentorLlmConfig admitted = LlmCatalogTestFixtures.admittedMentorConfig();
        when(admission.admit(workspace.getId(), developer.getId())).thenReturn(admitted);
        for (int call = 0; call < 3; call++) {
            Objects.requireNonNull(connections.acquire(admitted.connectionScope(), admitted.connectionId()))
                    .failed(new IllegalStateException("upstream stalled"));
        }

        assertThat(starter.startNext(WORKER, Duration.ofMinutes(5))).isEmpty();

        assertThat(failedTurn().getError()).isEqualTo(MentorTurnStarter.PROVIDER_PAUSED);
    }

    @Test
    void shouldTimeHowLongTheQuestionWaitedWhenItsFirstAttemptStarts() {
        when(admission.admit(workspace.getId(), developer.getId()))
                .thenReturn(LlmCatalogTestFixtures.admittedMentorConfig());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        new MentorTurnStarter(turns, admission, meters, connections).startNext(WORKER, Duration.ofMinutes(5));

        assertThat(meters.get(AgentMetrics.MENTOR_TURN_QUEUE_WAIT).timer().count())
                .isEqualTo(1);
    }

    @Test
    void shouldFailTheTurnWithTheReasonWhenAdmissionDeclinesTheDeveloper() {
        when(admission.admit(workspace.getId(), developer.getId()))
                .thenThrow(new MentorRefusedException(MentorRefusal.NO_AI));

        starter.startNext(WORKER, Duration.ofMinutes(5));

        MentorTurn row = failedTurn();
        assertThat(row.getError()).isEqualTo(MentorRefusal.NO_AI.userMessage());
        assertThat(row.isLeased()).isFalse();
    }

    private MentorTurn failedTurn() {
        return turnRepository
                .findByWorkspaceIdAndConversationIdAndStatus(
                        workspace.getId(),
                        conversationRepository
                                .findMainsByWorkspaceIdAndUserIdIn(workspace.getId(), Set.of(developer.getId()))
                                .getFirst()
                                .getId(),
                        MentorTurn.Status.FAILED)
                .getFirst();
    }
}
