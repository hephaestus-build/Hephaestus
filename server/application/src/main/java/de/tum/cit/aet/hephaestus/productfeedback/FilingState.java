package de.tum.cit.aet.hephaestus.productfeedback;

/** Where a report is on its way to GitHub. Only the listed transitions happen, each under a compare-and-set on the attempt. */
enum FilingState {
    /** Waits for the worker to draft it. */
    DRAFT_QUEUED,
    /** A worker holds the lease and drafts it. */
    DRAFTING,
    /** The worker handed back the model's answer, or that none came; the server validates and routes it. */
    ANSWERED,
    /** A public report waits for its hold to end; the reporter can still keep it private or edit it. */
    HELD,
    /** Routed and waiting for the server to file it. */
    QUEUED,
    /** A server holds the lease and files it. */
    FILING,
    /** On GitHub. */
    FILED,
    /** Not on GitHub: the admin email carries it, because no destination is set or filing is paused. */
    EMAILED,
    /** Filing failed for good; it waits in Needs action, and the admin email has carried it. */
    STOPPED;

    boolean claimable() {
        return this == DRAFT_QUEUED || this == QUEUED || this == HELD;
    }
}
