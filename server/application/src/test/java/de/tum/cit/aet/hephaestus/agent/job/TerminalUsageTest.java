package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult.LlmUsage;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.MeasurementVerification;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageProvenance;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class TerminalUsageTest extends BaseUnitTest {

    private static LlmUsage runner(int input, int output, int calls) {
        return new LlmUsage("m", input, output, 0, 0, 0, 0.0, calls);
    }

    @Test
    @DisplayName("a runner report that saw more than the proxy is billed in full")
    void runnerReportWins() {
        TerminalUsage usage =
                TerminalUsage.resolve(runner(1000, 700, 6), new ReviewJobLlmUsage(4, 900, 600, 0, 0, 0), List.of());

        assertThat(usage.verifiable()).isTrue();
        assertThat(usage.totalCalls()).isEqualTo(6);
        assertThat(usage.inputTokens()).isEqualTo(1000);
        assertThat(usage.outputTokens()).isEqualTo(700);
        assertThat(usage.provenance()).isEqualTo(UsageProvenance.RUNNER);
    }

    // The live defect, at its measured scale. The proxy forwarded 143 calls and 4,274,916 input tokens;
    // the runner's report — derived by walking a compacted session's surviving messages — claimed 26 and
    // 969,765. Preferring the runner because it was non-zero billed a quarter of the spend, and the
    // monthly cap reads this ledger.
    @Test
    @DisplayName("compaction eating the runner's calls no longer under-bills: the proxy's larger count wins")
    void aCompactedRunnerReportDoesNotBeatTheProxy() {
        TerminalUsage usage = TerminalUsage.resolve(
                runner(969_765, 120_000, 26), new ReviewJobLlmUsage(143, 4_274_916, 500_000, 0, 0, 0), List.of());

        assertThat(usage.totalCalls()).isEqualTo(143);
        assertThat(usage.inputTokens()).isEqualTo(4_274_916);
        assertThat(usage.outputTokens()).isEqualTo(500_000);
        assertThat(usage.provenance()).isEqualTo(UsageProvenance.PROXY);
    }

    // The runner counted cache writes as ordinary input while the proxy separated them. Both prompts total
    // 71,224 tokens; taking the larger of each bucket would store 116,824.
    @Test
    @DisplayName("cache writes the runner counted as input are stored once, from the proxy")
    void cacheWritesAreNotCountedTwiceWhenTheSourcesClassifyThemDifferently() {
        TerminalUsage usage = TerminalUsage.resolve(
                new LlmUsage("m", 49_624, 900, 0, 21_600, 0, 0.0, 3),
                new ReviewJobLlmUsage(3, 4_024, 900, 0, 21_600, 45_600),
                List.of());

        assertThat(usage.verifiable()).isTrue();
        assertThat(usage.provenance()).isEqualTo(UsageProvenance.PROXY);
        assertThat(usage.inputTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens())
                .isEqualTo(71_224);
        assertThat(usage.inputTokens()).isEqualTo(4_024);
        assertThat(usage.cacheWriteTokens()).isEqualTo(45_600);
    }

    @Test
    @DisplayName("a runner whose totals cover the proxy's is stored whole, cache classification included")
    void aCoveringRunnerReportIsStoredWhole() {
        TerminalUsage usage = TerminalUsage.resolve(
                new LlmUsage("m", 5_000, 900, 40, 21_600, 45_600, 0.0, 4),
                new ReviewJobLlmUsage(3, 49_624, 800, 0, 21_600, 0),
                List.of());

        assertThat(usage.verifiable()).isTrue();
        assertThat(usage.provenance()).isEqualTo(UsageProvenance.RUNNER);
        assertThat(usage.inputTokens()).isEqualTo(5_000);
        assertThat(usage.cacheWriteTokens()).isEqualTo(45_600);
        assertThat(usage.reasoningTokens()).isEqualTo(40);
        assertThat(usage.totalCalls()).isEqualTo(4);
    }

    // A larger prompt on one side and a larger output on the other means each recorded something the other
    // did not. Their aggregates cannot be merged into the spend, so the row admits it is unverifiable.
    @Test
    @DisplayName("incomparable totals keep the proxy's numbers whole and are unverifiable")
    void incomparableTotalsAreUnverifiable() {
        TerminalUsage usage = TerminalUsage.resolve(
                new LlmUsage("m", 100, 4_000, 7, 50, 900, 0.0, 3),
                new ReviewJobLlmUsage(9, 8_000, 200, 11, 20, 0),
                List.of());

        assertThat(usage.verifiable()).isFalse();
        assertThat(usage.provenance()).isEqualTo(UsageProvenance.PROXY);
        assertThat(usage.inputTokens()).isEqualTo(8_000);
        assertThat(usage.outputTokens()).isEqualTo(200);
        assertThat(usage.reasoningTokens()).isEqualTo(11);
        assertThat(usage.cacheReadTokens()).isEqualTo(20);
        assertThat(usage.cacheWriteTokens()).isZero();
        assertThat(usage.totalCalls()).isEqualTo(9);
    }

    @Test
    @DisplayName("more runner calls do not make the runner the source when its totals are smaller")
    void callCountsAreNotCoverage() {
        TerminalUsage usage =
                TerminalUsage.resolve(runner(10, 20, 50), new ReviewJobLlmUsage(2, 900, 600, 0, 0, 0), List.of());

        assertThat(usage.provenance()).isEqualTo(UsageProvenance.PROXY);
        assertThat(usage.inputTokens()).isEqualTo(900);
        assertThat(usage.totalCalls()).isEqualTo(50);
    }

    // The practice script stage runs before the runner, in another process, so the runner's report never holds
    // its chat calls. The proxy counted them in the job's counters, but here it missed review calls.
    @Test
    @DisplayName("a selected runner report also bills the practice script chat calls it could not see")
    void aSelectedRunnerReportAlsoBillsThePracticeScriptChatCalls() {
        TerminalUsage usage = TerminalUsage.resolve(
                runner(1000, 700, 6),
                new ReviewJobLlmUsage(5, 800, 400, 0, 0, 0),
                List.of(
                        new PracticeScriptKindTotal(ModelKind.CHAT, 2, 300, 100, 0, 0, 0, 2),
                        new PracticeScriptKindTotal(ModelKind.DECISION, 5, 50_000, 9_000, 0, 0, 0, 5)));

        assertThat(usage.verifiable()).isTrue();
        assertThat(usage.provenance()).isEqualTo(UsageProvenance.RUNNER);
        assertThat(usage.inputTokens()).isEqualTo(1300);
        assertThat(usage.outputTokens()).isEqualTo(800);
        assertThat(usage.totalCalls()).isEqualTo(8);
    }

    @Test
    @DisplayName("a selected runner report keeps the practice script chat calls' cache reads in their own bucket")
    void aSelectedRunnerReportKeepsThePracticeScriptCacheReadsApart() {
        TerminalUsage usage = TerminalUsage.resolve(
                runner(1000, 700, 6),
                new ReviewJobLlmUsage(5, 800, 400, 0, 0, 0),
                List.of(new PracticeScriptKindTotal(ModelKind.CHAT, 2, 40, 100, 260, 0, 0, 2)));

        assertThat(usage.provenance()).isEqualTo(UsageProvenance.RUNNER);
        assertThat(usage.inputTokens()).isEqualTo(1040);
        assertThat(usage.cacheReadTokens()).isEqualTo(260);
        assertThat(usage.outputTokens()).isEqualTo(800);
    }

    @Test
    @DisplayName("a selected proxy already holds the practice script chat calls and does not count them twice")
    void aSelectedProxyDoesNotCountThePracticeScriptChatCallsTwice() {
        TerminalUsage usage = TerminalUsage.resolve(
                runner(1000, 700, 6),
                new ReviewJobLlmUsage(8, 1300, 800, 0, 0, 0),
                List.of(new PracticeScriptKindTotal(ModelKind.CHAT, 2, 300, 100, 0, 0, 0, 2)));

        assertThat(usage.verifiable()).isTrue();
        assertThat(usage.provenance()).isEqualTo(UsageProvenance.PROXY);
        assertThat(usage.inputTokens()).isEqualTo(1300);
        assertThat(usage.outputTokens()).isEqualTo(800);
        assertThat(usage.totalCalls()).isEqualTo(8);
    }

    @Test
    @DisplayName("no runner report at all falls back to the proxy accumulators")
    void missingRunnerReportFallsBackToTheProxy() {
        TerminalUsage usage = TerminalUsage.fromProxy(new ReviewJobLlmUsage(4, 900, 600, 30, 100, 0));

        assertThat(usage.verifiable()).isTrue();
        assertThat(usage.totalCalls()).isEqualTo(4);
        assertThat(usage.inputTokens()).isEqualTo(900);
        assertThat(usage.cacheReadTokens()).isEqualTo(100);
        assertThat(usage.reasoningTokens()).isEqualTo(30);
        assertThat(usage.provenance()).isEqualTo(UsageProvenance.PROXY);
    }

    @Test
    @DisplayName("a runner report claiming calls but no tokens is not evidence — the proxy is")
    void runnerReportWithZeroTokensFallsBackToTheProxy() {
        TerminalUsage usage =
                TerminalUsage.resolve(runner(0, 0, 3), new ReviewJobLlmUsage(4, 900, 600, 0, 0, 0), List.of());

        assertThat(usage.verifiable()).isTrue();
        assertThat(usage.totalCalls()).isEqualTo(4);
        assertThat(usage.inputTokens()).isEqualTo(900);
    }

    @Test
    @DisplayName("null token fields in the runner report do not read as spend")
    void runnerReportWithNullTokenFieldsFallsBackToTheProxy() {
        LlmUsage malformed = new LlmUsage("m", null, null, null, null, null, null, 2);

        TerminalUsage usage = TerminalUsage.resolve(malformed, new ReviewJobLlmUsage(1, 10, 20, 0, 0, 0), List.of());

        assertThat(usage.verifiable()).isTrue();
        assertThat(usage.inputTokens()).isEqualTo(10);
    }

    @Test
    @DisplayName("neither record has tokens: zero spend, and say so as unverifiable")
    void nothingToBillIsReportedAsUnverifiable() {
        TerminalUsage usage = TerminalUsage.fromProxy(new ReviewJobLlmUsage(0, 0, 0, 0, 0, 0));

        assertThat(usage.verifiable()).isFalse();
        assertThat(usage.inputTokens()).isZero();
        assertThat(usage.totalCalls()).isZero();
        assertThat(usage.provenance()).isEqualTo(UsageProvenance.NONE);
    }

    @Test
    @DisplayName("an unverifiable attempt still keeps the reported call count as telemetry")
    void unverifiableAttemptKeepsTheReportedCallCount() {
        TerminalUsage usage = TerminalUsage.resolve(runner(0, 0, 3), null, List.of());

        assertThat(usage.verifiable()).isFalse();
        assertThat(usage.totalCalls()).isEqualTo(3);
        assertThat(usage.outputTokens()).isZero();
    }

    @Test
    @DisplayName("both records absent is unverifiable, not a crash")
    void bothRecordsAbsentIsUnverifiableNotACrash() {
        TerminalUsage usage = TerminalUsage.fromProxy(null);

        assertThat(usage.verifiable()).isFalse();
        assertThat(usage.totalCalls()).isZero();
    }

    @Test
    void shouldKeepMixedChatUsageUnverifiableWhenOneDirectCallReportsNoUsage() {
        TerminalUsage usage = TerminalUsage.resolve(
                runner(100, 20, 1),
                new ReviewJobLlmUsage(2, 110, 20, 0, 0, 0),
                List.of(new PracticeScriptKindTotal(ModelKind.CHAT, 2, 10, 0, 0, 0, 0, 1)));

        assertThat(usage.verifiable()).isFalse();
        assertThat(usage.inputTokens()).isEqualTo(110);
        assertThat(usage.totalCalls()).isEqualTo(3);
    }

    @Test
    void shouldVerifyZeroWhenTheProxyRecordedAReportedZero() {
        TerminalUsage usage = TerminalUsage.fromProxy(new ReviewJobLlmUsage(1, 0, 0, 0, 0, 0));

        assertThat(usage.verifiable()).isTrue();
        assertThat(usage.totalCalls()).isEqualTo(1);
        assertThat(usage.inputTokens()).isZero();
        assertThat(usage.outputTokens()).isZero();
        assertThat(usage.provenance()).isEqualTo(UsageProvenance.PROXY);
    }

    @Nested
    @DisplayName("appendTo picks the append path from the evidence, not from the call site")
    class AppendPath {

        private final LlmUsageRecorder recorder = mock(LlmUsageRecorder.class);
        private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
        private final ConfigSnapshot snapshot = new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                new FrozenModel(
                        LlmApiProtocol.OPENAI_COMPLETIONS,
                        "https://api.openai.com/v1",
                        "gpt-5",
                        UsageFundingSource.INSTANCE,
                        1L,
                        2L,
                        7L,
                        null,
                        null,
                        null,
                        false),
                null,
                null,
                600,
                false,
                null);

        static Stream<Arguments> billable() {
            return Stream.of(Arguments.argumentSet("observed tokens at a price", observed(), pricedInstance()));
        }

        @ParameterizedTest
        @CsvSource({"40,1,PRICED,false", "0,2,PRICED,true", "0,0,PRICED,false", "40,1,NO_CHARGE,false"})
        void shouldChooseTheLedgerStateFromCompletenessWhenDirectModelCallsFinish(
                long tokens, long reportedCalls, UsagePricingState pricingState, boolean billable) {
            UUID jobId = UUID.randomUUID();
            LlmPriceSnapshot price = pricingState == UsagePricingState.NO_CHARGE
                    ? new LlmPriceSnapshot(
                            UsageFundingSource.INSTANCE,
                            UsagePricingState.NO_CHARGE,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null)
                    : pricedInstance();
            ConfigSnapshot withEmbedding = snapshot.withPracticeScript(Map.of(
                    ModelKind.EMBEDDING,
                    new FrozenModel(
                            LlmApiProtocol.OPENAI_EMBEDDINGS,
                            "https://models.example/v1",
                            "embedding",
                            UsageFundingSource.INSTANCE,
                            1L,
                            2L,
                            7L,
                            null,
                            null,
                            price,
                            false)));
            when(jobs.sumPracticeScriptUsageByKind(7L, jobId, 1))
                    .thenReturn(List.of(
                            new PracticeScriptKindTotal(ModelKind.EMBEDDING, 2, tokens, 0, 0, 0, 0, reportedCalls)));

            TerminalUsageTestFixtures.project(
                    TerminalUsage.none(),
                    recorder,
                    jobs,
                    7L,
                    jobFor(jobId, 1),
                    withEmbedding,
                    LlmPriceSnapshot.unpricedInstance());

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(recorder).project(eq(7L), sample.capture());
            var embedding = sample.getValue();
            assertThat(embedding.sourceType()).isEqualTo(UsageSourceType.PRACTICE_SCRIPT_EMBEDDING);
            assertThat(embedding.totalCalls()).isEqualTo(2);
            assertThat(embedding.inputTokens()).isEqualTo(tokens);
            assertThat(embedding.measurementVerification())
                    .isEqualTo(billable ? MeasurementVerification.VERIFIED : MeasurementVerification.UNVERIFIABLE);
        }

        @Test
        void shouldKeepTerminalChatUsageUnverifiableWhenAStoppedAttemptHasAnUnknownDirectCall() {
            UUID jobId = UUID.randomUUID();
            when(jobs.sumPracticeScriptUsageByKind(7L, jobId, 1))
                    .thenReturn(List.of(new PracticeScriptKindTotal(ModelKind.CHAT, 2, 40, 0, 0, 0, 0, 1)));

            boolean billed = TerminalUsageTestFixtures.project(
                    TerminalUsage.fromProxy(new ReviewJobLlmUsage(1, 40, 0, 0, 0, 0)),
                    recorder,
                    jobs,
                    7L,
                    jobFor(jobId, 1),
                    snapshot,
                    pricedInstance());

            assertThat(billed).isFalse();
            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(recorder).project(eq(7L), sample.capture());
            assertThat(sample.getValue().inputTokens()).isEqualTo(40);
        }

        @Test
        void shouldOmitTheLedgerRowWhenNeitherSourceRecordedAModelCall() {
            boolean settled = TerminalUsageTestFixtures.project(
                    TerminalUsage.resolve(runner(0, 0, 0), new ReviewJobLlmUsage(0, 0, 0, 0, 0, 0), List.of()),
                    recorder,
                    jobs,
                    7L,
                    jobFor(UUID.randomUUID(), 1),
                    snapshot,
                    pricedInstance());

            assertThat(settled).isTrue();
            verifyNoInteractions(recorder);
        }

        @Test
        void shouldKeepAnUnpricedRowWhenOnlyADirectChatCallProvesUsage() {
            UUID jobId = UUID.randomUUID();
            when(jobs.sumPracticeScriptUsageByKind(7L, jobId, 1))
                    .thenReturn(List.of(new PracticeScriptKindTotal(ModelKind.CHAT, 2, 0, 0, 0, 0, 0, 0)));

            assertThat(TerminalUsageTestFixtures.project(
                            TerminalUsage.none(), recorder, jobs, 7L, jobFor(jobId, 1), snapshot, pricedInstance()))
                    .isFalse();

            var captured = ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(recorder).project(eq(7L), captured.capture());
            assertThat(captured.getValue().totalCalls()).isEqualTo(2);
            assertThat(captured.getValue().measurementVerification()).isEqualTo(MeasurementVerification.UNVERIFIABLE);
        }

        static Stream<Arguments> unbillable() {
            return Stream.of(
                    Arguments.argumentSet(
                            "calls without tokens at no charge",
                            TerminalUsage.resolve(runner(0, 0, 2), null, List.of()),
                            new LlmPriceSnapshot(
                                    UsageFundingSource.INSTANCE,
                                    UsagePricingState.NO_CHARGE,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null)),
                    Arguments.argumentSet(
                            "observed tokens without a price", observed(), LlmPriceSnapshot.unpricedInstance()),
                    Arguments.argumentSet(
                            "calls without tokens at a price",
                            TerminalUsage.resolve(runner(0, 0, 2), null, List.of()),
                            pricedInstance()),
                    Arguments.argumentSet(
                            "calls without tokens or a price",
                            TerminalUsage.resolve(runner(0, 0, 2), null, List.of()),
                            LlmPriceSnapshot.unpricedInstance()));
        }

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("billable")
        void billsWhenThePriceCanChargeWhatWasObserved(TerminalUsage usage, LlmPriceSnapshot price) {
            assertThat(TerminalUsageTestFixtures.project(
                            usage, recorder, jobs, 7L, jobFor(UUID.randomUUID(), 2), snapshot, price))
                    .isTrue();

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(recorder).project(eq(7L), sample.capture());
            assertThat(sample.getValue().sourceType()).isEqualTo(UsageSourceType.REVIEW_JOB);
            assertThat(sample.getValue().model()).isEqualTo("gpt-5");
            assertThat(sample.getValue().provenance()).isNotNull();
        }

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("unbillable")
        void appendsUnpricedWhenThePriceCannotChargeWhatWasObserved(TerminalUsage usage, LlmPriceSnapshot price) {
            assertThat(TerminalUsageTestFixtures.project(
                            usage, recorder, jobs, 7L, jobFor(UUID.randomUUID(), 2), snapshot, price))
                    .isFalse();

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(recorder).project(eq(7L), sample.capture());
            assertThat(sample.getValue().sourceType()).isEqualTo(UsageSourceType.REVIEW_JOB);
            assertThat(sample.getValue().model()).isEqualTo("gpt-5");
            // An unpriced row's tokens are still the evidence somebody will reconcile once a price exists.
            assertThat(sample.getValue().provenance()).isNotNull();
        }

        private static TerminalUsage observed() {
            return TerminalUsage.resolve(runner(1000, 700, 6), null, List.of());
        }

        @Test
        @DisplayName("the row is keyed by the job's own id and attempt, so a retry bills separately")
        void theRowIsKeyedByJobAndAttemptSoARetryBillsSeparately() {
            UUID jobId = UUID.randomUUID();

            TerminalUsageTestFixtures.project(
                    TerminalUsage.resolve(runner(10, 20, 1), null, List.of()),
                    recorder,
                    jobs,
                    7L,
                    jobFor(jobId, 3),
                    snapshot,
                    pricedInstance());

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(recorder).project(eq(7L), sample.capture());
            assertThat(sample.getValue().sourceId()).isEqualTo(jobId);
            assertThat(sample.getValue().sourceAttempt()).isEqualTo(3);
        }

        @Test
        @DisplayName("incomparable totals take the unverifiable path even with a frozen price")
        void incomparableTotalsAreNotBilled() {
            LlmPriceSnapshot price = pricedInstance();

            boolean billed = TerminalUsageTestFixtures.project(
                    TerminalUsage.resolve(
                            new LlmUsage("m", 100, 4_000, 0, 0, 0, 0.0, 3),
                            new ReviewJobLlmUsage(9, 8_000, 200, 0, 0, 0),
                            List.of()),
                    recorder,
                    jobs,
                    7L,
                    jobFor(UUID.randomUUID(), 1),
                    snapshot,
                    price);

            assertThat(billed).isFalse();
            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(recorder).project(eq(7L), sample.capture());
            assertThat(sample.getValue().measurementVerification()).isEqualTo(MeasurementVerification.UNVERIFIABLE);
            assertThat(sample.getValue().inputTokens()).isEqualTo(8_000);
            assertThat(sample.getValue().outputTokens()).isEqualTo(200);
            assertThat(sample.getValue().price()).isSameAs(price);
            assertThat(sample.getValue().provenance()).isEqualTo(UsageProvenance.PROXY);
        }

        @Test
        void incomparableUsageAtAFreeModelHasUnavailableCost() {
            var price = new LlmPriceSnapshot(
                    UsageFundingSource.INSTANCE, UsagePricingState.NO_CHARGE, null, null, null, null, null, null);
            boolean billed = TerminalUsageTestFixtures.project(
                    TerminalUsage.resolve(
                            new LlmUsage("m", 100, 4_000, 0, 0, 0, 0.0, 3),
                            new ReviewJobLlmUsage(9, 8_000, 200, 0, 0, 0),
                            List.of()),
                    recorder,
                    jobs,
                    7L,
                    jobFor(UUID.randomUUID(), 1),
                    snapshot,
                    price);
            assertThat(billed).isFalse();
            var captured = ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(recorder).project(eq(7L), captured.capture());
            assertThat(captured.getValue().measurementVerification()).isEqualTo(MeasurementVerification.UNVERIFIABLE);
            assertThat(captured.getValue().inputTokens()).isEqualTo(8_000);
            assertThat(captured.getValue().outputTokens()).isEqualTo(200);
            assertThat(captured.getValue().provenance()).isEqualTo(UsageProvenance.PROXY);
        }

        @Test
        @DisplayName("token counts survive onto an unpriced row")
        void unpricedRowKeepsItsTokenCounts() {
            TerminalUsageTestFixtures.project(
                    TerminalUsage.resolve(runner(900, 600, 4), null, List.of()),
                    recorder,
                    jobs,
                    7L,
                    jobFor(UUID.randomUUID(), 0),
                    snapshot,
                    LlmPriceSnapshot.unpricedInstance());

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(recorder).project(eq(7L), sample.capture());
            assertThat(sample.getValue().measurementVerification()).isEqualTo(MeasurementVerification.VERIFIED);
            assertThat(sample.getValue().inputTokens()).isEqualTo(900);
            assertThat(sample.getValue().totalCalls()).isEqualTo(4);
        }

        @Test
        @DisplayName("each practice script kind other than chat becomes one row with the total over its practices")
        void eachPracticeScriptKindBecomesOneRowWithItsTotal() {
            UUID jobId = UUID.randomUUID();
            when(jobs.sumPracticeScriptUsageByKind(7L, jobId, 1))
                    .thenReturn(List.of(
                            new PracticeScriptKindTotal(ModelKind.CHAT, 2, 70, 20, 0, 0, 0, 2),
                            new PracticeScriptKindTotal(ModelKind.DECISION, 5, 500_000, 100_000, 0, 0, 0, 5)));

            TerminalUsageTestFixtures.project(
                    TerminalUsage.fromProxy(new ReviewJobLlmUsage(2, 70, 20, 0, 0, 0)),
                    recorder,
                    jobs,
                    7L,
                    jobFor(jobId, 1),
                    snapshot,
                    pricedInstance());

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(recorder, times(2)).project(eq(7L), sample.capture());
            LlmUsageRecorder.LlmUsageSample decision = sample.getAllValues().get(1);
            assertThat(decision.sourceType()).isEqualTo(UsageSourceType.PRACTICE_SCRIPT_DECISION);
            assertThat(decision.sourceAttempt()).isEqualTo(1);
            assertThat(decision.totalCalls()).isEqualTo(5);
            assertThat(decision.inputTokens()).isEqualTo(500_000);
            assertThat(decision.outputTokens()).isEqualTo(100_000);
            // One instant for the attempt: its rows can never fall into two months.
            assertThat(decision.occurredAt())
                    .isEqualTo(sample.getAllValues().getFirst().occurredAt());
        }

        private ReviewJob jobFor(UUID jobId, int attempt) {
            ReviewJob job = new ReviewJob();
            job.setId(jobId);

            job.setAttempt(attempt);
            return job;
        }

        private static LlmPriceSnapshot pricedInstance() {
            return new LlmPriceSnapshot(
                    UsageFundingSource.INSTANCE,
                    UsagePricingState.PRICED,
                    1L,
                    null,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO);
        }
    }
}
