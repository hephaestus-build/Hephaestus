package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * The single-use code a completed sign-in hands to an installed client through its registered callback.
 * Only the code's hash is stored. The client redeems it with the PKCE verifier whose S256 challenge it
 * sent when the sign-in began, presenting the same client id and callback, so a code intercepted on its
 * way back is useless.
 */
@Entity
@Table(
        name = "client_sign_in_handoff",
        indexes = {
            @Index(name = "ix_client_sign_in_handoff_account_id", columnList = "account_id"),
            @Index(name = "ix_client_sign_in_handoff_expires_at", columnList = "expires_at"),
        })
@Getter
@NoArgsConstructor
public class ClientSignInHandoff {

    @Id
    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_kind", nullable = false, length = 32)
    private InstalledClientKind clientKind;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "redirect_uri", nullable = false, length = 255)
    private String redirectUri;

    /** Base64url S256 PKCE challenge (RFC 7636). */
    @Column(name = "code_challenge", nullable = false, length = 64)
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

    ClientSignInHandoff(
            String codeHash,
            Long accountId,
            InstalledClient client,
            String codeChallenge,
            Instant sessionExpiresAt,
            Instant authTime,
            Instant expiresAt) {
        this.codeHash = codeHash;
        this.accountId = accountId;
        this.clientKind = client.kind();
        this.clientId = client.clientId();
        this.redirectUri = client.redirectUri();
        this.codeChallenge = codeChallenge;
        this.sessionExpiresAt = sessionExpiresAt;
        this.authTime = authTime;
        this.expiresAt = expiresAt;
    }
}
