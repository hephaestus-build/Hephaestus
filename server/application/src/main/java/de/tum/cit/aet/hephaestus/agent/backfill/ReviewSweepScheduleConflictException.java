package de.tum.cit.aet.hephaestus.agent.backfill;

import java.io.Serial;

/**
 * A sweep schedule cannot exist on the terms asked for — this workspace already sweeps that kind of
 * work. Maps to 409.
 *
 * <p>Apart from {@link ReviewBackfillConflictException} because the {@code type} slug is what a client
 * branches on, and the two send an admin to different screens: a campaign conflict is resolved on the
 * campaign that is running, a schedule conflict on the schedule that already exists.
 */
public class ReviewSweepScheduleConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ReviewSweepScheduleConflictException(String message) {
        super(message);
    }
}
