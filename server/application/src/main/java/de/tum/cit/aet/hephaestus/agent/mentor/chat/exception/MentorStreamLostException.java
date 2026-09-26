package de.tum.cit.aet.hephaestus.agent.mentor.chat.exception;

import java.io.Serial;

/** The runner's event stream was cut off mid-turn, so the rest of the reply cannot be delivered. */
public final class MentorStreamLostException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MentorStreamLostException() {
        super("Runner event stream lost");
    }
}
