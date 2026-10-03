package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

class LlmBudgetServiceTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 42L;

    @Mock
    private LlmUsageEventRepository usageRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    private SimpleMeterRegistry meterRegistry;
    private LlmBudgetService budgetService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        budgetService = new LlmBudgetService(usageRepository, workspaceRepository, meterRegistry);
    }

    private Workspace workspaceWithBudgets(@Nullable BigDecimal instanceBudget, @Nullable BigDecimal byoBudget) {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setMonthlyLlmBudgetUsd(instanceBudget);
        workspace.setMonthlyByoLlmBudgetUsd(byoBudget);
        return workspace;
    }

    private LlmBudgetDecision decideWithBudgets(@Nullable BigDecimal instanceBudget, @Nullable BigDecimal byoBudget) {
        when(workspaceRepository.findById(WORKSPACE_ID))
                .thenReturn(Optional.of(workspaceWithBudgets(instanceBudget, byoBudget)));
        return budgetService.decide(WORKSPACE_ID);
    }

    /** Every ledger read stubbed leniently, so a single test can vary one purse at a time. */
    private void stubLedger(String instanceSpend, boolean instanceUnpriced, String byoSpend, boolean byoUnpriced) {
        lenient()
                .when(usageRepository.sumCost(eq(WORKSPACE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(new BigDecimal(instanceSpend));
        lenient()
                .when(usageRepository.existsUnpricedInstanceFunded(
                        eq(WORKSPACE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(instanceUnpriced);
        lenient()
                .when(usageRepository.sumByoCost(eq(WORKSPACE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(new BigDecimal(byoSpend));
        lenient()
                .when(usageRepository.existsUnpricedWorkspaceFunded(
                        eq(WORKSPACE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(byoUnpriced);
    }

    private static @Nullable BigDecimal cap(@Nullable String value) {
        return value == null || value.isEmpty() ? null : new BigDecimal(value);
    }

    @Nested
    @DisplayName("decide() — one purse's verdict")
    class PurseVerdict {

        @ParameterizedTest(name = "instance cap={0} spend={1} unpriced={2} -> {3}")
        @CsvSource({
            ", 999999.00, true, NONE",
            "10.00, 10.00, false, EXHAUSTED",
            "10.00, 1.00, false, NONE",
            "10.00, 1.00, true, UNPRICED_USAGE_BLOCKED",
            "0, 0, false, EXHAUSTED",
        })
        void theInstancePurseIsBlockedOnlyWhenExhaustedOrUnverifiable(
                String capUsd, String spend, boolean unpriced, LlmBudgetBlockReason expected) {
            stubLedger(spend, unpriced, "0.00", false);

            assertThat(decideWithBudgets(cap(capUsd), null).instanceFunded()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "byo cap={0} spend={1} unpriced={2} -> {3}")
        @CsvSource({
            ", 999999.00, true, NONE",
            "25.00, 25.00, false, EXHAUSTED",
            "25.00, 24.99, false, NONE",
            "25.00, 1.00, true, UNPRICED_USAGE_BLOCKED",
            "0, 0, false, EXHAUSTED",
        })
        void theWorkspaceFundedPurseIsBlockedOnlyWhenExhaustedOrUnverifiable(
                String capUsd, String spend, boolean unpriced, LlmBudgetBlockReason expected) {
            stubLedger("0.00", false, spend, unpriced);

            assertThat(decideWithBudgets(null, cap(capUsd)).workspaceFunded()).isEqualTo(expected);
        }

        @Test
        @DisplayName("an uncapped purse asks the ledger nothing, and EXHAUSTED never probes for unpriced usage")
        void theLedgerReadsAreLazy() {
            stubLedger("999999.00", true, "999999.00", true);

            assertThat(decideWithBudgets(null, null)).isEqualTo(LlmBudgetDecision.ALLOWED);
            verify(usageRepository, never()).sumCost(any(), any(), any());
            verify(usageRepository, never()).sumByoCost(any(), any(), any());

            decideWithBudgets(new BigDecimal("10.00"), new BigDecimal("10.00"));
            // Both purses are provably exhausted from the priced sums alone.
            verify(usageRepository, never()).existsUnpricedInstanceFunded(any(), any(), any());
            verify(usageRepository, never()).existsUnpricedWorkspaceFunded(any(), any(), any());
        }

        @Test
        @DisplayName("an unknown workspace id is allowed on both purses")
        void unknownWorkspaceIdIsNeverBlocked() {
            when(workspaceRepository.findById(99L)).thenReturn(Optional.empty());

            assertThat(budgetService.decide(99L)).isEqualTo(LlmBudgetDecision.ALLOWED);
        }
    }

    /**
     * The rule the whole two-purse design exists for: instance-funded and own-provider spend are
     * different people's money, so neither purse may ever be blocked by the other's state. Each cap is
     * only blocked by a blind spot its OWN owner can clear — an unpriced shared model is the host's to
     * price, an unpriced BYO model the workspace's.
     */
    @Nested
    @DisplayName("decide() — the two purses never contaminate each other")
    class PurseIsolation {

        record Month(
                String instanceCap,
                String instanceSpend,
                boolean instanceUnpriced,
                String byoCap,
                String byoSpend,
                boolean byoUnpriced) {}

        /**
         * The last two rows are the invariant at its extremes — nothing a workspace admin can write (a
         * zero cap, a million dollars of own-provider spend, an unpriceable own-provider model) is an
         * input to the host's verdict, and vice versa.
         *
         * <p>The ledger here is mocked, so what these rows pin is the DECISION logic. The
         * {@code funding_source} SQL predicate that feeds it is proved against a real database by
         * {@code LlmUsageLedgerIntegrationTest#theWorkspacesOwnCapReadsOnlyOwnProviderLedgerRows}.
         */
        static Stream<Arguments> crossPurse() {
            return Stream.of(
                    Arguments.of(
                            new Month("10.00", "0.00", true, "10.00", "0.00", false),
                            LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED,
                            LlmBudgetBlockReason.NONE,
                            "an unpriced INSTANCE event never blocks the BYO purse"),
                    Arguments.of(
                            new Month("10.00", "0.00", false, "10.00", "0.00", true),
                            LlmBudgetBlockReason.NONE,
                            LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED,
                            "an unpriced WORKSPACE event never blocks the instance purse"),
                    Arguments.of(
                            new Month("10.00", "10.00", false, "10.00", "0.00", false),
                            LlmBudgetBlockReason.EXHAUSTED,
                            LlmBudgetBlockReason.NONE,
                            "an exhausted instance purse leaves the BYO purse spendable"),
                    Arguments.of(
                            new Month("10.00", "0.00", false, "10.00", "10.00", false),
                            LlmBudgetBlockReason.NONE,
                            LlmBudgetBlockReason.EXHAUSTED,
                            "an exhausted BYO purse leaves the instance purse spendable"),
                    Arguments.of(
                            new Month("10.00", "1.00", true, "0", "999999.00", true),
                            LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED,
                            LlmBudgetBlockReason.EXHAUSTED,
                            "a maxed-out, unpriceable BYO purse still leaves the instance verdict on its own inputs"),
                    Arguments.of(
                            new Month("10.00", "999999.00", true, "0", "0.00", false),
                            LlmBudgetBlockReason.EXHAUSTED,
                            LlmBudgetBlockReason.EXHAUSTED,
                            "a maxed-out, unpriceable instance purse does not change why the BYO purse is blocked"));
        }

        @ParameterizedTest(name = "{3}")
        @MethodSource("crossPurse")
        void oneBlockedPurseLeavesTheOtherAlone(
                Month month, LlmBudgetBlockReason expectedInstance, LlmBudgetBlockReason expectedByo, String why) {
            stubLedger(month.instanceSpend(), month.instanceUnpriced(), month.byoSpend(), month.byoUnpriced());

            LlmBudgetDecision decision = decideWithBudgets(cap(month.instanceCap()), cap(month.byoCap()));

            assertThat(decision.instanceFunded()).as(why).isEqualTo(expectedInstance);
            assertThat(decision.workspaceFunded()).as(why).isEqualTo(expectedByo);
        }
    }

    /**
     * An unattributable call (null funding source) is judged against BOTH purses — fail-safe, never a
     * way around a cap.
     */
    @Nested
    @DisplayName("LlmBudgetDecision.forFunding with no funding source")
    class UnattributableCalls {

        @Test
        @DisplayName("blocked when EITHER purse is blocked, allowed only when neither is")
        void unknownFundingSourceIsFailSafe() {
            LlmBudgetDecision instanceBlocked =
                    new LlmBudgetDecision(LlmBudgetBlockReason.EXHAUSTED, LlmBudgetBlockReason.NONE);
            LlmBudgetDecision byoBlocked =
                    new LlmBudgetDecision(LlmBudgetBlockReason.NONE, LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED);

            assertThat(instanceBlocked.forFunding(null)).isEqualTo(LlmBudgetBlockReason.EXHAUSTED);
            assertThat(byoBlocked.forFunding(null)).isEqualTo(LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED);
            assertThat(LlmBudgetDecision.ALLOWED.forFunding(null)).isEqualTo(LlmBudgetBlockReason.NONE);
            assertThat(LlmBudgetDecision.ALLOWED.blocks(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("blockSubmission is scoped to whoever pays")
    class BlockSubmission {

        @ParameterizedTest(name = "{4}")
        @MethodSource("de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetServiceTest#exhaustedCaps")
        void anExhaustedCapBlocksOnlyItsOwnFundingSource(
                String instanceSpend, String byoSpend, FundingSource blockedSource, String expectedCapTag, String why) {
            stubLedger(instanceSpend, false, byoSpend, false);
            Workspace workspace = workspaceWithBudgets(new BigDecimal("10.00"), new BigDecimal("10.00"));
            FundingSource openSource =
                    blockedSource == FundingSource.INSTANCE ? FundingSource.WORKSPACE : FundingSource.INSTANCE;

            assertThat(budgetService.blockSubmission(workspace, "PULL_REQUEST_REVIEW", openSource))
                    .as(why)
                    .isFalse();
            assertThat(budgetService.blockSubmission(workspace, "PULL_REQUEST_REVIEW", blockedSource))
                    .isTrue();
            assertThat(meterRegistry
                            .counter("llm.budget.blocked", "surface", "agent_job", "cap", expectedCapTag)
                            .count())
                    .isEqualTo(1d);

            // An unattributable submission is blocked by either cap — and is filed under the cap that
            // actually refused it, not the one the caller happened to be asking about.
            assertThat(budgetService.blockSubmission(workspace, "PULL_REQUEST_REVIEW", null))
                    .isTrue();
            assertThat(meterRegistry
                            .counter("llm.budget.blocked", "surface", "agent_job", "cap", expectedCapTag)
                            .count())
                    .isEqualTo(2d);
        }
    }

    static Stream<Arguments> exhaustedCaps() {
        return Stream.of(
                Arguments.of(
                        "10.00",
                        "0.00",
                        FundingSource.INSTANCE,
                        "instance",
                        "an exhausted instance cap does not block workspace-funded work"),
                Arguments.of(
                        "0.00",
                        "10.00",
                        FundingSource.WORKSPACE,
                        "byo",
                        "an exhausted BYO cap does not block instance-funded work"));
    }

    @Nested
    class Verdict {

        @ParameterizedTest(name = "spend={0} unpriced={1} cap={2} -> {3}")
        @CsvSource({
            "5.00, false, 10.00, WITHIN",
            "10.00, false, 10.00, EXHAUSTED",
            "5.00, true, 10.00, UNVERIFIABLE",
            // Both conditions at once: already-reached-the-cap is the more actionable signal.
            "10.00, true, 10.00, EXHAUSTED",
            // Uncapped can never be EXHAUSTED, but can still be UNVERIFIABLE.
            "999999.00, false, , WITHIN",
            "999999.00, true, , UNVERIFIABLE",
        })
        void theVerdictCombinesSpendUnpricedUsageAndCap(
                String pricedCost, boolean hasUnpriced, String capUsd, LlmBudgetVerdict expected) {
            assertThat(LlmBudgetService.verdictFor(new BigDecimal(pricedCost), hasUnpriced, cap(capUsd)))
                    .isEqualTo(expected);
        }
    }

    @Nested
    class MonthWindow {

        @ParameterizedTest(name = "{0}-{1} = [{2}, {3})")
        @CsvSource({
            "2026, 7, 2026-07-01T00:00:00Z, 2026-08-01T00:00:00Z",
            "2026, 12, 2026-12-01T00:00:00Z, 2027-01-01T00:00:00Z",
        })
        void windowIsAHalfOpenUtcCalendarMonth(int year, int month, String from, String to) {
            LlmBudgetService.MonthWindow window = LlmBudgetService.MonthWindow.of(YearMonth.of(year, month));

            assertThat(window.from()).isEqualTo(Instant.parse(from));
            assertThat(window.to()).isEqualTo(Instant.parse(to));
        }
    }
}
