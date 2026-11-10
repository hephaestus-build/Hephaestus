package de.tum.cit.aet.hephaestus.core.auth.spi;

/**
 * Cross-module port: the recent sign-in that a workspace membership change needs when it grants, demotes or removes
 * an owner. Only the workspace knows the member's current role, so it asks at the point of decision, under its lock.
 */
public interface WorkspaceOwnerChangeAssurance {
    /** Returns when the caller signed in recently; otherwise records the refusal and throws the step-up problem. */
    void requireRecentSignIn();
}
