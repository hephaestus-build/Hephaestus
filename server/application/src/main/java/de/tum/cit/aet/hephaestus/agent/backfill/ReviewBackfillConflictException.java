package de.tum.cit.aet.hephaestus.agent.backfill;

import java.io.Serial;

/** A campaign cannot be started, cancelled or superseded from the state it is in. Maps to 409. */
public class ReviewBackfillConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ReviewBackfillConflictException(String message) {
        super(message);
    }
}
