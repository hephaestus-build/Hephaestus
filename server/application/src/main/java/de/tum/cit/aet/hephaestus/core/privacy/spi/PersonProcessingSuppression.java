package de.tum.cit.aet.hephaestus.core.privacy.spi;

import org.jspecify.annotations.Nullable;

/** Exact-key processing fence; must be checked again after a mirror is recreated. */
public interface PersonProcessingSuppression {
    boolean isSuppressed(long providerId, String subject, @Nullable String teamId);

    boolean isUserSuppressed(long userId);

    /** Current source attribution, including resynced provider records, scoped to the requesting workspace. */
    boolean isArtifactSuppressed(long workspaceId, String artifactKind, long artifactId);
}
