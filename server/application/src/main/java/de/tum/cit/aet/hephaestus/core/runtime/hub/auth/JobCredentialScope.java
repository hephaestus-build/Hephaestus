package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

/**
 * What a review job's credential lets its sandbox do beside model calls. A sandbox gets exactly one: the review launch
 * measures, admits and prepares private feedback; the public review launch only prepares the review on the work.
 */
public enum JobCredentialScope {
    REVIEW("review"),
    PUBLIC_REVIEW("public_review");

    private final String claim;

    JobCredentialScope(String claim) {
        this.claim = claim;
    }

    /** The scope as the credential carries it, and as its {@code SCOPE_} authority names it. */
    public String claim() {
        return claim;
    }
}
