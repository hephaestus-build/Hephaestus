package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetHeadroom;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;

/**
 * A ledger row is only appended when an agent job or mentor turn ends, so for the whole length of a run
 * the ledger says the workspace has spent nothing. Every fixture below therefore fixes a zero-spend
 * ledger, and what refuses a call is the attempt's own completed spend counted on top of it.
 */
class ProxyBudgetGateTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 42L;
    private static final BigDecimal ONE_DOLLAR = new BigDecimal("1.00");

    @Mock
    private LlmBudgetService budgetService;

    private ProxyBudgetGate gate;

    @BeforeEach
    void setUp() {
        gate = new ProxyBudgetGate(budgetService);
    }

    private static LlmBudgetHeadroom instanceCapOfOneDollar() {
        return new LlmBudgetHeadroom(BigDecimal.ZERO, ONE_DOLLAR, false, null, null, false);
    }

    private static LlmBudgetHeadroom workspaceCapOfOneDollar() {
        return new LlmBudgetHeadroom(null, null, false, BigDecimal.ZERO, ONE_DOLLAR, false);
    }

    private static ProxyRouting routing(@Nullable FundingSource scope, @Nullable String spentSoFarUsd) {
        return new ProxyRouting(
                "job:test",
                "openai-completions",
                "https://frozen.example.com/v1",
                scope,
                7L,
                8L,
                WORKSPACE_ID,
                spentSoFarUsd == null
                        ? null
                        : new ProxyRouting.BilledAttempt(
                                LlmUsageSourceType.AGENT_JOB,
                                UUID.randomUUID(),
                                0,
                                new BigDecimal(spentSoFarUsd),
                                "worker-1"));
    }

    @Nested
    @DisplayName("the bound: one attempt cannot outspend the cap by more than a call")
    class TheBound {

        @ParameterizedTest(name = "an attempt that has spent ${0} of a $1.00 cap is blocked={1}")
        @CsvSource({"0.99, false", "1.00, true"})
        void anAttemptIsJudgedOnItsOwnUnrecordedSpend(String ownSpendUsd, boolean blocked) {
            LlmBudgetHeadroom headroom = instanceCapOfOneDollar();
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(headroom);

            assertThat(headroom.decide().blocks(FundingSource.INSTANCE))
                    .as("the ledger alone allows both of these calls")
                    .isFalse();
            assertThat(gate.isBlocked(routing(FundingSource.INSTANCE, ownSpendUsd)))
                    .isEqualTo(blocked);
        }

        @Test
        @DisplayName("a second call is judged on fresh attempt spend even though the ledger read is cached")
        void theAttemptSpendIsFreshEvenWhenTheLedgerVerdictIsCached() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(instanceCapOfOneDollar());

            assertThat(gate.isBlocked(routing(FundingSource.INSTANCE, "0.60"))).isFalse();
            assertThat(gate.isBlocked(routing(FundingSource.INSTANCE, "1.20"))).isTrue();

            verify(budgetService, times(1)).headroom(WORKSPACE_ID);
        }
    }

    @Nested
    @DisplayName("the two purses stay separate, and an unattributable attempt is judged against both")
    class WhichPursePays {

        @Test
        @DisplayName("an exhausted host budget does not stop a call the workspace pays for itself")
        void anExhaustedInstancePurseDoesNotBlockAWorkspaceFundedAttempt() {
            when(budgetService.headroom(WORKSPACE_ID))
                    .thenReturn(new LlmBudgetHeadroom(
                            new BigDecimal("500.00"), ONE_DOLLAR, false, BigDecimal.ZERO, null, false));

            assertThat(gate.isBlocked(routing(FundingSource.WORKSPACE, "9.99"))).isFalse();
        }

        @Test
        @DisplayName("a workspace-funded attempt's spend is charged to the workspace's own cap")
        void aWorkspaceFundedAttemptIsJudgedAgainstItsOwnCap() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(workspaceCapOfOneDollar());

            assertThat(gate.isBlocked(routing(FundingSource.WORKSPACE, "1.00"))).isTrue();
        }

        @Test
        @DisplayName("an attempt with no known funding source is charged to both purses")
        void anUnattributableAttemptIsChargedToBothPurses() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(instanceCapOfOneDollar());

            assertThat(gate.isBlocked(routing(null, "1.00"))).isTrue();
        }
    }

    /**
     * A mentor turn has no {@code agent_job} row, so it needs a meter of its own: a route the gate finds
     * nothing to charge against spends without limit for the whole length of a turn.
     */
    @Nested
    @DisplayName("a mentor turn is bounded by its own completed calls, exactly like a job attempt")
    class AMentorTurn {

        private final MentorProxyCredentialRegistry credentials = new MentorProxyCredentialRegistry();

        private static final LlmPriceSnapshot TEN_DOLLARS_PER_MILLION_INPUT = new LlmPriceSnapshot(
                FundingSource.INSTANCE,
                PricingState.PRICED,
                1L,
                null,
                new BigDecimal("10"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);

        private ProxyRouting turnThatHasSpent(int inputTokens) {
            UUID sessionId = UUID.randomUUID();
            String token = credentials.mint(
                    sessionId,
                    new MentorProxyCredentialRegistry.Route(
                            "openai-responses",
                            "https://frozen.example.com/v1",
                            FundingSource.INSTANCE,
                            7L,
                            8L,
                            WORKSPACE_ID));
            MentorTurnMeter meter = new MentorTurnMeter(UUID.randomUUID(), TEN_DOLLARS_PER_MILLION_INPUT);
            credentials.bindTurn(sessionId, meter);
            if (inputTokens > 0) {
                credentials.accumulate(meter.turnId(), new ProxyTokenUsage(inputTokens, 0, 0, 0, 0));
            }
            return credentials.validate(token).orElseThrow();
        }

        /** At $10 per million input tokens, 100k tokens is $1.00 — the whole cap; 90k is still under it. */
        @ParameterizedTest(name = "a turn that has burned {0} input tokens of a $1.00 cap is blocked={1}")
        @CsvSource({"90000, false", "100000, true"})
        void aTurnIsJudgedOnItsOwnCompletedCalls(int inputTokens, boolean blocked) {
            LlmBudgetHeadroom headroom = instanceCapOfOneDollar();
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(headroom);

            assertThat(headroom.decide().blocks(FundingSource.INSTANCE))
                    .as("the ledger alone allows both of these turns")
                    .isFalse();
            assertThat(gate.isBlocked(turnThatHasSpent(inputTokens))).isEqualTo(blocked);
        }
    }

    @Nested
    @DisplayName("routes with nothing to charge")
    class NothingToCharge {

        @Test
        @DisplayName("a route with no live execution contributes no in-flight spend")
        void aRouteWithNoLiveExecutionIsJudgedOnTheLedgerAlone() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(instanceCapOfOneDollar());

            assertThat(gate.isBlocked(routing(FundingSource.INSTANCE, null))).isFalse();
        }

        @Test
        @DisplayName("an unattributable route with no workspace never blocks, and never queries the ledger")
        void aRouteWithNoWorkspaceFailsOpen() {
            ProxyRouting noWorkspace = new ProxyRouting(
                    "job:legacy", "openai-completions", "https://frozen.example.com/v1", null, null, null, null, null);

            assertThat(gate.isBlocked(noWorkspace)).isFalse();
        }
    }
}
