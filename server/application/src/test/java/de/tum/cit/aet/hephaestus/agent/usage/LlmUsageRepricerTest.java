package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * The way out of a month that no cap can verify.
 *
 * <p>One UNPRICED row funded from a capped purse blocks every agent job in the workspace, and there was
 * no path back short of removing the cap, hand-editing the ledger, or waiting for the first of the
 * month. These tests hold the line on both halves of the answer: the price gets applied once the
 * catalogue has it, and nothing gets invented when it does not.
 */
class LlmUsageRepricerTest extends BaseUnitTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final long WORKSPACE_ID = 7L;

    @Mock
    private LlmUsageEventRepository usageRepository;

    @Mock
    private LlmModelRepository modelRepository;

    @Mock
    private LlmModelPriceRepository priceRepository;

    @Mock
    private WorkspaceLlmModelRepository workspaceModelRepository;

    private LlmUsageRepricer repricer;

    @BeforeEach
    void setUp() {
        repricer = new LlmUsageRepricer(usageRepository, modelRepository, priceRepository, workspaceModelRepository);
    }

    private static UnpricedLedgerRow row(
            @Nullable String model,
            UsageFundingSource funding,
            @Nullable Long appliedPriceId,
            @Nullable Long appliedWorkspaceModelId) {
        return new UnpricedLedgerRow(
                EVENT_ID,
                WORKSPACE_ID,
                model,
                funding,
                1_000_000L,
                0L,
                0L,
                0L,
                0L,
                appliedPriceId,
                appliedWorkspaceModelId,
                MeasurementVerification.VERIFIED,
                UsagePricingState.UNPRICED,
                null,
                null,
                null,
                null);
    }

    private static LlmModelPrice price(PricingMode mode, @Nullable BigDecimal input) {
        LlmModelPrice price = new LlmModelPrice();
        price.setId(11L);
        price.setPricingMode(mode);
        price.setPer1mInputUsd(input);
        return price;
    }

    private void repriceSucceeds() {
        when(usageRepository.applyResolvedPrice(any(), any(), any(), any())).thenReturn(1);
    }

    /** The price this reprice actually wrote, as one value — which is how the ledger stores it. */
    private LlmPriceSnapshot appliedPrice() {
        ArgumentCaptor<LlmPriceSnapshot> price = ArgumentCaptor.forClass(LlmPriceSnapshot.class);
        verify(usageRepository).applyResolvedPrice(eq(EVENT_ID), any(), price.capture(), any());
        return price.getValue();
    }

    @Test
    void shouldFillAMissingRateWithoutReplacingAKnownFrozenRate() {
        var incomplete = new UnpricedLedgerRow(
                EVENT_ID,
                WORKSPACE_ID,
                "model",
                UsageFundingSource.INSTANCE,
                1_000_000L,
                100_000L,
                0,
                0,
                0,
                11L,
                null,
                MeasurementVerification.VERIFIED,
                UsagePricingState.PRICED,
                new BigDecimal("2.00"),
                null,
                null,
                null);
        var changed = price(PricingMode.PRICED, new BigDecimal("20.00"));
        changed.setPer1mOutputUsd(new BigDecimal("8.00"));
        when(priceRepository.findById(11L)).thenReturn(Optional.of(changed));
        repriceSucceeds();
        assertThat(repricer.reprice(incomplete)).isEqualTo(LlmUsageRepricer.ResultOutcome.REPRICED);
        var applied = appliedPrice();
        assertThat(applied.per1mInputUsd()).isEqualByComparingTo("2.00");
        assertThat(applied.per1mOutputUsd()).isEqualByComparingTo("8.00");
    }

    @Test
    void shouldUseFrozenRatesWhenTheInitialPriceWasKnown() {
        var known = new UnpricedLedgerRow(
                EVENT_ID,
                WORKSPACE_ID,
                "byo-model",
                UsageFundingSource.WORKSPACE,
                1_000_000L,
                0,
                0,
                0,
                0,
                null,
                42L,
                MeasurementVerification.VERIFIED,
                UsagePricingState.PRICED,
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                null,
                null);
        repriceSucceeds();
        assertThat(repricer.reprice(known)).isEqualTo(LlmUsageRepricer.ResultOutcome.REPRICED);
        assertThat(appliedPrice().per1mInputUsd()).isEqualByComparingTo("2.00");
        verifyNoInteractions(workspaceModelRepository, modelRepository, priceRepository);
    }

    @Test
    @DisplayName("a row whose catalogue entry now has a price is priced from it")
    void repricesFromTheProvenanceId() {
        when(priceRepository.findById(11L)).thenReturn(Optional.of(price(PricingMode.PRICED, new BigDecimal("3.00"))));
        repriceSucceeds();

        LlmUsageRepricer.ResultOutcome outcome = repricer.reprice(row("gpt-5", UsageFundingSource.INSTANCE, 11L, null));

        assertThat(outcome).isEqualTo(LlmUsageRepricer.ResultOutcome.REPRICED);
        ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<LlmPriceSnapshot> price = ArgumentCaptor.forClass(LlmPriceSnapshot.class);
        verify(usageRepository).applyResolvedPrice(eq(EVENT_ID), cost.capture(), price.capture(), any());
        // One million input tokens at $3.00 per million.
        assertThat(cost.getValue()).isEqualByComparingTo("3.00");
        assertThat(price.getValue().pricingState()).isEqualTo(UsagePricingState.PRICED);
        // The row is priced by the catalogue entry it names, and the rate written back is that entry's.
        assertThat(price.getValue().appliedPriceId()).isEqualTo(11L);
        assertThat(price.getValue().per1mInputUsd()).isEqualByComparingTo("3.00");
    }

    // A Cohere reranking row billed search units and no tokens. A price per token cannot charge them, so
    // pricing its zero tokens would confirm $0 for a call that the provider charged.
    @Test
    @DisplayName("a row with only search units stays unpriced, because it holds no token to price")
    void aRowWithOnlySearchUnitsStaysUnpriced() {
        LlmUsageRepricer.ResultOutcome outcome = repricer.reprice(new UnpricedLedgerRow(
                EVENT_ID,
                WORKSPACE_ID,
                "rerank-v3.5",
                UsageFundingSource.INSTANCE,
                0L,
                0L,
                0L,
                0L,
                3L,
                11L,
                null,
                MeasurementVerification.VERIFIED,
                UsagePricingState.UNPRICED,
                null,
                null,
                null,
                null));

        assertThat(outcome).isEqualTo(LlmUsageRepricer.ResultOutcome.NO_TOKEN_EVIDENCE);
        verify(usageRepository, never()).applyResolvedPrice(any(), any(), any(), any());
    }

    // Tokens and search units on one row: the tokens have a rate, the search units do not.
    @Test
    @DisplayName("a row with tokens and search units stays unpriced, because no price has a rate for search units")
    void aRowWithTokensAndSearchUnitsStaysUnpriceable() {
        when(priceRepository.findById(11L)).thenReturn(Optional.of(price(PricingMode.PRICED, new BigDecimal("3.00"))));

        LlmUsageRepricer.ResultOutcome outcome = repricer.reprice(new UnpricedLedgerRow(
                EVENT_ID,
                WORKSPACE_ID,
                "rerank-v3.5",
                UsageFundingSource.INSTANCE,
                120L,
                0L,
                0L,
                0L,
                3L,
                11L,
                null,
                MeasurementVerification.VERIFIED,
                UsagePricingState.UNPRICED,
                null,
                null,
                null,
                null));

        assertThat(outcome).isEqualTo(LlmUsageRepricer.ResultOutcome.STILL_UNPRICEABLE);
        verify(usageRepository, never()).applyResolvedPrice(any(), any(), any(), any());
    }

    // Every row the ledger backfill created — the ones that actually held a live cap shut — carries no
    // provenance at all, because historical spend predates it. The model name is the only handle.
    @Test
    @DisplayName("a row with no provenance is priced through its model name")
    void repricesThroughTheModelName() {
        LlmModel model = new LlmModel();
        model.setId(4L);
        when(modelRepository.findByUpstreamModelId("gpt-5")).thenReturn(List.of(model));
        when(priceRepository.findByModelIdAndEffectiveToIsNull(4L))
                .thenReturn(Optional.of(price(PricingMode.PRICED, new BigDecimal("2.00"))));
        repriceSucceeds();

        assertThat(repricer.reprice(row("gpt-5", UsageFundingSource.INSTANCE, null, null)))
                .isEqualTo(LlmUsageRepricer.ResultOutcome.REPRICED);
    }

    // Two connections may serve the same upstream id at different prices. Picking one would put a number
    // into an append-only ledger that nobody can defend.
    @Test
    @DisplayName("an ambiguous model name is left unpriced rather than guessed")
    void refusesToGuessBetweenTwoModelsOfTheSameName() {
        when(modelRepository.findByUpstreamModelId("gpt-5")).thenReturn(List.of(new LlmModel(), new LlmModel()));

        LlmUsageRepricer.ResultOutcome outcome =
                repricer.reprice(row("gpt-5", UsageFundingSource.INSTANCE, null, null));

        assertThat(outcome).isEqualTo(LlmUsageRepricer.ResultOutcome.UNIDENTIFIABLE);
        verify(usageRepository, never()).applyResolvedPrice(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a row naming no model at all is reported as unidentifiable, not as free")
    void aRowWithNoModelIsUnidentifiable() {
        LlmUsageRepricer.ResultOutcome outcome = repricer.reprice(row(null, UsageFundingSource.INSTANCE, null, null));

        assertThat(outcome).isEqualTo(LlmUsageRepricer.ResultOutcome.UNIDENTIFIABLE);
        verifyNoInteractions(usageRepository);
    }

    @Test
    @DisplayName("a catalogue entry that is still unpriced leaves the row alone")
    void stillUnpricedCatalogueEntryChangesNothing() {
        when(priceRepository.findById(11L)).thenReturn(Optional.of(price(PricingMode.UNPRICED, null)));

        LlmUsageRepricer.ResultOutcome outcome = repricer.reprice(row("gpt-5", UsageFundingSource.INSTANCE, 11L, null));

        assertThat(outcome).isEqualTo(LlmUsageRepricer.ResultOutcome.STILL_UNPRICEABLE);
        verify(usageRepository, never()).applyResolvedPrice(any(), any(), any(), any());
    }

    // Charging only the buckets a partial price covers would under-bill silently, which is the exact
    // thing UNPRICED exists to make loud.
    @Test
    @DisplayName("a price missing the rate for a bucket this row used is not applied")
    void aPartialPriceIsNotApplied() {
        when(priceRepository.findById(11L)).thenReturn(Optional.of(price(PricingMode.PRICED, null)));

        assertThat(repricer.reprice(row("gpt-5", UsageFundingSource.INSTANCE, 11L, null)))
                .isEqualTo(LlmUsageRepricer.ResultOutcome.STILL_UNPRICEABLE);
    }

    @Test
    @DisplayName("a model the catalogue marks free is settled as a real zero, not left blocking the month")
    void noChargeIsSettledRatherThanLeftUnpriced() {
        when(priceRepository.findById(11L)).thenReturn(Optional.of(price(PricingMode.NO_CHARGE, null)));
        repriceSucceeds();

        LlmUsageRepricer.ResultOutcome outcome = repricer.reprice(row("gpt-5", UsageFundingSource.INSTANCE, 11L, null));

        assertThat(outcome).isEqualTo(LlmUsageRepricer.ResultOutcome.REPRICED);
        assertThat(appliedPrice().pricingState()).isEqualTo(UsagePricingState.NO_CHARGE);
    }

    @Test
    @DisplayName("a row with no measured token stays unknown even when its model is free")
    void aRowWithNoTokenEvidenceIsNeverPriced() {
        var unmeasured = new UnpricedLedgerRow(
                EVENT_ID,
                WORKSPACE_ID,
                "gpt-5",
                UsageFundingSource.INSTANCE,
                0L,
                0L,
                0L,
                0L,
                0L,
                11L,
                null,
                MeasurementVerification.VERIFIED,
                UsagePricingState.UNPRICED,
                null,
                null,
                null,
                null);

        assertThat(repricer.reprice(unmeasured)).isEqualTo(LlmUsageRepricer.ResultOutcome.NO_TOKEN_EVIDENCE);
        verifyNoInteractions(usageRepository, priceRepository, modelRepository, workspaceModelRepository);
    }

    @ParameterizedTest
    @EnumSource(
            value = MeasurementVerification.class,
            names = {"UNKNOWN", "UNVERIFIABLE"})
    void shouldRefusePositiveMeasurementsWhenVerificationWasNotAccepted(MeasurementVerification verification) {
        var row = new UnpricedLedgerRow(
                EVENT_ID,
                WORKSPACE_ID,
                "gpt-5",
                UsageFundingSource.INSTANCE,
                1_000_000L,
                0L,
                0L,
                0L,
                0L,
                11L,
                null,
                verification,
                UsagePricingState.UNPRICED,
                null,
                null,
                null,
                null);

        assertThat(repricer.reprice(row)).isEqualTo(LlmUsageRepricer.ResultOutcome.UNVERIFIED_MEASUREMENT);
        verifyNoInteractions(usageRepository, priceRepository, modelRepository, workspaceModelRepository);
    }

    // The UPDATE is fenced on pricing_state = 'UNPRICED', so a row another pod already priced returns 0
    // rows. Reporting that as a reprice would tell an operator the block had lifted when it had not.
    @Test
    @DisplayName("losing the race to another pod is not reported as a reprice")
    void aLostRaceIsNotReportedAsARepricing() {
        when(priceRepository.findById(11L)).thenReturn(Optional.of(price(PricingMode.PRICED, new BigDecimal("3.00"))));
        when(usageRepository.applyResolvedPrice(any(), any(), any(), any())).thenReturn(0);

        assertThat(repricer.reprice(row("gpt-5", UsageFundingSource.INSTANCE, 11L, null)))
                .isEqualTo(LlmUsageRepricer.ResultOutcome.STILL_UNPRICEABLE);
    }

    @Test
    @DisplayName("an own-provider row is priced from the workspace's own model, never the instance catalogue")
    void ownProviderRowsUseTheWorkspaceCatalogue() {
        WorkspaceLlmModel model = new WorkspaceLlmModel();
        model.setId(21L);
        model.setPricingMode(PricingMode.PRICED);
        model.setPer1mInputUsd(new BigDecimal("1.50"));
        when(workspaceModelRepository.findByIdAndWorkspaceId(21L, WORKSPACE_ID)).thenReturn(Optional.of(model));
        repriceSucceeds();

        LlmUsageRepricer.ResultOutcome outcome =
                repricer.reprice(row("own-model", UsageFundingSource.WORKSPACE, null, 21L));

        assertThat(outcome).isEqualTo(LlmUsageRepricer.ResultOutcome.REPRICED);
        verify(modelRepository, never()).findByUpstreamModelId(any());
        verify(priceRepository, never()).findByModelIdAndEffectiveToIsNull(anyLong());
    }
}
