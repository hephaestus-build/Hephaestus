package de.tum.cit.aet.hephaestus.agent.sandbox;

/** Kubernetes image pull policies. */
public enum ImagePullPolicy {
    ALWAYS,
    IF_NOT_PRESENT,
    NEVER,
}
