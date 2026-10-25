package de.tum.cit.aet.hephaestus.agent.sandbox;

/**
 * The runtime that runs a worker's sandboxes: practice reviews, the Heph host and Heph's turn sandboxes. A worker runs
 * exactly one. A value that names no runtime stops the worker's start ({@link SandboxRuntimeConfiguration}); a runtime
 * that cannot start never hands its work to the other one.
 */
public enum SandboxRuntime {
    DOCKER,
    KUBERNETES;

    /** Bound from {@code HEPHAESTUS_SANDBOX_RUNTIME} in {@code application.yml}. */
    public static final String PROPERTY = "hephaestus.sandbox.runtime";
}
