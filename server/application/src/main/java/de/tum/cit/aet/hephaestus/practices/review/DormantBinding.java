package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A practice that is switched on and can never fire here, with the reason attached — dropping it from a
 * listing would leave it looking configured and healthy while permanently silent, and failing the boot
 * over it would break every workspace that has simply not connected an integration yet.
 *
 * @param practiceId    the practice that cannot fire
 * @param signals       the signals it is bound to, none of which anything connected here raises
 * @param raisedByAnyOf the integrations that <em>could</em> raise at least one of them if connected;
 *                      empty means no compiled integration covers it either, which is a build problem
 *                      rather than an onboarding one
 */
public record DormantBinding(Long practiceId, Set<SignalName> signals, Set<IntegrationKind> raisedByAnyOf) {
    public DormantBinding {
        signals = Set.copyOf(signals);
        raisedByAnyOf = Set.copyOf(raisedByAnyOf);
    }

    /**
     * The fact, in the words a reader knows: each signal and integration by its display name, never by its
     * identifier. Stated rather than phrased as the fix, by the rule {@code SignalStateReason#describe()}
     * keeps: a developer reads it on the trace of their own work and cannot connect an integration.
     *
     * @param signalName      a signal's display name
     * @param integrationName an integration's display name
     */
    public String reason(Function<SignalName, String> signalName, Function<IntegrationKind, String> integrationName) {
        String moments = signals.stream().map(signalName).distinct().sorted().collect(Collectors.joining(", "));
        if (raisedByAnyOf.isEmpty()) {
            return "Nothing Hephaestus can connect reports the moments this practice watches for (" + moments
                    + "), so it is never reviewed.";
        }
        String integrations =
                raisedByAnyOf.stream().map(integrationName).sorted().collect(Collectors.joining(" or "));
        return "Nothing connected to this workspace reports the moments this practice watches for (" + moments + "); "
                + integrations + " would.";
    }
}
