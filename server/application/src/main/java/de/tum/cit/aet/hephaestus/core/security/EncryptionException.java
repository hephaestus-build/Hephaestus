package de.tum.cit.aet.hephaestus.core.security;

import java.io.Serial;

/**
 * Exception thrown when encryption or decryption of sensitive data fails.
 */
public class EncryptionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
