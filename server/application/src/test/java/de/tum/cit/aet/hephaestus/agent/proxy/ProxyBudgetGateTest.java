package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetHeadroom;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.RunningSpend;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * the ledger says the workspace has spent nothing. Most fixtures below therefore fix a zero-spend
 * ledger, and what refuses a call is the spend of the running work counted on top of it.
 */
class ProxyBudgetGateTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("508d958c-100e-594f-8b4e-6d5c60e59470");
    private static final UUID OTHER_LEASE = UUID.fromString("fafb277e-9c90-58ff-9167-1f1b3b0dce88");

    private static final long WORKSPACE_ID = 42L;
    private static final BigDecimal ONE_DOLLAR = new BigDecimal("1.00");

    @Mock
    private LlmBudgetService budgetService;

    @Mock
    private ProxyRunningSpend runningSpend;

    private ProxyBudgetGate gate;

    @BeforeEach
    void setUp() {
        gate = new ProxyBudgetGate(
                budgetService,
                runningSpend,
                new PracticeReviewProperties(false, 15, 5, null, 12, 16000, 200_000, 1000, Duration.ofMinutes(10)));
    }

    private static LlmBudgetHeadroom instanceCapOfOneDollar() {
        return new LlmBudgetHeadroom(BigDecimal.ZERO, ONE_DOLLAR, false, null, null, false);
    }

    private static LlmBudgetHeadroom workspaceCapOfOneDollar() {
        return new LlmBudgetHeadroom(null, null, false, BigDecimal.ZERO, ONE_DOLLAR, false);
    }

    private void running(String instanceUsd, String workspaceUsd) {
        when(runningSpend.of(WORKSPACE_ID))
                .thenReturn(new RunningSpend(new BigDecimal(instanceUsd), new BigDecimal(workspaceUsd)));
    }

    private static ProxyRouting.BilledAttempt attempt() {
        return new ProxyRouting.BilledAttempt(UsageSourceType.REVIEW_JOB, UUID.randomUUID(), 0, LEASE);
    }

    private static ProxyRouting routing(@Nullable UsageFundingSource scope) {
        return new ProxyRouting(
                "job:test",
                "openai-completions",
                "https://frozen.example.com/v1",
                scope,
                7L,
                8L,
                WORKSPACE_ID,
                attempt(),
                null,
                null,
                Instant.now().plusSeconds(60));
    }

    @Nested
    @DisplayName("the bound: running work cannot outspend the cap by more than the calls in flight")
    class TheBound {

        @ParameterizedTest(name = "running work that has spent ${0} of a $1.00 cap is blocked={1}")
        @CsvSource({"0.99, false", "1.00, true"})
        void aCallIsJudgedOnTheSpendOfTheRunningWork(String runningUsd, boolean blocked) {
            LlmBudgetHeadroom headroom = instanceCapOfOneDollar();
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(headroom);
            running(runningUsd, "0");

            assertThat(headroom.decide().blocks(UsageFundingSource.INSTANCE))
                    .as("the ledger alone allows both of these calls")
                    .isFalse();
            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE))).isEqualTo(blocked);
        }

        @Test
        @DisplayName("a second call is judged on fresh running spend even though the ledger read is cached")
        void theRunningSpendIsFreshEvenWhenTheLedgerVerdictIsCached() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(instanceCapOfOneDollar());

            running("0.60", "0");
            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE))).isFalse();
            running("1.20", "0");
            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE))).isTrue();

            verify(budgetService, times(1)).headroom(WORKSPACE_ID);
        }

        @Test
        @DisplayName("a workspace without a cap reads no running spend")
        void anUncappedWorkspaceReadsNoRunningSpend() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(LlmBudgetHeadroom.UNCAPPED);

            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE))).isFalse();

            verifyNoInteractions(runningSpend);
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
                            new BigDecimal("500.00"), ONE_DOLLAR, false, BigDecimal.ZERO, BigDecimal.TEN, false));
            running("0", "9.99");

            assertThat(gate.isBlocked(routing(UsageFundingSource.WORKSPACE))).isFalse();
        }

        @Test
        @DisplayName("spend on the workspace's own provider counts against the workspace's own cap")
        void workspaceFundedSpendIsJudgedAgainstItsOwnCap() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(workspaceCapOfOneDollar());
            running("0", "1.00");

            assertThat(gate.isBlocked(routing(UsageFundingSource.WORKSPACE))).isTrue();
        }

        @Test
        @DisplayName("a call that a purse without a cap pays reads no running spend")
        void aCallOfAnUncappedPurseReadsNoRunningSpend() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(workspaceCapOfOneDollar());

            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE))).isFalse();

            verifyNoInteractions(runningSpend);
        }

        @Test
        @DisplayName("spend on the host's models does not count against the workspace's own cap")
        void instanceFundedSpendLeavesTheOwnCapAlone() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(workspaceCapOfOneDollar());
            running("9.99", "0");

            assertThat(gate.isBlocked(routing(UsageFundingSource.WORKSPACE))).isFalse();
        }

        @Test
        @DisplayName("an attempt with no known funding source is judged against both purses")
        void anUnattributableAttemptIsJudgedAgainstBothPurses() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(instanceCapOfOneDollar());
            running("1.00", "0");

            assertThat(gate.isBlocked(routing(null))).isTrue();
        }
    }

    @Nested
    @DisplayName("routes with no workspace")
    class NoWorkspace {

        @Test
        @DisplayName("an unattributable route with no workspace never blocks, and never queries the ledger")
        void aRouteWithNoWorkspaceFailsOpen() {
            ProxyRouting noWorkspace = new ProxyRouting(
                    "job:legacy",
                    "openai-completions",
                    "https://frozen.example.com/v1",
                    null,
                    null,
                    null,
                    null,
                    attempt(),
                    null,
                    null,
                    Instant.now().plusSeconds(60));

            assertThat(gate.isBlocked(noWorkspace)).isFalse();
        }
    }

    @Nested
    class DirectModelCap {

        @ParameterizedTest
        @CsvSource({"199999, 999, false", "200000, 0, true", "0, 999, false", "0, 1000, true", "199999, 1000, true"})
        void shouldRefuseWhenTheAttemptsDirectModelTokensOrSearchUnitsReachTheirLimit(
                long tokens, long searchUnits, boolean reached) {
            var attempt = new ProxyRouting.BilledAttempt(
                    UsageSourceType.PRACTICE_SCRIPT_RERANKING, UUID.randomUUID(), 0, OTHER_LEASE, tokens, searchUnits);

            assertThat(gate.isDirectModelCapReached(attempt)).isEqualTo(reached);
        }
    }
}
