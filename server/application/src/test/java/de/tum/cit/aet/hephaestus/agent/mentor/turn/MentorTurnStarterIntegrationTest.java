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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Admission runs when a worker starts a turn, because a follow-up can wait while the member's choice changes. */
class MentorTurnStarterIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String WORKER = "worker-starter";

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private MentorAttemptUsageService attemptUsage;

    @Autowired
    private JdbcTemplate jdbc;

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
        starter = new MentorTurnStarter(turns, admission, attemptUsage, metrics, connections);
    }

    @Test
    void shouldFreezeTheAdmittedModelAndRatesOnTheAttemptWhenAdmissionAccepts() {
        when(admission.admit(workspace.getId(), accountId)).thenReturn(LlmCatalogTestFixtures.admittedMentorConfig());

        MentorTurnStarter.StartedTurn started =
                starter.startNext(WORKER, Duration.ofMinutes(5)).orElseThrow();

        assertThat(started.accountId()).isEqualTo(accountId);
        MentorTurn row = turnRepository.findById(started.turn().getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(MentorTurnAdmission.read(row.getAdmission())).hasValueSatisfying(admitted -> {
            assertThat(admitted.model()).isEqualTo("test-model");
            assertThat(admitted.price())
                    .isEqualTo(Objects.requireNonNull(
                            LlmCatalogTestFixtures.admittedMentorConfig().priceSnapshot()));
        });
        // The attempt's usage measurement exists before any call, with the same model and rates and no call.
        assertThat(jdbc.queryForMap(
                        "SELECT model, pricing_state, applied_price_id, applied_per_1m_input_usd, calls, occurred_at"
                                + " FROM mentor_turn_attempt_usage WHERE turn_id = ? AND attempt = ?",
                        row.getId(),
                        row.getAttempt()))
                .containsEntry("model", "test-model")
                .containsEntry("pricing_state", "PRICED")
                .containsEntry("applied_price_id", 12L)
                .containsEntry("calls", 0)
                .containsEntry("occurred_at", null)
                .hasEntrySatisfying(
                        "applied_per_1m_input_usd",
                        rate -> assertThat((BigDecimal) rate).isEqualByComparingTo("10"));
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

        assertThat(starter.startNext(WORKER, Duration.ofMinutes(5))).isEmpty();

        MentorTurn row = failedTurn();
        assertThat(row.getError()).isEqualTo(MentorTurnStarter.MODEL_TOO_SMALL);
        assertThat(row.isLeased()).isFalse();
    }

    @Test
    void shouldFailTheTurnWithoutStartingItWhenItsProviderConnectionIsPaused() {
        MentorLlmConfig admitted = LlmCatalogTestFixtures.admittedMentorConfig();
        when(admission.admit(workspace.getId(), accountId)).thenReturn(admitted);
        for (int call = 0; call < 3; call++) {
            Objects.requireNonNull(connections.acquire(admitted.connectionScope(), admitted.connectionId()))
                    .failed(new IllegalStateException("upstream stalled"));
        }

        assertThat(starter.startNext(WORKER, Duration.ofMinutes(5))).isEmpty();

        assertThat(failedTurn().getError()).isEqualTo(MentorTurnStarter.PROVIDER_PAUSED);
    }

    @Test
    void shouldTimeHowLongTheQuestionWaitedWhenItsFirstAttemptStarts() {
        when(admission.admit(workspace.getId(), accountId)).thenReturn(LlmCatalogTestFixtures.admittedMentorConfig());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        new MentorTurnStarter(turns, admission, attemptUsage, meters, connections)
                .startNext(WORKER, Duration.ofMinutes(5));

        assertThat(meters.get(AgentMetrics.MENTOR_TURN_QUEUE_WAIT).timer().count())
                .isEqualTo(1);
        assertThat(meters.get(AgentMetrics.AGENT_PHASE_REPORTED_DURATION)
                        .tags("phase", "admission_wait", "workload", "conversation", "outcome", "completed")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldFailTheTurnWithTheReasonWhenAdmissionDeclinesTheDeveloper() {
        when(admission.admit(workspace.getId(), accountId)).thenThrow(new MentorRefusedException(MentorRefusal.NO_AI));

        starter.startNext(WORKER, Duration.ofMinutes(5));

        MentorTurn row = failedTurn();
        assertThat(row.getError()).isEqualTo(MentorRefusal.NO_AI.userMessage());
        assertThat(row.isLeased()).isFalse();
    }

    private MentorTurn failedTurn() {
        return turnRepository
                .findByWorkspaceIdAndConversationIdAndStatus(workspace.getId(), main.getId(), MentorTurn.Status.FAILED)
                .getFirst();
    }
}
