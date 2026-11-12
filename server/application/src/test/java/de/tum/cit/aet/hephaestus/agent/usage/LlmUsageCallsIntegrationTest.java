package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The life of one model call's ledger row: opened before the call, measured at its end, settled with its claim. */
@Tag("integration")
class LlmUsageCallsIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final LlmPriceSnapshot PRICED = new LlmPriceSnapshot(
            UsageFundingSource.INSTANCE,
            UsagePricingState.PRICED,
            null,
            null,
            new BigDecimal("2.00"),
            new BigDecimal("8.00"),
            null,
            null);

    @Autowired
    private LlmUsageRecorder recorder;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private LlmUsageRepricingSweeper sweeper;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private LlmModelPriceRepository prices;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private LlmBudgetService budget;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldReadTheNewestFailureOfTheExactClaimByFailureTimeRatherThanCallOpenTime() {
        AgentRunLease lease = leasedRun("calls-failure-order");
        UUID older = open(lease, lease.epoch(), PRICED);
        UUID newer = open(lease, lease.epoch(), PRICED);
        var first = new MentorTurn.FailedCall("upstream_status", 502);
        var last = new MentorTurn.FailedCall("upstream_stalled", null);
        transactions.executeWithoutResult(tx -> recorder.complete(newer, lease.workspaceId(), null, first));
        Instant firstTime = failedAt(newer);
        transactions.executeWithoutResult(tx -> recorder.complete(older, lease.workspaceId(), null, last));
        assertThat(failedAt(older)).isAfter(firstTime);
        assertThat(occurredAt(older)).isBefore(occurredAt(newer));
        assertThat(recorder.latestFailure(lease.workspaceId(), lease.runId(), lease.epoch()))
                .isEqualTo(last);

        transactions.executeWithoutResult(tx -> recorder.complete(
                newer, lease.workspaceId(), LlmUsageRecorder.CallUsage.NONE, new MentorTurn.FailedCall("later", 503)));
        assertThat(failedAt(newer))
                .as("a repeated completion cannot move a call's failure")
                .isEqualTo(firstTime);
        assertThat(failure(newer)).isEqualTo(first);
        assertThat(row(newer)).containsEntry("call_state", "ENDED").containsEntry("unreported_calls", 1);
        assertThat(recorder.latestFailure(lease.workspaceId(), lease.runId(), lease.epoch()))
                .isEqualTo(last);

        UUID anotherEpoch = open(lease, lease.epoch() + 1, PRICED);
        transactions.executeWithoutResult(tx -> recorder.complete(
                anotherEpoch, lease.workspaceId(), null, new MentorTurn.FailedCall("other_epoch", 500)));
        assertThat(recorder.latestFailure(lease.workspaceId(), lease.runId(), lease.epoch()))
                .isEqualTo(last);
        assertThat(recorder.latestFailure(lease.workspaceId(), UUID.randomUUID(), lease.epoch()))
                .isNull();
        AgentRunLease otherWorkspace = leasedRun("calls-failure-other");
        assertThat(recorder.latestFailure(otherWorkspace.workspaceId(), lease.runId(), lease.epoch()))
                .isNull();
        transactions.executeWithoutResult(tx -> recorder.complete(
                older, otherWorkspace.workspaceId(), null, new MentorTurn.FailedCall("wrong_workspace", 403)));
        assertThat(failure(older)).isEqualTo(last);
    }

    @Test
    void shouldKeepReportedUsageWhenTheProviderReturnsANonStandardStatus() {
        AgentRunLease lease = leasedRun("calls-nonstandard-status");
        UUID call = open(lease, lease.epoch(), PRICED);

        transactions.executeWithoutResult(tx -> recorder.complete(
                call,
                lease.workspaceId(),
                new LlmUsageRecorder.CallUsage(10, 2, 0, 0, 0, 0),
                new MentorTurn.FailedCall("upstream_status", 600)));

        assertThat(row(call))
                .containsEntry("call_state", "ENDED")
                .containsEntry("input_tokens", 10L)
                .containsEntry("output_tokens", 2L)
                .containsEntry("measurement_verification", "VERIFIED")
                .containsEntry("pricing_state", "PRICED");
        assertThat((BigDecimal) row(call).get("cost_usd")).isEqualByComparingTo("0.000036");
        var failure = new MentorTurn.FailedCall("upstream_status", null);
        assertThat(failure(call)).isEqualTo(failure);
        assertThat(recorder.latestFailure(lease.workspaceId(), lease.runId(), lease.epoch()))
                .isEqualTo(failure);
    }

    @Test
    void shouldKeepMeasuredUsageWhenTheFirstFailureArrivesAfterCompletion() {
        AgentRunLease lease = leasedRun("calls-failure-after-usage");
        UUID call = open(lease, lease.epoch(), PRICED);
        complete(lease, call, new LlmUsageRecorder.CallUsage(10, 2, 0, 0, 0, 0));
        var measured = row(call);
        var failure = new MentorTurn.FailedCall("stream_failed", 200);

        transactions.executeWithoutResult(tx -> recorder.complete(call, lease.workspaceId(), null, failure));
        Instant failed = failedAt(call);
        transactions.executeWithoutResult(tx -> recorder.complete(
                call, lease.workspaceId(), LlmUsageRecorder.CallUsage.NONE, new MentorTurn.FailedCall("later", 503)));

        assertThat(row(call)).containsAllEntriesOf(measured);
        assertThat(failure(call)).isEqualTo(failure);
        assertThat(failedAt(call)).isEqualTo(failed);
    }

    @Test
    void shouldKeepTheFirstFailureWhenSettledUsageArrivesLate() {
        AgentRunLease lease = leasedRun("calls-failure-late");
        UUID call = open(lease, lease.epoch(), PRICED);
        transactions.executeWithoutResult(tx -> runs.end(fence(lease)));
        var failure = new MentorTurn.FailedCall("upstream_stalled", null);
        transactions.executeWithoutResult(tx -> recorder.complete(call, lease.workspaceId(), null, failure));
        Instant failed = failedAt(call);
        assertThat(row(call)).containsEntry("call_state", "SETTLED");

        complete(lease, call, new LlmUsageRecorder.CallUsage(10, 2, 0, 0, 0, 0));

        assertThat(row(call))
                .containsEntry("call_state", "ENDED")
                .containsEntry("input_tokens", 10L)
                .containsEntry("output_tokens", 2L);
        assertThat(failedAt(call)).isEqualTo(failed);
        assertThat(failure(call)).isEqualTo(failure);
        assertThat(recorder.latestFailure(lease.workspaceId(), lease.runId(), lease.epoch()))
                .isEqualTo(failure);
    }

    @Test
    void shouldWriteOneMeasuredRowWhenACallOpensAndCompletes() {
        AgentRunLease lease = leasedRun("calls-measured");
        UUID call = open(lease, lease.epoch(), PRICED);

        assertThat(row(call))
                .as("open before the call leaves, at zero until it ends")
                .containsEntry("call_state", "OPEN")
                .containsEntry("pricing_state", "PRICED")
                .containsEntry("measurement_verification", "UNKNOWN");

        complete(lease, call, new LlmUsageRecorder.CallUsage(1_000_000, 100_000, 0, 0, 0, 0));

        assertThat(row(call))
                .containsEntry("call_state", "ENDED")
                .containsEntry("input_tokens", 1_000_000L)
                .containsEntry("output_tokens", 100_000L)
                .containsEntry("total_calls", 1)
                .containsEntry("unreported_calls", 0)
                .containsEntry("usage_provenance", "PROXY")
                .containsEntry("measurement_verification", "VERIFIED")
                .containsEntry("pricing_state", "PRICED");
        assertThat((BigDecimal) row(call).get("cost_usd")).isEqualByComparingTo("2.80");
    }

    @Test
    void shouldEndACallWithoutUsageUnverifiableAndUnpricedNeverAtZero() {
        AgentRunLease lease = leasedRun("calls-no-usage");
        UUID call = open(lease, lease.epoch(), PRICED);

        complete(lease, call, null);

        assertThat(row(call))
                .containsEntry("call_state", "ENDED")
                .containsEntry("measurement_verification", "UNVERIFIABLE")
                .containsEntry("pricing_state", "UNPRICED")
                .containsEntry("unreported_calls", 1)
                .containsEntry("cost_usd", null);
    }

    @Test
    void shouldSettleTheOpenCallsOfTheEndingClaimOnlyWhenTheRunEnds() {
        AgentRunLease lease = leasedRun("calls-settle");
        UUID open = open(lease, lease.epoch(), PRICED);
        UUID measured = open(lease, lease.epoch(), PRICED);
        complete(lease, measured, new LlmUsageRecorder.CallUsage(10, 0, 0, 0, 0, 0));
        UUID otherClaim = open(lease, lease.epoch() + 1, PRICED);

        transactions.executeWithoutResult(
                tx -> assertThat(runs.end(fence(lease))).isEqualTo(AgentRuns.EndOutcome.ENDED));

        assertThat(row(open))
                .containsEntry("call_state", "SETTLED")
                .containsEntry("measurement_verification", "UNVERIFIABLE")
                .containsEntry("pricing_state", "UNPRICED")
                .containsEntry("unreported_calls", 1)
                .containsEntry("cost_usd", null);
        assertThat(row(measured))
                .as("a measured call keeps its measurement")
                .containsEntry("measurement_verification", "VERIFIED")
                .containsEntry("pricing_state", "PRICED");
        assertThat(row(otherClaim)).as("another claim's call is not this end's").containsEntry("call_state", "OPEN");
    }

    @Test
    void shouldMeasureASettledCallAgainWhenItsAnswerArrivesAfterItsClaimEnded() {
        AgentRunLease lease = leasedRun("calls-late");
        UUID call = open(lease, lease.epoch(), PRICED);
        transactions.executeWithoutResult(
                tx -> assertThat(runs.requeue(fence(lease), AgentRuns.Charge.FREE, Duration.ZERO, null))
                        .isTrue());
        Instant dated = occurredAt(call);
        assertThat(row(call)).containsEntry("measurement_verification", "UNVERIFIABLE");

        complete(lease, call, new LlmUsageRecorder.CallUsage(500, 50, 0, 0, 0, 0));

        // The measurement replaces the settlement at the price frozen before the call left.
        assertThat(row(call))
                .containsEntry("call_state", "ENDED")
                .containsEntry("input_tokens", 500L)
                .containsEntry("output_tokens", 50L)
                .containsEntry("unreported_calls", 0)
                .containsEntry("measurement_verification", "VERIFIED")
                .containsEntry("pricing_state", "PRICED")
                .containsEntry("usage_provenance", "PROXY");
        assertThat(occurredAt(call)).as("the call keeps its date").isEqualTo(dated);

        // A second answer of the same call changes nothing.
        complete(lease, call, new LlmUsageRecorder.CallUsage(9_999, 0, 0, 0, 0, 0));
        assertThat(row(call)).containsEntry("input_tokens", 500L);
    }

    @ParameterizedTest
    @EnumSource(
            value = UsagePricingState.class,
            names = {"PRICED", "NO_CHARGE"})
    void shouldPriceLateUsageAtTheFrozenModeWhenItsClaimEndedFirst(UsagePricingState state) {
        AgentRunLease lease =
                leasedRun(state == UsagePricingState.PRICED ? "calls-frozen-priced" : "calls-frozen-free");
        var price = new LlmPriceSnapshot(
                UsageFundingSource.WORKSPACE,
                state,
                null,
                999999L,
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                null,
                null);
        UUID call = open(lease, lease.epoch(), price, new BigDecimal("3.00"));
        UUID error = open(lease, lease.epoch(), price, new BigDecimal("3.00"));
        transactions.executeWithoutResult(tx -> runs.end(fence(lease)));
        complete(lease, call, new LlmUsageRecorder.CallUsage(1_000_000, 100_000, 0, 0, 0, 0));
        complete(lease, error, LlmUsageRecorder.CallUsage.NONE);
        assertThat(row(call)).containsEntry("pricing_state", state.name());
        assertThat((BigDecimal) row(call).get("cost_usd"))
                .isEqualByComparingTo(state == UsagePricingState.PRICED ? "2.80" : "0");
        assertThat(row(error)).containsEntry("pricing_state", state.name());
        assertThat((BigDecimal) row(error).get("cost_usd")).isZero();
    }

    @Test
    void shouldRefuseAReopenedOrResettledMeasuredCallAndAnEditedInitialPrice() {
        AgentRunLease lease = leasedRun("calls-transition-guard");
        UUID call = open(lease, lease.epoch(), PRICED);
        complete(lease, call, LlmUsageRecorder.CallUsage.NONE);
        for (String state : new String[] {"OPEN", "SETTLED"})
            assertThatThrownBy(() -> jdbc.update("UPDATE llm_usage_event SET call_state = ? WHERE id = ?", state, call))
                    .hasMessageContaining("Illegal usage call transition");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE llm_usage_event SET initial_pricing_state = 'NO_CHARGE' WHERE id = ?", call))
                .hasMessageContaining("A model call keeps its initial pricing state");
    }

    @Test
    void shouldKeepTheCallsOfADeletedRunWithoutTheirRun() {
        AgentRunLease lease = leasedRun("calls-deleted-run");
        UUID measured = open(lease, lease.epoch(), PRICED);
        complete(lease, measured, new LlmUsageRecorder.CallUsage(10, 0, 0, 0, 0, 0));
        UUID open = open(lease, lease.epoch(), PRICED);

        // A deleted turn deletes its run the same way.
        jdbc.update("DELETE FROM agent_run WHERE id = ?", lease.runId());

        assertThat(row(measured)).containsEntry("run_id", null).containsEntry("input_tokens", 10L);
        assertThat(row(open))
                .as("the run's deletion settles the call it left open")
                .containsEntry("run_id", null)
                .containsEntry("call_state", "SETTLED")
                .containsEntry("measurement_verification", "UNVERIFIABLE");
    }

    @Test
    void shouldPriceAnUnpricedVerifiedCallOfAnOlderMonth() {
        AgentRunLease lease = leasedRun("calls-reprice-older");
        LlmConnection connection = connections.save(LlmCatalogTestFixtures.connection("calls-reprice-conn"));
        LlmModel model = models.save(LlmCatalogTestFixtures.model(connection, "calls-reprice-model", "gpt-calls"));
        LlmModelPrice price = new LlmModelPrice();
        price.setModel(model);
        price.setPricingMode(PricingMode.UNPRICED);
        price.setEffectiveFrom(Instant.now().minus(200, ChronoUnit.DAYS));
        price = prices.saveAndFlush(price);
        var unpriced = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE, UsagePricingState.UNPRICED, price.getId(), null, null, null, null, null);
        UUID call = open(lease, lease.epoch(), unpriced);
        var failure = new MentorTurn.FailedCall("stream_failed", 200);
        transactions.executeWithoutResult(tx -> recorder.complete(
                call, lease.workspaceId(), new LlmUsageRecorder.CallUsage(1_000_000, 0, 0, 0, 0, 0), failure));
        Instant failed = failedAt(call);
        Instant threeMonthsAgo = Instant.now().minus(92, ChronoUnit.DAYS);
        jdbc.update(
                "UPDATE llm_usage_event SET started_at = ?, occurred_at = ? WHERE id = ?",
                Timestamp.from(threeMonthsAgo),
                Timestamp.from(threeMonthsAgo),
                call);
        price.setPricingMode(PricingMode.PRICED);
        price.setPer1mInputUsd(new BigDecimal("3.00"));
        price.setPer1mOutputUsd(new BigDecimal("9.00"));
        prices.saveAndFlush(price);

        sweeper.repriceNow();

        assertThat(row(call)).containsEntry("pricing_state", "PRICED");
        assertThat((BigDecimal) row(call).get("cost_usd")).isEqualByComparingTo("3.00");
        assertThat(failure(call)).isEqualTo(failure);
        assertThat(failedAt(call)).isEqualTo(failed);
    }

    /**
     * A provider timeout and a client abort both end the call without reported usage; a crash leaves it open until its
     * claim ends. Each way, the call keeps the upper bound it opened with.
     */
    @Test
    void shouldKeepTheUpperBoundWhenACallEndsWithoutUsageOrItsClaimEndsFirst() {
        AgentRunLease lease = leasedRun("calls-bound-kept");
        UUID cut = open(lease, lease.epoch(), PRICED, new BigDecimal("0.120000"));
        UUID crashed = open(lease, lease.epoch(), PRICED, new BigDecimal("0.340000"));

        complete(lease, cut, null);
        transactions.executeWithoutResult(
                tx -> assertThat(runs.end(fence(lease))).isEqualTo(AgentRuns.EndOutcome.ENDED));

        assertThat(row(cut)).containsEntry("call_state", "ENDED");
        assertThat(row(crashed)).containsEntry("call_state", "SETTLED");
        for (UUID call : new UUID[] {cut, crashed})
            assertThat(row(call))
                    .containsEntry("measurement_verification", "UNVERIFIABLE")
                    .containsEntry("pricing_state", "UNPRICED")
                    .containsEntry("cost_usd", null);
        assertThat((BigDecimal) row(cut).get("cost_bound_usd")).isEqualByComparingTo("0.12");
        assertThat((BigDecimal) row(crashed).get("cost_bound_usd")).isEqualByComparingTo("0.34");
    }

    @Test
    void shouldDropTheUpperBoundWhenTheCallNeedsARateItsPriceLacks() {
        AgentRunLease lease = leasedRun("calls-bound-dropped");
        UUID call = open(lease, lease.epoch(), PRICED, new BigDecimal("0.500000"));

        // The frozen price has no cache-read rate, so neither the cost nor the bound is known.
        complete(lease, call, new LlmUsageRecorder.CallUsage(10, 10, 10, 0, 0, 0));

        assertThat(row(call))
                .containsEntry("measurement_verification", "VERIFIED")
                .containsEntry("pricing_state", "UNPRICED")
                .containsEntry("cost_bound_usd", null);
    }

    @Test
    void shouldCountEachOpenOrUnmeasuredCallAtItsUpperBoundAgainstTheMonthlyCap() {
        AgentRunLease lease = leasedRun("calls-bound-cap");
        jdbc.update("UPDATE workspace SET monthly_llm_budget_usd = 1.00 WHERE id = ?", lease.workspaceId());
        UUID unmeasured = open(lease, lease.epoch(), PRICED, new BigDecimal("0.400000"));
        complete(lease, unmeasured, null);
        UUID measured = open(lease, lease.epoch(), PRICED, new BigDecimal("0.900000"));
        complete(lease, measured, new LlmUsageRecorder.CallUsage(100_000, 0, 0, 0, 0, 0));

        LlmBudgetHeadroom headroom = budget.headroom(lease.workspaceId());
        assertThat(headroom.instanceSpentUsd())
                .as("the unmeasured call at its bound, the measured one at its cost")
                .isEqualByComparingTo("0.60");
        assertThat(headroom.decide().forFunding(UsageFundingSource.INSTANCE))
                .as("a bound keeps the month verifiable, and a later call within the cap may start")
                .isEqualTo(LlmBudgetBlockReason.NONE);

        // A call at its provider holds its bound too, so the next one finds the cap reached.
        open(lease, lease.epoch(), PRICED, new BigDecimal("0.400000"));

        assertThat(budget.headroom(lease.workspaceId()).instanceSpentUsd()).isEqualByComparingTo("1.00");
        assertThat(budget.decide(lease.workspaceId()).forFunding(UsageFundingSource.INSTANCE))
                .isEqualTo(LlmBudgetBlockReason.EXHAUSTED);
    }

    @Test
    void shouldBlockTheCapWhenAnUnmeasuredCallHasNoUpperBound() {
        AgentRunLease lease = leasedRun("calls-no-bound-cap");
        jdbc.update("UPDATE workspace SET monthly_llm_budget_usd = 1.00 WHERE id = ?", lease.workspaceId());
        UUID call = open(lease, lease.epoch(), PRICED);
        complete(lease, call, null);

        assertThat(budget.decide(lease.workspaceId()).forFunding(UsageFundingSource.INSTANCE))
                .isEqualTo(LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED);
    }

    @Test
    void shouldNeverRepriceAnUnmeasuredCallThatCountsAtItsBound() {
        AgentRunLease lease = leasedRun("calls-bound-reprice");
        LlmConnection connection = connections.save(LlmCatalogTestFixtures.connection("calls-bound-conn"));
        LlmModel model = models.save(LlmCatalogTestFixtures.model(connection, "calls-bound-model", "gpt-bound"));
        LlmModelPrice price = new LlmModelPrice();
        price.setModel(model);
        price.setPricingMode(PricingMode.PRICED);
        price.setPer1mInputUsd(new BigDecimal("3.00"));
        price.setPer1mOutputUsd(new BigDecimal("9.00"));
        price.setEffectiveFrom(Instant.now().minus(1, ChronoUnit.DAYS));
        price = prices.saveAndFlush(price);
        var frozen = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE,
                UsagePricingState.PRICED,
                price.getId(),
                null,
                new BigDecimal("3.00"),
                new BigDecimal("9.00"),
                null,
                null);
        UUID call = open(lease, lease.epoch(), frozen, new BigDecimal("0.250000"));
        complete(lease, call, null);

        sweeper.repriceNow();

        assertThat(row(call))
                .containsEntry("measurement_verification", "UNVERIFIABLE")
                .containsEntry("pricing_state", "UNPRICED")
                .containsEntry("cost_usd", null);
        assertThat((BigDecimal) row(call).get("cost_bound_usd")).isEqualByComparingTo("0.25");
    }

    /** A run of a new workspace, leased by its first claim. */
    private AgentRunLease leasedRun(String slug) {
        Workspace workspace =
                createWorkspace(slug, "Calls " + slug, slug + "-org", AccountType.ORG, persistUser(slug + "-owner"));
        long workspaceId = workspace.getId();
        String subject = "calls:" + UUID.randomUUID();
        return Objects.requireNonNull(transactions.execute(tx -> {
            runs.enqueue(workspaceId, AgentRunKind.REVIEW, AgentRunBand.BATCH, subject, null);
            return runs.claimSubject(workspaceId, subject, Set.of(AgentRunKind.REVIEW))
                    .orElseThrow();
        }));
    }

    private static AgentRunFence fence(AgentRunLease lease) {
        return new AgentRunFence(lease.runId(), lease.workspaceId(), lease.epoch());
    }

    private UUID open(AgentRunLease lease, int epoch, LlmPriceSnapshot price) {
        return open(lease, epoch, price, null);
    }

    private UUID open(AgentRunLease lease, int epoch, LlmPriceSnapshot price, @Nullable BigDecimal bound) {
        return Objects.requireNonNull(transactions.execute(tx -> recorder.open(new LlmUsageRecorder.OpenCall(
                lease.workspaceId(),
                lease.runId(),
                epoch,
                "PRACTICE_REVIEW",
                UsageSourceType.REVIEW_JOB,
                UUID.randomUUID(),
                epoch - 1,
                null,
                ModelKind.CHAT,
                null,
                "gpt-calls",
                price,
                bound))));
    }

    private void complete(AgentRunLease lease, UUID call, LlmUsageRecorder.@Nullable CallUsage usage) {
        transactions.executeWithoutResult(tx -> recorder.complete(call, lease.workspaceId(), usage));
    }

    private Instant failedAt(UUID call) {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT failed_at FROM llm_usage_event WHERE id = ?", Timestamp.class, call))
                .toInstant();
    }

    private MentorTurn.FailedCall failure(UUID call) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT call_failure ->> 'kind', (call_failure ->> 'status')::integer FROM llm_usage_event WHERE id = ?",
                (rs, number) -> new MentorTurn.FailedCall(rs.getString(1), rs.getObject(2, Integer.class)),
                call));
    }

    private Instant occurredAt(UUID call) {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT occurred_at FROM llm_usage_event WHERE id = ?", Timestamp.class, call))
                .toInstant();
    }

    private Map<String, @Nullable Object> row(UUID call) {
        return jdbc.queryForMap(
                "SELECT call_state, run_id, input_tokens, output_tokens, total_calls, unreported_calls, usage_provenance,"
                        + " measurement_verification, pricing_state, cost_usd, cost_bound_usd FROM llm_usage_event"
                        + " WHERE id = ?",
                call);
    }
}
