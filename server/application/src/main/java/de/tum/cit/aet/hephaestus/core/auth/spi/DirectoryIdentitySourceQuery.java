package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Operator-approved association between an exact organizational issuer and directory group IDs. */
public interface DirectoryIdentitySourceQuery {
    List<Source> approvedSources();

    Optional<Source> approvedSource(String registrationId);
    /** Requires a transaction; excludes concurrent source approval changes through admission. */
    Optional<Source> approvedSourceForUpdate(String registrationId);

    record Source(
            String registrationId,
            String displayName,
            String issuer,
            long providerId,
            Set<String> groupIds,
            Instant updatedAt) {}
}
