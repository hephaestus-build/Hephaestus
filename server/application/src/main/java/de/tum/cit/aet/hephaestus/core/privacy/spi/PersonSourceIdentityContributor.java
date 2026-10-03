package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;
import java.util.Set;

/** Provider-owned exact attribution, shared by preview source closure and processing admission. */
public interface PersonSourceIdentityContributor {
    Set<String> artifactKinds();

    /** Instance-wide source ids, selected only through verified native identities or stable user keys. */
    List<Long> sourceIds(PersonScope person);

    /** Native attribution on the current source, with requesting-workspace ownership checked in SQL. */
    List<PersonIdentity> identitiesForSource(long workspaceId, String artifactKind, long artifactId);
}
