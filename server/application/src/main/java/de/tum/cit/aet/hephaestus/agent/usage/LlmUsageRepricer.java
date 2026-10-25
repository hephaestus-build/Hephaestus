package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Price only verified, complete measurements; rates cannot verify usage or replace a frozen charge.
// Each row commits independently so one unpriceable row cannot roll back the pass.
@Component
public class LlmUsageRepricer {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRepricer.class);

    private final LlmUsageEventRepository usageRepository;
    private final LlmModelRepository modelRepository;
    private final LlmModelPriceRepository priceRepository;
    private final WorkspaceLlmModelRepository workspaceModelRepository;

    public LlmUsageRepricer(
            LlmUsageEventRepository usageRepository,
            LlmModelRepository modelRepository,
            LlmModelPriceRepository priceRepository,
            WorkspaceLlmModelRepository workspaceModelRepository) {
        this.usageRepository = usageRepository;
        this.modelRepository = modelRepository;
        this.workspaceModelRepository = workspaceModelRepository;
        this.priceRepository = priceRepository;
    }

    /** What happened to one row. */
    public enum ResultOutcome {
        /** A rate was found and applied; the row now counts toward its purse's total. */
        REPRICED,
        STILL_UNPRICEABLE,
        /** The row named no model, or named one this instance can no longer identify. Nothing was written. */
        UNIDENTIFIABLE,
        /**
         * The row measured no billable token, so any rate, a free one included, would price spend nobody
         * observed. Nothing was written; the row stays unknown.
         */
        NO_TOKEN_EVIDENCE,
        /** Source selection did not accept this measurement. No price can repair that decision. */
        UNVERIFIED_MEASUREMENT,
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResultOutcome reprice(UnpricedLedgerRow row) {
        if (row.measurementVerification() != MeasurementVerification.VERIFIED) {
            return ResultOutcome.UNVERIFIED_MEASUREMENT;
        }
        if (!row.hasTokenEvidence()) {
            return ResultOutcome.NO_TOKEN_EVIDENCE;
        }
        Rates rates = resolveRates(row);
        if (rates == null) {
            return ResultOutcome.UNIDENTIFIABLE;
        }
        LlmPriceSnapshot price = rates.toSnapshot(row.fundingSource());
        if (price.pricingState() == UsagePricingState.UNPRICED) {
            return ResultOutcome.STILL_UNPRICEABLE;
        }
        LlmPriceSnapshot.Cost computed = price.calculateCost(
                row.inputTokens(), row.outputTokens(), row.cacheReadTokens(), row.cacheWriteTokens());
        if (computed.usd() == null || missesARateItNeeds(row, price)) {
            // The catalogue row exists and claims to be priced, but not for every bucket this event
            // actually used. Charging the buckets it does cover would under-bill silently, which is the
            // one thing UNPRICED exists to prevent.
            return ResultOutcome.STILL_UNPRICEABLE;
        }
        int updated = usageRepository.applyResolvedPrice(row.id(), computed.usd(), price, row);
        if (updated == 0) {
            return ResultOutcome.STILL_UNPRICEABLE;
        }
        // The ledger row first, then the attempt's charge: a projection reads charges before it locks a ledger row.
        usageRepository.adoptResolvedPriceInAttemptCharge(row.id(), price);
        log.info(
                "Repriced ledger event {} ({} tokens in / {} out, model {}) as {} at {} USD",
                row.id(),
                row.inputTokens(),
                row.outputTokens(),
                row.model(),
                price.pricingState(),
                computed.usd());
        return ResultOutcome.REPRICED;
    }

    /**
     * A rate the resolved price is missing cannot be charged. Mirrors the same rule in
     * {@code LlmUsageRecorder}, deliberately duplicated rather than shared: this one is asked about a
     * stored row and that one about a sample, and collapsing them would mean one of the two callers
     * building a fake instance of the other's type to ask its question.
     */
    private static boolean missesARateItNeeds(UnpricedLedgerRow row, LlmPriceSnapshot price) {
        // NO_CHARGE has no rates by design and is a real zero, not a gap. No price has a rate for search units.
        return (price.pricingState() == UsagePricingState.PRICED
                && ((row.inputTokens() > 0 && price.per1mInputUsd() == null)
                        || (row.outputTokens() > 0 && price.per1mOutputUsd() == null)
                        || (row.cacheReadTokens() > 0 && price.per1mCacheReadUsd() == null)
                        || (row.cacheWriteTokens() > 0 && price.per1mCacheWriteUsd() == null)
                        || row.searchUnits() > 0));
    }

    /**
     * Find today's rates for the model this row was charged against, in order of how certainly the row
     * identifies it.
     *
     * <p>The provenance ids come first because they are exact: a row that went UNPRICED only because one
     * rate was blank still names the catalogue row it came from. The upstream-model-id lookup is the
     * fallback for verified rows without a price snapshot. That lookup insists on a
     * unique match: two connections may expose the same upstream id at different prices, and guessing
     * between them would put a number the operator cannot defend into an append-only ledger.
     */
    private @Nullable Rates resolveRates(UnpricedLedgerRow row) {
        if (row.appliedPriceId() != null) {
            return priceRepository.findById(row.appliedPriceId()).map(Rates::of).orElse(null);
        }
        if (row.appliedWorkspaceModelId() != null) {
            return workspaceModelRepository
                    .findByIdAndWorkspaceId(row.appliedWorkspaceModelId(), row.workspaceId())
                    .map(Rates::of)
                    .orElse(null);
        }
        if (row.model() == null || row.model().isBlank()) {
            return null;
        }
        return row.fundingSource() == UsageFundingSource.WORKSPACE
                ? uniqueWorkspaceModel(row).map(Rates::of).orElse(null)
                : uniqueInstancePrice(row).map(Rates::of).orElse(null);
    }

    private Optional<LlmModelPrice> uniqueInstancePrice(UnpricedLedgerRow row) {
        String model = Objects.requireNonNull(row.model());
        List<LlmModel> candidates = modelRepository.findByUpstreamModelId(model);
        if (candidates.size() != 1) {
            reportAmbiguity(row, candidates.size());
            return Optional.empty();
        }
        return priceRepository.findByModelIdAndEffectiveToIsNull(
                candidates.getFirst().getId());
    }

    private Optional<WorkspaceLlmModel> uniqueWorkspaceModel(UnpricedLedgerRow row) {
        String model = Objects.requireNonNull(row.model());
        List<WorkspaceLlmModel> candidates =
                workspaceModelRepository.findByWorkspaceIdAndUpstreamModelId(row.workspaceId(), model);
        if (candidates.size() != 1) {
            reportAmbiguity(row, candidates.size());
            return Optional.empty();
        }
        return Optional.of(candidates.getFirst());
    }

    private static void reportAmbiguity(UnpricedLedgerRow row, int candidates) {
        if (candidates > 1) {
            log.warn(
                    "Ledger event {} names model '{}', which {} catalogue entries claim at possibly different "
                            + "prices; leaving it unpriced rather than guessing which was billed",
                    row.id(),
                    row.model(),
                    candidates);
        }
    }

    /** The four per-million rates and whether the catalogue considers the model priced at all. */
    private record Rates(
            PricingMode mode,
            @Nullable Long priceId,
            @Nullable Long workspaceModelId,
            @Nullable BigDecimal input,
            @Nullable BigDecimal output,
            @Nullable BigDecimal cacheRead,
            @Nullable BigDecimal cacheWrite) {
        static Rates of(LlmModelPrice price) {
            return new Rates(
                    price.getPricingMode(),
                    price.getId(),
                    null,
                    price.getPer1mInputUsd(),
                    price.getPer1mOutputUsd(),
                    price.getPer1mCacheReadUsd(),
                    price.getPer1mCacheWriteUsd());
        }

        static Rates of(WorkspaceLlmModel model) {
            return new Rates(
                    model.getPricingMode(),
                    null,
                    model.getId(),
                    model.getPer1mInputUsd(),
                    model.getPer1mOutputUsd(),
                    model.getPer1mCacheReadUsd(),
                    model.getPer1mCacheWriteUsd());
        }

        LlmPriceSnapshot toSnapshot(UsageFundingSource fundingSource) {
            UsagePricingState state = switch (mode) {
                case PRICED -> UsagePricingState.PRICED;
                case NO_CHARGE -> UsagePricingState.NO_CHARGE;
                case UNPRICED -> UsagePricingState.UNPRICED;
            };
            return new LlmPriceSnapshot(
                    fundingSource, state, priceId, workspaceModelId, input, output, cacheRead, cacheWrite);
        }
    }
}
