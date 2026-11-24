package de.tum.cit.aet.hephaestus.core.authority;

/** Where a run's authority is checked again (ADR 0134 decision 6). Each refusal names its boundary. */
public enum AuthorityBoundary {
    /** Before a run reads a source. */
    CONTEXT_READ,
    /** Before the model gateway sends a call to a provider. */
    MODEL_DISPATCH,
    /** Before a write to anyone other than the owner: a provider write, a Slack reply, an email or a push. */
    SEND
}
