package de.tum.cit.aet.hephaestus.practices.feedback.thread;

/** Why Hephaestus stopped changing a thread. */
public enum FeedbackThreadStopReason {
    /** A person resolved or reopened the thread, or Hephaestus could not prove the change its own. Final. */
    HUMAN_CONTROLLED,
    /** The thread, its note or its work is gone. Final. */
    GONE,
    /** The thread no longer starts with the owned note. Final. */
    UNSUPPORTED,
    /** A person replied after the owned note: Hephaestus does not close a conversation that a person continues. Final. */
    REPLIED,
    /** The feedback was withdrawn. */
    WITHDRAWN,
    /** The workspace does not permit feedback delivery from the evidence the change rests on. */
    NOT_PERMITTED,
    /** The developer's data is held for an account request. */
    PERSON_SUPPRESSED,
    /** The provider did not confirm the change within the action's attempts. */
    FAILED;

    /** Whether no later decision may change the thread again. */
    public boolean isFinal() {
        return this == HUMAN_CONTROLLED || this == GONE || this == UNSUPPORTED || this == REPLIED;
    }
}
