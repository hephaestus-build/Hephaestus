package de.tum.cit.aet.hephaestus.core.auth.jwt;

import de.tum.cit.aet.hephaestus.core.security.SecretBinding;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Hephaestus's own JWT-signing key set. Backs Spring Security's {@code NimbusJwtEncoder}
 * through a custom {@code JWKSource<SecurityContext>} that reads this table.
 *
 * <h2>Key lifecycle</h2>
 * New JWTs sign with the most recently inserted {@code active=true} key; all active keys verify.
 *
 * <p>{@code private_key_pem} holds the PKCS#8 DER private key sealed by {@link SystemSecrets}, bound to the
 * {@code kid}.
 */
@Entity
@Table(name = "jwt_signing_key")
@Getter
@Setter
@NoArgsConstructor
public class JwtSigningKey {

    @Id
    @Column(name = "kid", length = 64)
    private String kid;

    @Column(name = "algorithm", nullable = false, length = 16)
    @ColumnDefault("'ES256'")
    private String algorithm = "ES256";

    @Column(name = "public_key_pem", nullable = false, columnDefinition = "text")
    private String publicKeyPem;

    @Column(name = "private_key_pem", nullable = false)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private byte[] sealedPrivateKey;

    @Column(name = "active", nullable = false)
    @ColumnDefault("true")
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The PKCS#8 DER private key. */
    public byte[] privateKey(SystemSecrets secrets) {
        return secrets.open(sealedPrivateKey, secretBinding());
    }

    /** Seals {@code privateKeyDer} under the active system key; set the {@code kid} first. */
    public void replacePrivateKey(byte[] privateKeyDer, SystemSecrets secrets) {
        this.sealedPrivateKey = secrets.seal(privateKeyDer, secretBinding());
    }

    /** Seals the private key again under the active system key; false when it already uses that key. */
    public boolean resealWithActiveKey(SystemSecrets secrets) {
        if (secrets.isSealedWithActiveKey(sealedPrivateKey)) return false;
        this.sealedPrivateKey = secrets.seal(secrets.open(sealedPrivateKey, secretBinding()), secretBinding());
        return true;
    }

    /** Why the private key does not open with the configured keys; names key identifiers, never a key. */
    public String sealingMismatch(SystemSecrets secrets) {
        return secrets.describeMismatch(sealedPrivateKey);
    }

    private SecretBinding secretBinding() {
        return SecretBinding.of("jwt_signing_key.private_key_pem", kid);
    }
}
