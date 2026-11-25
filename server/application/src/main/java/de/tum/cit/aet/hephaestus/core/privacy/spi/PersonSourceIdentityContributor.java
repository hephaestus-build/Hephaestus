package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provider-owned exact attribution, shared by preview source closure and processing admission. */
public interface PersonSourceIdentityContributor {
    PersonSourceScope scope();

    Set<String> itemKinds();

    /**
     * Instance-wide source ids, selected only through verified native identities or stable user
     * keys.
     */
    List<Long> sourceIds(PersonScope person);

    /**
     * Native attribution on the current source, with requesting-workspace ownership checked in SQL.
     */
    List<PersonIdentity> identitiesForSource(long workspaceId, String itemKind, long sourceId);

    /** {@link #identitiesForSource} of each of {@code sourceIds}, keyed by source id; every id has an entry. */
    default Map<Long, List<PersonIdentity>> identitiesForSources(
            long workspaceId, String itemKind, Collection<Long> sourceIds) {
        Map<Long, List<PersonIdentity>> identities = new LinkedHashMap<>();
        for (long sourceId : sourceIds) identities.put(sourceId, identitiesForSource(workspaceId, itemKind, sourceId));
        return identities;
    }
}
