package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetHeadroom;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.PinnedParks;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;

/**
 * The ledger has a row for each model call, and a call's cost lands when the call ends. The gate refuses a call once the
 * month's ended calls reach the cap of the purse that pays it.
 */
class ProxyBudgetGateTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("508d958c-100e-594f-8b4e-6d5c60e59470");

    private static final long WORKSPACE_ID = 42L;
    private static final BigDecimal ONE_DOLLAR = new BigDecimal("1.00");

    @Mock
    private LlmBudgetService budgetService;

    private ProxyBudgetGate gate;

    @BeforeEach
    void setUp() {
        gate = new ProxyBudgetGate(budgetService);
    }

    private static LlmBudgetHeadroom instanceCapOfOneDollar(String spentUsd) {
        return new LlmBudgetHeadroom(new BigDecimal(spentUsd), ONE_DOLLAR, false, null, null, false);
    }

    private static LlmBudgetHeadroom workspaceCapOfOneDollar(String spentUsd) {
        return new LlmBudgetHeadroom(null, null, false, new BigDecimal(spentUsd), ONE_DOLLAR, false);
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
                Instant.now().plusSeconds(60),
                null);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,false"})
    void shouldRefuseAnUnboundedProbeBeforeItCanPoisonACappedPurse(boolean capped, boolean blocked) {
        when(budgetService.headroom(WORKSPACE_ID))
                .thenReturn(
                        capped
                                ? new LlmBudgetHeadroom(null, null, false, BigDecimal.ZERO, ONE_DOLLAR, false)
                                : LlmBudgetHeadroom.UNCAPPED);
        var probe = new ProxyRouting(
                "model-capability-probe",
                "openai-completions",
                "",
                UsageFundingSource.WORKSPACE,
                7L,
                8L,
                WORKSPACE_ID,
                new ProxyRouting.BilledAttempt(UsageSourceType.PROBE, UUID.randomUUID(), 0, null),
                Instant.now().plusSeconds(15),
                64L);
        assertThat(gate.refusesHold(probe, null)).isEqualTo(blocked);
    }

    @Nested
    @DisplayName("the bound: the month's ended calls cannot outspend the cap by more than the calls in flight")
    class TheBound {

        @ParameterizedTest(name = "ended calls that spent ${0} of a $1.00 cap block={1}")
        @CsvSource({"0.99, false", "1.00, true"})
        void aCallIsJudgedOnTheEndedCallsOfTheMonth(String spentUsd, boolean blocked) {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(instanceCapOfOneDollar(spentUsd));

            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE))).isEqualTo(blocked);
        }

        @Test
        @DisplayName("a call that ended on this replica counts against the cap at once, though the verdict is cached")
        void aCompletedCallCountsAtOnce() {
            when(budgetService.headroom(WORKSPACE_ID))
                    .thenReturn(instanceCapOfOneDollar("0.60"), instanceCapOfOneDollar("1.20"));

            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE))).isFalse();
            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE)))
                    .as("the cached verdict")
                    .isFalse();
            gate.spent(WORKSPACE_ID);
            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE))).isTrue();

            verify(budgetService, times(2)).headroom(WORKSPACE_ID);
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
                            new BigDecimal("500.00"),
                            ONE_DOLLAR,
                            false,
                            new BigDecimal("9.99"),
                            BigDecimal.TEN,
                            false));

            assertThat(gate.isBlocked(routing(UsageFundingSource.WORKSPACE))).isFalse();
        }

        @Test
        @DisplayName("spend on the workspace's own provider counts against the workspace's own cap")
        void workspaceFundedSpendIsJudgedAgainstItsOwnCap() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(workspaceCapOfOneDollar("1.00"));

            assertThat(gate.isBlocked(routing(UsageFundingSource.WORKSPACE))).isTrue();
        }

        @Test
        @DisplayName("a call that a purse without a cap pays is never blocked")
        void aCallOfAnUncappedPurseIsNotBlocked() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(workspaceCapOfOneDollar("1.00"));

            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE))).isFalse();
        }

        @Test
        @DisplayName("an attempt with no known funding source is judged against both purses")
        void anUnattributableAttemptIsJudgedAgainstBothPurses() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(instanceCapOfOneDollar("1.00"));

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
                    Instant.now().plusSeconds(60),
                    null);

            assertThat(gate.isBlocked(noWorkspace)).isFalse();
        }
    }

    @Nested
    @DisplayName("the hold: a call opens only while its own upper bound fits under its purse's cap")
    class TheHold {

        @ParameterizedTest
        @CsvSource({
            "0.90, 0.10, false",
            "0.90, 0.100001, true",
            "0.99, 0.00, false",
            "1.00, 0.00, true",
            "0.00, 1.00, false",
            "0.00, 1.01, true"
        })
        void shouldRefuseACallWhoseBoundTakesTheSpendPastTheCap(String spentUsd, String boundUsd, boolean refused) {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(instanceCapOfOneDollar(spentUsd));

            assertThat(gate.refusesHold(routing(UsageFundingSource.INSTANCE), new BigDecimal(boundUsd)))
                    .isEqualTo(refused);
        }

        /** The cached verdict can be five seconds old; the hold reads the spend again, with every call opened since. */
        @Test
        void shouldReadTheSpendAgainForTheHold() {
            when(budgetService.headroom(WORKSPACE_ID))
                    .thenReturn(instanceCapOfOneDollar("0.10"), instanceCapOfOneDollar("0.95"));
            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE))).isFalse();

            assertThat(gate.refusesHold(routing(UsageFundingSource.INSTANCE), new BigDecimal("0.10")))
                    .isTrue();
        }

        @Test
        void shouldHoldOnlyThePursePayingForTheCall() {
            when(budgetService.headroom(WORKSPACE_ID)).thenReturn(workspaceCapOfOneDollar("0.99"));

            assertThat(gate.refusesHold(routing(UsageFundingSource.INSTANCE), new BigDecimal("0.50")))
                    .isFalse();
            assertThat(gate.refusesHold(routing(UsageFundingSource.WORKSPACE), new BigDecimal("0.50")))
                    .isTrue();
        }

        /** A call without a bound keeps the verdict's rule: its row makes the purse unverifiable once it is open. */
        @Test
        void shouldPassACallWithoutABound() {
            assertThat(gate.refusesHold(routing(UsageFundingSource.INSTANCE), null))
                    .isFalse();
            verify(budgetService, times(0)).headroom(WORKSPACE_ID);
        }
    }

    @Nested
    class DirectModelCap {}

    /** The proxy serves model calls on virtual threads, which a ledger read under a monitor would pin on Java 21. */
    @Nested
    class VirtualThreads {

        @Test
        void shouldReadTheLedgerWithoutPinningTheVirtualThreadThatAsks() throws Exception {
            when(budgetService.headroom(WORKSPACE_ID)).thenAnswer(read -> {
                Thread.sleep(50);
                return instanceCapOfOneDollar("0.10");
            });
            assertThat(PinnedParks.during(() -> gate.isBlocked(routing(UsageFundingSource.INSTANCE))))
                    .as("pinned parks while the ledger was read")
                    .isEmpty();
        }

        @Test
        void shouldReadTheLedgerAgainWhenACallSpentWhileTheVerdictWasRead() throws Exception {
            var reading = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            when(budgetService.headroom(WORKSPACE_ID))
                    .thenAnswer(read -> {
                        reading.countDown();
                        release.await();
                        return instanceCapOfOneDollar("0.60");
                    })
                    .thenReturn(instanceCapOfOneDollar("1.20"));
            Thread first = Thread.ofVirtual().start(() -> gate.isBlocked(routing(UsageFundingSource.INSTANCE)));
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();

            gate.spent(WORKSPACE_ID);
            release.countDown();
            first.join();

            assertThat(gate.isBlocked(routing(UsageFundingSource.INSTANCE)))
                    .as("the verdict read before the spend is not kept")
                    .isTrue();
            verify(budgetService, times(2)).headroom(WORKSPACE_ID);
        }
    }
}
