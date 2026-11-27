package de.tum.cit.aet.hephaestus.agent.job;

/** Why a seal chose its review's producer (ADR 0160): kept on the job beside the producer. */
public enum FeedbackProducerReason {
    /** An earlier seal of the review pinned it, or a server before the pin wrote the review. */
    INHERITED,
    /** Selecting the mentor is off on this server. */
    GATE_CLOSED,
    /** The workspace does not select the mentor. */
    NOT_SELECTED,
    /** Not a pull or merge request whose practices are all about its author: no other destination has a cutover. */
    UNSUPPORTED_ROUTE,
    /** No author practice names a linked person. */
    NO_LINKED_AUTHOR,
    /** The author practices name more than one person. */
    SEVERAL_AUTHORS,
    /** The author's account has not signed in yet. A blocked or deleting author records {@link #OWNER_REFUSED}. */
    AUTHOR_INACTIVE,
    /** The author is no current, visible member of the workspace. */
    AUTHOR_NOT_MEMBER,
    /** The author has practice feedback delivery off, or made no AI choice, or chose No AI. */
    AUTHOR_NOT_CONSENTING,
    /** The write fence refused the mailbox row: a processing restriction, or a blocked or deleting author. */
    OWNER_REFUSED,
    /** The review already has a mailbox row for another owner or another admission. */
    EVENT_MISMATCH,
    /** The mailbox row for the author is written. */
    OWNER_BOUND
}
