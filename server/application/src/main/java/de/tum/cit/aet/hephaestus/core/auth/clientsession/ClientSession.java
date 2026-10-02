package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
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
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * One signed-in installed client. Its id is the {@code sid} of every access token it holds; the tokens
 * and the hashes of the refresh secrets issued with them live on {@code issued_jwt} rows linked by
 * {@code session_id}. The deadline and {@code auth_time} are fixed at sign-in and never extended by a
 * rotation. Rows are only ever ended by a conditional bulk update under the row lock, never saved from a
 * possibly stale entity.
 */
@Entity
@Table(
        name = "client_session",
        indexes = {
            @Index(name = "ix_client_session_account_id", columnList = "account_id"),
            @Index(name = "ix_client_session_session_expires_at", columnList = "session_expires_at"),
        })
@Getter
@NoArgsConstructor
public class ClientSession {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_kind", nullable = false, length = 32)
    private InstalledClientKind clientKind;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

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
    private IssuedJwt.@Nullable RevokedReason revokedReason;

    ClientSession(
            UUID id,
            Long accountId,
            InstalledClientKind clientKind,
            String clientId,
            Instant sessionExpiresAt,
            Instant authTime) {
        this.id = id;
        this.accountId = accountId;
        this.clientKind = clientKind;
        this.clientId = clientId;
        this.sessionExpiresAt = sessionExpiresAt;
        this.authTime = authTime;
    }
}
