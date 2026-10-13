package de.tum.cit.aet.hephaestus.agent.mentor.turn.exception;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import java.io.Serial;

/** Mentor admission declined the member; the turn stops before anything is persisted. */
public final class MentorRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final MentorRefusal reason;

    public MentorRefusedException(MentorRefusal reason) {
        super("Mentor turn refused: " + reason);
        this.reason = reason;
    }

    public MentorRefusal reason() {
        return reason;
    }
}
