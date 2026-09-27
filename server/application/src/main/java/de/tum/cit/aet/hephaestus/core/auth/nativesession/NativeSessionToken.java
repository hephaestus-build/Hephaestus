package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Immutable credential lineage for one native session. Retained until the session is removed, even
 * after the corresponding access-token row expires, so any replaced refresh secret detects reuse and
 * a session-list entry still revokes the right session after arbitrarily many rotations.
 */
@Entity
@Table(
        name = "native_session_token",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_native_session_token_hash", columnNames = "refresh_token_hash"),
        indexes = @Index(name = "ix_native_session_token_session_id", columnList = "session_id"))
@Getter
@NoArgsConstructor
public class NativeSessionToken {
    @Id
    @Column(name = "jti", columnDefinition = "uuid")
    private UUID jti;

    @Column(name = "session_id", nullable = false, columnDefinition = "uuid")
    private UUID sessionId;

    /** Hash of 256 random bits; neither the refresh secret nor the access token is stored. */
    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    private String refreshTokenHash;

    public NativeSessionToken(UUID jti, UUID sessionId, String refreshTokenHash) {
        this.jti = jti;
        this.sessionId = sessionId;
        this.refreshTokenHash = refreshTokenHash;
    }
}
