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
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRepricer.Outcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
            FundingSource funding,
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
                appliedPriceId,
                appliedWorkspaceModelId);
    }

    private static LlmModelPrice price(PricingMode mode, @Nullable BigDecimal input) {
        LlmModelPrice price = new LlmModelPrice();
        price.setId(11L);
        price.setPricingMode(mode);
        price.setPer1mInputUsd(input);
        return price;
    }

    private void repriceSucceeds() {
        when(usageRepository.applyResolvedPrice(any(), any(), any())).thenReturn(1);
    }

    /** The price this reprice actually wrote, as one value — which is how the ledger stores it. */
    private LlmPriceSnapshot appliedPrice() {
        ArgumentCaptor<LlmPriceSnapshot> price = ArgumentCaptor.forClass(LlmPriceSnapshot.class);
        verify(usageRepository).applyResolvedPrice(eq(EVENT_ID), any(), price.capture());
        return price.getValue();
    }

    @Test
    @DisplayName("a row whose catalogue entry now has a price is priced from it")
    void repricesFromTheProvenanceId() {
        when(priceRepository.findById(11L)).thenReturn(Optional.of(price(PricingMode.PRICED, new BigDecimal("3.00"))));
        repriceSucceeds();

        Outcome outcome = repricer.reprice(row("gpt-5", FundingSource.INSTANCE, 11L, null));

        assertThat(outcome).isEqualTo(Outcome.REPRICED);
        ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<LlmPriceSnapshot> price = ArgumentCaptor.forClass(LlmPriceSnapshot.class);
        verify(usageRepository).applyResolvedPrice(eq(EVENT_ID), cost.capture(), price.capture());
        // One million input tokens at $3.00 per million.
        assertThat(cost.getValue()).isEqualByComparingTo("3.00");
        assertThat(price.getValue().pricingState()).isEqualTo(PricingState.PRICED);
        // The row is priced by the catalogue entry it names, and the rate written back is that entry's.
        assertThat(price.getValue().appliedPriceId()).isEqualTo(11L);
        assertThat(price.getValue().per1mInputUsd()).isEqualByComparingTo("3.00");
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

        assertThat(repricer.reprice(row("gpt-5", FundingSource.INSTANCE, null, null)))
                .isEqualTo(Outcome.REPRICED);
    }

    // Two connections may serve the same upstream id at different prices. Picking one would put a number
    // into an append-only ledger that nobody can defend.
    @Test
    @DisplayName("an ambiguous model name is left unpriced rather than guessed")
    void refusesToGuessBetweenTwoModelsOfTheSameName() {
        when(modelRepository.findByUpstreamModelId("gpt-5")).thenReturn(List.of(new LlmModel(), new LlmModel()));

        Outcome outcome = repricer.reprice(row("gpt-5", FundingSource.INSTANCE, null, null));

        assertThat(outcome).isEqualTo(Outcome.UNIDENTIFIABLE);
        verify(usageRepository, never()).applyResolvedPrice(any(), any(), any());
    }

    @Test
    @DisplayName("a row naming no model at all is reported as unidentifiable, not as free")
    void aRowWithNoModelIsUnidentifiable() {
        Outcome outcome = repricer.reprice(row(null, FundingSource.INSTANCE, null, null));

        assertThat(outcome).isEqualTo(Outcome.UNIDENTIFIABLE);
        verifyNoInteractions(usageRepository);
    }

    @Test
    @DisplayName("a catalogue entry that is still unpriced leaves the row alone")
    void stillUnpricedCatalogueEntryChangesNothing() {
        when(priceRepository.findById(11L)).thenReturn(Optional.of(price(PricingMode.UNPRICED, null)));

        Outcome outcome = repricer.reprice(row("gpt-5", FundingSource.INSTANCE, 11L, null));

        assertThat(outcome).isEqualTo(Outcome.STILL_UNPRICEABLE);
        verify(usageRepository, never()).applyResolvedPrice(any(), any(), any());
    }

    // Charging only the buckets a partial price covers would under-bill silently, which is the exact
    // thing UNPRICED exists to make loud.
    @Test
    @DisplayName("a price missing the rate for a bucket this row used is not applied")
    void aPartialPriceIsNotApplied() {
        when(priceRepository.findById(11L)).thenReturn(Optional.of(price(PricingMode.PRICED, null)));

        assertThat(repricer.reprice(row("gpt-5", FundingSource.INSTANCE, 11L, null)))
                .isEqualTo(Outcome.STILL_UNPRICEABLE);
    }

    @Test
    @DisplayName("a model the catalogue marks free is settled as a real zero, not left blocking the month")
    void noChargeIsSettledRatherThanLeftUnpriced() {
        when(priceRepository.findById(11L)).thenReturn(Optional.of(price(PricingMode.NO_CHARGE, null)));
        repriceSucceeds();

        Outcome outcome = repricer.reprice(row("gpt-5", FundingSource.INSTANCE, 11L, null));

        assertThat(outcome).isEqualTo(Outcome.REPRICED);
        assertThat(appliedPrice().pricingState()).isEqualTo(PricingState.NO_CHARGE);
    }

    // The UPDATE is fenced on pricing_state = 'UNPRICED', so a row another pod already priced returns 0
    // rows. Reporting that as a reprice would tell an operator the block had lifted when it had not.
    @Test
    @DisplayName("losing the race to another pod is not reported as a reprice")
    void aLostRaceIsNotReportedAsARepricing() {
        when(priceRepository.findById(11L)).thenReturn(Optional.of(price(PricingMode.PRICED, new BigDecimal("3.00"))));
        when(usageRepository.applyResolvedPrice(any(), any(), any())).thenReturn(0);

        assertThat(repricer.reprice(row("gpt-5", FundingSource.INSTANCE, 11L, null)))
                .isEqualTo(Outcome.STILL_UNPRICEABLE);
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

        Outcome outcome = repricer.reprice(row("own-model", FundingSource.WORKSPACE, null, 21L));

        assertThat(outcome).isEqualTo(Outcome.REPRICED);
        verify(modelRepository, never()).findByUpstreamModelId(any());
        verify(priceRepository, never()).findByModelIdAndEffectiveToIsNull(anyLong());
    }
}
