package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ObservationVisibilityPolicy {

    private final EvidenceAuthorization evidenceAuthorization;

    public ObservationVisibilityPolicy(EvidenceAuthorization evidenceAuthorization) {
        this.evidenceAuthorization = evidenceAuthorization;
    }

    /**
     * Returns observations measured against current review rules whose evidence remains authorized for the
     * requested use. Currentness is checked first so stale claims do not trigger authorization reads; the
     * remaining observations are authorized in one batch.
     */
    public Set<UUID> permitsAll(long workspaceId, Collection<Observation> observations, SourceUsePurpose purpose) {
        return permitted(workspaceId, observations, purpose, false);
    }

    public Set<UUID> permitsForNewDelivery(
            long workspaceId, Collection<Observation> observations, SourceUsePurpose purpose) {
        return permitted(workspaceId, observations, purpose, true);
    }

    private Set<UUID> permitted(
            long workspaceId, Collection<Observation> observations, SourceUsePurpose purpose, boolean newDelivery) {
        List<Observation> current = new ArrayList<>(observations.size());
        for (Observation observation : observations) {
            if (ReviewClaimCurrentness.of(observation.getPracticeRevision(), observation.getPractice())
                    == ReviewClaimCurrentness.CURRENT) {
                current.add(observation);
            }
        }
        if (current.isEmpty()) {
            return Set.of();
        }
        return newDelivery
                ? evidenceAuthorization.permitsForNewDelivery(workspaceId, current, purpose)
                : evidenceAuthorization.permitsAll(workspaceId, current, purpose);
    }
}
