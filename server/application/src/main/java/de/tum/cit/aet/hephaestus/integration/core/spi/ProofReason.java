package de.tum.cit.aet.hephaestus.integration.core.spi;

/** Why a monitored resource's readers are proven, still being proven, or could not be proven. */
public enum ProofReason {
    /** The provider has not yet listed who can see the resource. */
    AWAITING_READERS(ProofState.PENDING),
    /** The provider confirmed the resource public. */
    PUBLIC_ON_PROVIDER(ProofState.PROVEN),
    /** The provider listed every reader of the resource. */
    READERS_LISTED(ProofState.PROVEN),
    /** The provider's rate limit stopped the listing of readers; the next sync continues it. */
    RATE_LIMITED(ProofState.PENDING),
    /** The listing of readers stopped before its end; the next sync lists them again. */
    LISTING_INCOMPLETE(ProofState.PENDING),
    /** The provider no longer shows the resource to this workspace. */
    RESOURCE_UNAVAILABLE(ProofState.FAILED),
    /**
     * The provider cannot list every reader of the resource, such as a GitHub internal repository that enterprise
     * members outside its organization can read. It waits until its visibility changes.
     */
    READERS_NOT_LISTABLE(ProofState.FAILED);

    private final ProofState state;

    ProofReason(ProofState state) {
        this.state = state;
    }

    public ProofState state() {
        return state;
    }
}
