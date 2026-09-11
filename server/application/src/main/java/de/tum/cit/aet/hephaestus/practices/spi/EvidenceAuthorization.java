package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface EvidenceAuthorization {
    Set<UUID> permitsForNewDelivery(long workspaceId, Collection<Observation> observations, SourceUsePurpose purpose);

    boolean permits(long workspaceId, Observation observation, SourceUsePurpose purpose);

    /**
     * The ids of the observations {@link #permits} would admit, for a whole list at once.
     *
     * <p>Membership is the whole answer: an observation whose id is absent is not permitted, so a caller
     * never has to tell "denied" from "not asked about". An observation that has not been persisted has no
     * id to return and is therefore never permitted — the safe direction for an authorization answer, and
     * unreachable from the read surfaces, which authorize rows they loaded from the database.
     *
     * @return the permitted observation ids; never null, possibly empty
     */
    Set<UUID> permitsAll(long workspaceId, Collection<Observation> observations, SourceUsePurpose purpose);
}
