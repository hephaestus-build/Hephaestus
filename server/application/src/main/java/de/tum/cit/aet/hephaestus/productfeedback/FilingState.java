package de.tum.cit.aet.hephaestus.productfeedback;

/** Where a report is on its way to GitHub. {@link FilingLifecycle} owns which moves are allowed. */
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
    /** Its {@code file_issue} action in the outbox writes it to GitHub, and the action's end moves it on. */
    FILING,
    /** On GitHub. */
    FILED,
    /** Not on GitHub: it stays saved for the admins in the console. It says nothing about mail, which nothing can prove. */
    SAVED,
    /** Filing failed for good; it waits in Needs action. */
    STOPPED;

    boolean claimable() {
        return this == DRAFT_QUEUED || this == QUEUED || this == HELD;
    }
}
