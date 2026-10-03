package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;

/**
 * How a signal's discovery mode maps onto the population its measurements belong to.
 *
 * <p>{@code DiscoveredVia} answers how we learned the signal happened; {@code ObservationOrigin} answers
 * how the sample was selected. A review submitted from a ledger row has to derive the second from the
 * first, because by then nothing else remembers.
 */
public final class SignalOrigins {

    private SignalOrigins() {}

    public static ObservationOrigin observationOriginOf(DiscoveredVia discoveredVia) {
        return switch (discoveredVia) {
            // SYNC groups with LIVE despite finding a signal after the fact: reconciliation never triggers a
            // review on its own, only a later live redelivery or campaign does.
            //
            // SWEEP groups with LIVE, not BACKFILL: its window is bounded to the recent past by rule rather
            // than chosen with hindsight, so filing it as BACKFILL would hide its observations from the
            // reflection read model.
            case EVENT, SYNC, SWEEP -> ObservationOrigin.LIVE;
            case MANUAL -> ObservationOrigin.MANUAL;
            case BACKFILL -> ObservationOrigin.BACKFILL;
        };
    }
}
