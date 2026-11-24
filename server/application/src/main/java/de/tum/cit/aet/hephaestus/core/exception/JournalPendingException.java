package de.tum.cit.aet.hephaestus.core.exception;

/** The committed privacy fact still needs its durable copy. */
public class JournalPendingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JournalPendingException() {}

    public JournalPendingException(Throwable cause) {
        super("The privacy journal copy is pending", cause);
    }
}
