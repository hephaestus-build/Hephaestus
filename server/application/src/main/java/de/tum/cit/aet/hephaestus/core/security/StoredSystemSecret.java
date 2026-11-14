package de.tum.cit.aet.hephaestus.core.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A secret that the instance itself generates and keeps, such as the key of erased-namespace reservations. It stays
 * the same across system key rotations: only its sealing changes.
 */
@Entity
@Table(name = "system_secret")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoredSystemSecret {

    @Id
    @Column(name = "name", length = 64)
    private String name;

    @Column(name = "sealed_value", nullable = false)
    @Getter(AccessLevel.NONE)
    private byte[] sealedValue;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public byte[] value(SystemSecrets secrets) {
        return secrets.open(sealedValue, binding(name));
    }

    /** Seals the value again under the active system key; false when it already uses that key. */
    public boolean resealWithActiveKey(SystemSecrets secrets) {
        if (secrets.isSealedWithActiveKey(sealedValue)) return false;
        this.sealedValue = secrets.seal(secrets.open(sealedValue, binding(name)), binding(name));
        return true;
    }

    /** Why the value does not open with the configured keys; names key identifiers, never a key. */
    public String sealingMismatch(SystemSecrets secrets) {
        return secrets.describeMismatch(sealedValue);
    }

    static SecretBinding binding(String name) {
        return SecretBinding.of("system_secret.sealed_value", name);
    }
}
