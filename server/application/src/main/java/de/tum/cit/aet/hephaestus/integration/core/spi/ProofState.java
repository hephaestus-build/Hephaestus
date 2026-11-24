package de.tum.cit.aet.hephaestus.integration.core.spi;

/** How far the check of a monitored resource's readers on the provider got. */
public enum ProofState {
    /** The provider proved who can see the resource. */
    PROVEN,
    /** The check is still running or waits for the next sync. */
    PENDING,
    /** The check cannot finish until an operator acts, for example on a repository the provider no longer shows. */
    FAILED
}
