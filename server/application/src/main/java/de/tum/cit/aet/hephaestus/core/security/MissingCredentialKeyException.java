package de.tum.cit.aet.hephaestus.core.security;

import java.io.Serial;

/**
 * Exception thrown when the key a stored credential was encrypted under is not configured on this
 * instance. Separated from a decryption failure because it is a property of the configuration, not
 * of the row: correcting the configuration makes the same ciphertext readable again.
 */
public class MissingCredentialKeyException extends EncryptionException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MissingCredentialKeyException(String message) {
        super(message);
    }
}
