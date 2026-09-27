package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * The single-use code the browser federation hands to the native app after a successful sign-in. Only
 * the hash of the code is stored; the app redeems it with the PKCE verifier whose S256 challenge it sent
 * when the sign-in began, so a code intercepted on its way back through the app's URL scheme is useless.
 */
@Entity
@Table(
        name = "native_sign_in_handoff",
        indexes = {
            @Index(name = "ix_native_sign_in_handoff_account_id", columnList = "account_id"),
            @Index(name = "ix_native_sign_in_handoff_expires_at", columnList = "expires_at"),
        })
@Getter
@Setter
@NoArgsConstructor
public class NativeSignInHandoff {

    @Id
    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Base64url S256 PKCE challenge (RFC 7636). */
    @Column(name = "code_challenge", nullable = false, length = 128)
    private String codeChallenge;

    @Column(name = "session_expires_at", nullable = false)
    private Instant sessionExpiresAt;

    @Column(name = "auth_time", nullable = false)
    private Instant authTime;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private @Nullable Instant consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    public NativeSignInHandoff(
            String codeHash,
            Long accountId,
            String codeChallenge,
            Instant sessionExpiresAt,
            Instant authTime,
            Instant expiresAt) {
        this.codeHash = codeHash;
        this.accountId = accountId;
        this.codeChallenge = codeChallenge;
        this.sessionExpiresAt = sessionExpiresAt;
        this.authTime = authTime;
        this.expiresAt = expiresAt;
    }
}
