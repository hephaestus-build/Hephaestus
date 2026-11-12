package de.tum.cit.aet.hephaestus.productfeedback;

/** Where a report is on its way to GitHub. {@link FilingLifecycle} owns which moves are allowed. */
enum FilingState {
    /** Its {@code draft_report} action in the outbox drafts it, and the action's end moves it on. */
    DRAFT_QUEUED,
    /** Its action holds a draft attempt. */
    DRAFTING,
    /** Its model has no capacity; the action waits without spending an attempt. */
    DRAFT_WAITING,
    /** The draft action handed back the model's answer, or that none came; the server validates and routes it. */
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
}
