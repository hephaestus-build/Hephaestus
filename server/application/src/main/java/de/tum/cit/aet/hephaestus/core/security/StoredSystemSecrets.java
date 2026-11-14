package de.tum.cit.aet.hephaestus.core.security;

import java.security.SecureRandom;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads and creates the secrets that the instance generates for itself ({@link StoredSystemSecret}). */
@Service
public class StoredSystemSecrets implements SystemSecretOwner {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StoredSystemSecretRepository repository;
    private final SystemSecrets secrets;

    public StoredSystemSecrets(StoredSystemSecretRepository repository, SystemSecrets secrets) {
        this.repository = repository;
        this.secrets = secrets;
    }

    /** The value stored under {@code name}; empty without a system key or before {@link #ensure} created it. */
    @Transactional(readOnly = true)
    public Optional<byte[]> find(String name) {
        if (!secrets.isEnabled()) return Optional.empty();
        return repository.findById(name).map(secret -> secret.value(secrets));
    }

    /** Opens every stored value, so a wrong system key stops the server at start with the identifiers involved. */
    @Transactional(readOnly = true)
    public void verifyAllOpen() {
        if (!secrets.isEnabled()) return;
        for (StoredSystemSecret secret : repository.findAll()) {
            try {
                secret.value(secrets);
            } catch (EncryptionException mismatch) {
                throw new IllegalStateException(
                        "hephaestus.security.encryption-key does not open the stored system"
                                + " secret " + secret.getName() + ": " + secret.sealingMismatch(secrets)
                                + ". Restore the system key that sealed it, as docs/admin/secret-custody.mdx describes.",
                        mismatch);
            }
        }
    }

    /**
     * Creates a random value of {@code bytes} bytes under {@code name} unless one exists. Its own transaction commits the
     * value, so a caller in a read-only transaction can read it right after.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensure(String name, int bytes) {
        if (!secrets.isEnabled() || repository.existsById(name)) return;
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        repository.insertIfAbsent(name, secrets.seal(value, StoredSystemSecret.binding(name)));
    }

    @Override
    @Transactional
    public int resealWithActiveKey(SystemSecrets secrets) {
        int resealed = 0;
        for (var row : repository.lockAllForKeyRotation()) {
            if (row.resealWithActiveKey(secrets)) resealed++;
        }
        return resealed;
    }
}
