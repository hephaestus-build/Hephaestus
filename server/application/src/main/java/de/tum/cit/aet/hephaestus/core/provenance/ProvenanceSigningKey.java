package de.tum.cit.aet.hephaestus.core.provenance;

import de.tum.cit.aet.hephaestus.core.security.SecretBinding;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An Ed25519 key that signs provenance records. The newest key signs; every key verifies, for as long as a record it
 * signed can exist. {@code private_key} holds the PKCS#8 DER private key sealed by {@link SystemSecrets}, bound to the
 * {@code kid}; {@code public_key} holds the X.509 SubjectPublicKeyInfo DER.
 */
@Entity
@Table(name = "provenance_signing_key")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProvenanceSigningKey {

    @Id
    @Column(name = "kid", length = 64)
    private String kid;

    @Column(name = "public_key", nullable = false)
    private byte[] publicKey;

    @Column(name = "private_key", nullable = false)
    @Getter(AccessLevel.NONE)
    private byte[] sealedPrivateKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    ProvenanceSigningKey(String kid, byte[] publicKey, byte[] privateKey, Instant createdAt, SystemSecrets secrets) {
        this.kid = kid;
        this.publicKey = publicKey.clone();
        this.sealedPrivateKey = secrets.seal(privateKey, binding(kid));
        this.createdAt = createdAt;
    }

    /** The PKCS#8 DER private key. */
    byte[] privateKey(SystemSecrets secrets) {
        return secrets.open(sealedPrivateKey, binding(kid));
    }

    /** Seals the private key again under the active system key; false when it already uses that key. */
    boolean resealWithActiveKey(SystemSecrets secrets) {
        if (secrets.isSealedWithActiveKey(sealedPrivateKey)) return false;
        byte[] privateKey = secrets.open(sealedPrivateKey, binding(kid));
        try {
            this.sealedPrivateKey = secrets.seal(privateKey, binding(kid));
        } finally {
            Arrays.fill(privateKey, (byte) 0);
        }
        return true;
    }

    /** Why the private key does not open with the configured keys; names key identifiers, never a key. */
    String sealingMismatch(SystemSecrets secrets) {
        return secrets.describeMismatch(sealedPrivateKey);
    }

    private static SecretBinding binding(String kid) {
        return SecretBinding.of("provenance_signing_key.private_key", kid);
    }
}
