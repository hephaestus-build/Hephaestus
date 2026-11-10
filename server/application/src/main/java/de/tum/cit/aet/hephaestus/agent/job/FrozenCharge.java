package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import org.jspecify.annotations.Nullable;

/**
 * What the calls of an attempt cost and which model served them, as the attempt froze it. A
 * later claim freezes a new configuration onto the job row, so a call that returns after its attempt was handed
 * on is priced from here and never from the row.
 *
 * @param model the upstream model id the ledger row names; {@code null} when the attempt froze no readable model
 */
public record FrozenCharge(@Nullable String model, LlmPriceSnapshot price) {

    /** The charge of a model whose price cannot be recovered: instance-funded and unpriced, so it reads unknown. */
    public static final FrozenCharge UNKNOWN = new FrozenCharge(null, LlmPriceSnapshot.unpricedInstance());

    /** The charge of one frozen model; a model admitted without a price stays unpriced. */
    public static FrozenCharge of(FrozenModel model) {
        LlmPriceSnapshot price = model.priceSnapshot();
        return new FrozenCharge(model.upstreamModelId(), price != null ? price : LlmPriceSnapshot.unpricedInstance());
    }
}
