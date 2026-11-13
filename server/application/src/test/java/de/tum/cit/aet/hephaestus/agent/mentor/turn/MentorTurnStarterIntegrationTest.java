package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.exception.MentorRefusedException;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.DigestAdmission;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
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
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Admission runs when a conversation sandbox starts a turn, because a follow-up can wait while the member's choice changes. */
class MentorTurnStarterIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private PersonDataWriteFence writeFence;

    private final MentorAdmission admission = mock(MentorAdmission.class);
    private final LlmConnectionHealth connections = new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults());
    private Workspace workspace;
    private long accountId;
    private MentorConversation main;
    private MentorTurnStarter starter;

    @BeforeEach
    void setUpStarter() {
        User developer = persistUser("heph-starter-developer");
        workspace = createWorkspace("heph-starter", "Hephaestus", "heph-org", AccountType.ORG, developer);
        accountId = accountId(developer);
        main = conversations.findOrCreateMain(workspace.getId(), accountId);
        turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, "Question", UUID.randomUUID().toString(), null, null));
        starter = starter(metrics);
    }

    @Test
    void shouldFreezeTheAdmittedModelAndRatesOnTheAttemptWhenAdmissionAccepts() {
        when(admission.admit(workspace.getId(), accountId)).thenReturn(LlmCatalogTestFixtures.admittedMentorConfig());

        MentorTurnStarter.StartedTurn started = MentorTurnStarts.start(beans, starter, workspace.getId(), main.getId())
                .orElseThrow();

        assertThat(started.accountId()).isEqualTo(accountId);
        MentorTurn row = turnRepository.findById(started.turn().getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(MentorTurnAdmission.read(row.getAdmission())).hasValueSatisfying(admitted -> {
            assertThat(admitted.model()).isEqualTo("test-model");
            assertThat(admitted.price())
                    .isEqualTo(Objects.requireNonNull(
                            LlmCatalogTestFixtures.admittedMentorConfig().priceSnapshot()));
        });
        assertThat(MentorTurnAdmission.read(row.getAdmission())
                        .orElseThrow()
                        .price()
                        .per1mInputUsd())
                .isEqualByComparingTo("10");
    }

    @Test
    void shouldFailTheTurnWithoutStartingItWhenTheModelsContextWindowIsTooSmallForHeph() {
        MentorLlmConfig admitted = LlmCatalogTestFixtures.admittedMentorConfig();
        when(admission.admit(workspace.getId(), accountId))
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

        assertThat(MentorTurnStarts.start(beans, starter, workspace.getId(), main.getId()))
                .isEmpty();

        MentorTurn row = failedTurn();
        assertThat(row.getError()).isEqualTo(MentorTurnStarter.MODEL_TOO_SMALL);
        assertThat(turns.isHeld(row)).isFalse();
    }

    @Test
    void shouldFailTheTurnWithoutStartingItWhenItsProviderConnectionIsPaused() {
        MentorLlmConfig admitted = LlmCatalogTestFixtures.admittedMentorConfig();
        when(admission.admit(workspace.getId(), accountId)).thenReturn(admitted);
        for (int call = 0; call < 3; call++) {
            Objects.requireNonNull(connections.acquire(admitted.connectionScope(), admitted.connectionId()))
                    .failed(new IllegalStateException("upstream stalled"));
        }

        assertThat(MentorTurnStarts.start(beans, starter, workspace.getId(), main.getId()))
                .isEmpty();

        assertThat(failedTurn().getError()).isEqualTo(MentorTurnStarter.PROVIDER_PAUSED);
    }

    @Test
    void shouldTimeHowLongTheQuestionWaitedWhenItsFirstAttemptStarts() {
        when(admission.admit(workspace.getId(), accountId)).thenReturn(LlmCatalogTestFixtures.admittedMentorConfig());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        MentorTurnStarts.start(beans, starter(meters), workspace.getId(), main.getId());

        assertThat(meters.get(AgentMetrics.MENTOR_TURN_QUEUE_WAIT).timer().count())
                .isEqualTo(1);
    }

    @Test
    void shouldFailTheTurnWithTheReasonWhenAdmissionDeclinesTheDeveloper() {
        when(admission.admit(workspace.getId(), accountId)).thenThrow(new MentorRefusedException(MentorRefusal.NO_AI));

        MentorTurnStarts.start(beans, starter, workspace.getId(), main.getId());

        MentorTurn row = failedTurn();
        assertThat(row.getError()).isEqualTo(MentorRefusal.NO_AI.userMessage());
        assertThat(turns.isHeld(row)).isFalse();
    }

    private MentorTurnStarter starter(MeterRegistry meters) {
        return new MentorTurnStarter(
                turns,
                admission,
                beans.getBean(DigestAdmission.class),
                turnRepository,
                writeFence,
                meters,
                connections);
    }

    private MentorTurn failedTurn() {
        return turnRepository
                .findByWorkspaceIdAndConversationIdAndStatus(workspace.getId(), main.getId(), MentorTurn.Status.FAILED)
                .getFirst();
    }
}
