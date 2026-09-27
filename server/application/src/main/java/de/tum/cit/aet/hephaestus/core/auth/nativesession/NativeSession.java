package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * One native sign-in, bounded by an absolute deadline. The current access-token id identifies the only
 * refresh credential that can rotate; immutable credential history lives in NativeSessionToken.
 */
@Entity
@Table(
        name = "native_session",
        indexes = {
            @Index(name = "ix_native_session_account_id", columnList = "account_id"),
            @Index(name = "ix_native_session_current_jti", columnList = "current_jti"),
            @Index(name = "ix_native_session_session_expires_at", columnList = "session_expires_at"),
        })
@Getter
@Setter
@NoArgsConstructor
public class NativeSession {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** The access token this session currently backs; its {@code issued_jwt} row decides liveness. */
    @Column(name = "current_jti", nullable = false, columnDefinition = "uuid")
    private UUID currentJti;

    @Column(name = "session_expires_at", nullable = false)
    private Instant sessionExpiresAt;

    @Column(name = "auth_time", nullable = false)
    private Instant authTime;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 32)
    private @Nullable RevokedReason revokedReason;

    public NativeSession(UUID id, Long accountId, UUID currentJti, Instant sessionExpiresAt, Instant authTime) {
        this.id = id;
        this.accountId = accountId;
        this.currentJti = currentJti;
        this.sessionExpiresAt = sessionExpiresAt;
        this.authTime = authTime;
    }

    public enum RevokedReason {
        /** The app signed out with its refresh secret. */
        LOGOUT,
        /** A replaced secret was reused: the entire session ends. */
        REFRESH_REUSE,
        /** The access token it backs was revoked through an existing path, or the account is inactive. */
        SESSION_ENDED,
    }
}
