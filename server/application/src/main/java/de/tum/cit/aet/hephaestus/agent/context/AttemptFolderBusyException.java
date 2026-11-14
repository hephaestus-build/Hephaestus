package de.tum.cit.aet.hephaestus.agent.context;

import java.io.Serial;

/**
 * Another process or thread holds the evidence folder that this server must rebuild, longer than a rebuild waits, or
 * the folder is gone again when admission reads it. An infrastructure state that ends by itself: the caller tries
 * again. It says nothing about the evidence, so no coverage or citation check may treat it as a refusal.
 */
public class AttemptFolderBusyException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public AttemptFolderBusyException(String message) {
        super(message);
    }

    public AttemptFolderBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
