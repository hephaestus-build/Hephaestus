package de.tum.cit.aet.hephaestus.agent.mentor.chat.exception;

import java.io.Serial;

/** A retry that names no reply this thread can answer again; the message is safe to show the developer. */
public final class MentorRetryRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String NOT_RETRYABLE = "This reply can't be tried again. Ask your question again instead.";
    public static final String SUPERSEDED =
            "This reply was already answered or tried again. Reload the conversation to see the latest reply.";

    public MentorRetryRejectedException(String message) {
        super(message);
    }
}
