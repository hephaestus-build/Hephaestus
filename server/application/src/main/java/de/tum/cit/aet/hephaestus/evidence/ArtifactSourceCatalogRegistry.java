package de.tum.cit.aet.hephaestus.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface ArtifactSourceCatalogRegistry {
    /** Constant form of {@link #CURRENT_VERSION}, for the annotations that need a compile-time value. */
    String CURRENT_VERSION_VALUE = "1.1.0";

    /** The contract this runtime ships: every new capture and every authored practice pins it. */
    SourceContractVersion CURRENT_VERSION = new SourceContractVersion(CURRENT_VERSION_VALUE);

    /**
     * The contract retired by {@link #CURRENT_VERSION}, still readable for the feedback recorded under it
     * and upgraded from when an installed practice policy is found pinned to it.
     */
    SourceContractVersion PREVIOUS_VERSION = new SourceContractVersion("1.0.0");

    ArtifactSourceCatalog current();

    /** SHA-256 of the exact versioned catalog resource bytes loaded by this runtime. */
    String catalogDigest();

    ArtifactSourceContract requireSource(SourceContractVersion version, SourceKind kind);

    boolean isSourceUsePermitted(SourceContractVersion version, SourceKind kind, SourceUsePurpose purpose);

    /**
     * The sources a review of this artifact kind may observe, refusing a kind no source declares.
     *
     * <p>Refusing rather than returning empty is deliberate: an empty evidence surface and a misspelled
     * artifact kind are indistinguishable to the caller, and the second one must not silently produce a
     * review that looked at nothing.
     */
    Set<SourceKind> requireSourcesFor(SourceContractVersion version, String artifactKind);

    /**
     * The sources a practice bound to this artifact kind reads by default, refusing a kind no source
     * declares — same reason {@link #requireSourcesFor} refuses one.
     */
    List<SourceKind> requireDefaultSourcesFor(SourceContractVersion version, String artifactKind);

    SourceUseDecision requireUseDecision(SourceContractVersion version, String decisionId);

    Optional<Instant> earliestUseDecisionExpiry(@Nullable SourceUsePurpose purpose);
}
