package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.io.Serial;
import java.util.Locale;

/**
 * A stored credential exists but cannot be decrypted with the keys this server runs with. That is a
 * state of the connection, not a failure of the request that met it: the operator either restores
 * the key the credential was written with or replaces the credential, and until then every request
 * that needs the plaintext gets this answer.
 */
public class CredentialUnreadableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long connectionId;
    private final IntegrationKind kind;

    public CredentialUnreadableException(long connectionId, IntegrationKind kind, Throwable cause) {
        super(
                "The stored " + kind.name().toLowerCase(Locale.ROOT) + " credential of connection "
                        + connectionId
                        + " cannot be read with the server's current encryption keys. Replace the credential, or restore the"
                        + " key it was written with if that was changed by mistake.",
                cause);
        this.connectionId = connectionId;
        this.kind = kind;
    }

    public long connectionId() {
        return connectionId;
    }

    public IntegrationKind kind() {
        return kind;
    }
}
