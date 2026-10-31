package de.tum.cit.aet.hephaestus.practices.review.dto;

/**
 * Why no practice review of a member's work runs now, listed in the order they are checked: the workspace, then the
 * member's own reasons, then the rules a requested review also passes, and last the automatic start. Repository
 * coverage of one piece of work and a practice's state filters depend on the work and are not judged here.
 */
public enum ReviewReadinessBlocker {
    /** The workspace is not active. */
    WORKSPACE_INACTIVE,
    /** Practice reviews are off, or neither work activity nor a request starts one. */
    REVIEWS_OFF,
    /** The member's personal data was erased. */
    PERSON_ERASED,
    /** The member chose No AI. */
    NO_AI,
    /** The review rollout leaves the member out. */
    OUTSIDE_REVIEW_SCOPE,
    /** Review selection cannot take the member as a subject, such as without an enabled identity for the provider. */
    NOT_ELIGIBLE,
    /** The workspace asks for an AI choice, and the member has not made one. */
    CHOICE_REQUIRED,
    /** No ready review model fits the member's AI choice, or the absence of one. */
    NO_MODEL,
    /** No practice that Hephaestus reviews is about the person who did the work. */
    NO_PRACTICE,
    /** Work activity starts no review. A request starts one when every rule above admits the member. */
    ON_REQUEST_ONLY
}
