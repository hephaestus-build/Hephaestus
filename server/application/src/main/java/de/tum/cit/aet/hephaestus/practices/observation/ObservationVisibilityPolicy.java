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
    private final ObservationInvalidationRepository invalidations;

    public ObservationVisibilityPolicy(
            EvidenceAuthorization evidenceAuthorization, ObservationInvalidationRepository invalidations) {
        this.evidenceAuthorization = evidenceAuthorization;
        this.invalidations = invalidations;
    }

    /**
     * The ids of the observations measured against a current practice revision, not invalidated by an admin,
     * whose evidence remains authorized for the requested use. Currentness is checked first so stale claims do
     * not trigger authorization reads; the rest are authorized in one batch.
     */
    public Set<UUID> permitsAll(long workspaceId, Collection<Observation> observations, SourceUsePurpose purpose) {
        return permitted(workspaceId, observations, purpose, false);
    }

    public Set<UUID> permitsForNewDelivery(
            long workspaceId, Collection<Observation> observations, SourceUsePurpose purpose) {
        return permitted(workspaceId, observations, purpose, true);
    }

    /**
     * The ids of the observations that may still be shown to the developer they are about: evidence
     * measured by review rules the practice has since changed stays, since a card closed by that change is
     * something the developer may see; a claim whose rules cannot be verified at all, or that an admin
     * invalidated, is dropped. The rest are authorized in one batch, as {@link #permitsAll} does.
     */
    public Set<UUID> permitsShown(long workspaceId, Collection<Observation> observations, SourceUsePurpose purpose) {
        List<Observation> verifiable = new ArrayList<>(observations.size());
        for (Observation observation : observations) {
            if (ReviewClaimCurrentness.of(observation.getPracticeRevision(), observation.getPractice())
                    != ReviewClaimCurrentness.UNVERIFIABLE) {
                verifiable.add(observation);
            }
        }
        withoutInvalidated(workspaceId, verifiable);
        if (verifiable.isEmpty()) {
            return Set.of();
        }
        return evidenceAuthorization.permitsAll(workspaceId, verifiable, purpose);
    }

    /** Read-only history keeps superseded and invalidated rows, but still enforces evidence authorization. */
    public Set<UUID> permitsHistory(long workspaceId, Collection<Observation> observations, SourceUsePurpose purpose) {
        return evidenceAuthorization.permitsAll(workspaceId, observations, purpose);
    }

    private Set<UUID> permitted(
            long workspaceId, Collection<Observation> observations, SourceUsePurpose purpose, boolean newDelivery) {
        List<Observation> current = new ArrayList<>(observations.size());
        for (Observation observation : observations) {
            if (ReviewClaimCurrentness.of(
                            observation.getPracticeRevision(), observation.getPractice(), observation.getSupersededAt())
                    == ReviewClaimCurrentness.CURRENT) {
                current.add(observation);
            }
        }
        withoutInvalidated(workspaceId, current);
        if (current.isEmpty()) {
            return Set.of();
        }
        return newDelivery
                ? evidenceAuthorization.permitsForNewDelivery(workspaceId, current, purpose)
                : evidenceAuthorization.permitsAll(workspaceId, current, purpose);
    }

    private void withoutInvalidated(long workspaceId, List<Observation> observations) {
        if (observations.isEmpty()) {
            return;
        }
        Set<UUID> invalidated = invalidations.findActiveObservationIds(
                workspaceId, observations.stream().map(Observation::getId).toList());
        observations.removeIf(observation -> invalidated.contains(observation.getId()));
    }
}
