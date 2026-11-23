package de.tum.cit.aet.hephaestus.relationship;

/**
 * The closed list of records that the mentoring modules write. A record's name and message are literals here, so no
 * text of a person can become either. A producer adds its events to this enum, where review sees the list as a whole.
 */
public enum MentoringLogEvent {
    ;

    private final String eventName;
    private final String message;

    @SuppressWarnings("UnusedMethod") // The list starts empty: the first producer's event calls it.
    MentoringLogEvent(String eventName, String message) {
        this.eventName = eventName;
        this.message = message;
    }

    /** The {@code event.name} of the record, such as {@code mentoring.turn.ended}. */
    public final String eventName() {
        return eventName;
    }

    /** The record's fixed message, for a person who reads the log. */
    public final String message() {
        return message;
    }
}
