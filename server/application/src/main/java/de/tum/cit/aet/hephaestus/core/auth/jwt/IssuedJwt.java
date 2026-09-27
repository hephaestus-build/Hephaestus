package de.tum.cit.aet.hephaestus.core.auth.jwt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Revocation list for Hephaestus-issued cookie JWTs. Every JWT we mint has a {@code jti}
 * inserted here at issuance; the {@code RevocationAwareJwtDecoder} consults a Caffeine-cached
 * view of this table on every request to short-circuit revoked tokens.
 *
 * <p>Cross-pod correctness comes from the indexed {@code jti} lookup on every request, not from
 * cache invalidation: the decoder caches only the REVOKED verdict (a negative cache), so a revoke
 * is visible to every pod within DB visibility lag. The Caffeine entry only sheds replay load.
 *
 * <p>Expired rows are pruned daily by {@code IssuedJwtCleanupJob}; the {@code expires_at} index
 * makes that range delete cheap.
 *
 * <p>A token an installed-client session issued carries its {@code session_id} and the hash of the
 * refresh secret issued with it. Those rows outlive their access-token expiry for as long as the session
 * row exists, so any refresh secret or JTI the session ever handed out still resolves its family: a
 * replayed old secret revokes the live session, and revoking a stale session-list entry ends it.
 */
@Entity
@Table(
        name = "issued_jwt",
        indexes = @Index(name = "ix_issued_jwt_session_id", columnList = "session_id"),
        uniqueConstraints =
                @UniqueConstraint(name = "uq_issued_jwt_refresh_token_hash", columnNames = "refresh_token_hash"))
@Getter
@Setter
@NoArgsConstructor
public class IssuedJwt {

    @Id
    @Column(name = "jti", columnDefinition = "uuid")
    private UUID jti;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private @Nullable Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    @Nullable
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 32)
    @Nullable
    private RevokedReason revokedReason;

    @Column(name = "user_agent", length = 512)
    @Nullable
    private String userAgent;

    // Postgres `inet`: bind the String through the INET JDBC type so the driver casts it
    // explicitly. Without this Hibernate binds a `varchar`, and the insert fails with
    // "column ip_inet is of type inet but expression is of type character varying".
    @Column(name = "ip_inet", columnDefinition = "inet")
    @JdbcTypeCode(SqlTypes.INET)
    @Nullable
    private String ipInet;

    /** The installed-client session ({@code client_session.id}, the token's {@code sid}); null for browser tokens. */
    @Column(name = "session_id", columnDefinition = "uuid")
    @Nullable
    private UUID sessionId;

    /** SHA-256 (hex) of the refresh secret issued with this token; null for browser tokens. */
    @Column(name = "refresh_token_hash", length = 64)
    @Nullable
    private String refreshTokenHash;

    public IssuedJwt(UUID jti, Long accountId, Instant expiresAt) {
        this.jti = jti;
        this.accountId = accountId;
        this.expiresAt = expiresAt;
    }

    public enum RevokedReason {
        LOGOUT,
        ROTATE,
        SIGN_OUT_EVERYWHERE,
        /** A user revoked one of their OWN sessions ("sign out this device"). */
        SELF_REVOKE,
        ADMIN_REVOKE,
        IMPERSONATION_EXIT,
        ACCOUNT_DELETED,
        /** A rotated-away refresh secret of a live installed-client session was presented again. */
        REFRESH_REUSE,
    }
}
