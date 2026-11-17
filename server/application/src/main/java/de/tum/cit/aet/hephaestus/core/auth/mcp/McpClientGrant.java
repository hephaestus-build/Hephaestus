package de.tum.cit.aet.hephaestus.core.auth.mcp;

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
 * One MCP client's permission to call one workspace's MCP endpoint for one account (ADR 0110 decision 9).
 * The code lives in {@code client_sign_in_handoff} and the tokens in {@code issued_jwt}; this row holds the
 * workspace, the resource and the scopes, and is the single home of those facts.
 */
@Entity
@Table(
        name = "mcp_client_grant",
        indexes = {
            @Index(name = "ix_mcp_client_grant_workspace_id", columnList = "workspace_id"),
            @Index(name = "ix_mcp_client_grant_account_id", columnList = "account_id"),
            @Index(name = "ix_mcp_client_grant_client_session_id", columnList = "client_session_id"),
        })
@Getter
@NoArgsConstructor
public class McpClientGrant {

    /** The client's resource URIs are URLs, and URLs on the web are capped near this length in practice. */
    static final int URL_LENGTH = 2048;

    public enum Status {
        /** Waiting for the account to approve or refuse it on the consent page. */
        PENDING,
        /** Approved; its code waits in {@code client_sign_in_handoff}. */
        APPROVED,
        /** Its code was exchanged; it names a live client session. */
        ACTIVE,
        /** The account refused it on the consent page. */
        REFUSED,
        /** Ended by the account, a workspace admin, or an account-wide revocation. */
        REVOKED,
        /** Its consent request or its code ran out before the next step. */
        EXPIRED,
    }

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "client_id", nullable = false, length = URL_LENGTH)
    private String clientId;

    /** Self-declared by the client and shown only as plain text below the client's host. */
    @Column(name = "client_name", nullable = false, length = ClientMetadataResolver.CLIENT_NAME_LENGTH)
    private String clientName;

    @Column(name = "redirect_uri", nullable = false, length = URL_LENGTH)
    private String redirectUri;

    /** The literal workspace endpoint this grant names; a token's {@code aud} is exactly this value. */
    @Column(name = "resource", nullable = false, length = URL_LENGTH)
    private String resource;

    /** Space-separated, as OAuth writes scopes. */
    @Column(name = "scopes", nullable = false, length = 128)
    private String scopes;

    @Column(name = "code_challenge", nullable = false, length = 64)
    private String codeChallenge;

    @Column(name = "state", length = 512)
    private @Nullable String state;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "client_session_id", columnDefinition = "uuid")
    private @Nullable UUID clientSessionId;

    /** Until when the consent request may be decided. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @Column(name = "approved_at")
    private @Nullable Instant approvedAt;

    @Column(name = "ended_at")
    private @Nullable Instant endedAt;

    /** When the approving account confirmed with a passkey on the consent page; null when the policy asked for none. */
    @Column(name = "passkey_verified_at")
    private @Nullable Instant passkeyVerifiedAt;

    /** The passkey credential that confirmed the approval. The assertion itself is never stored. */
    @Column(name = "passkey_credential_id", length = 2048)
    private @Nullable String passkeyCredentialId;

    McpClientGrant(
            UUID id,
            Long workspaceId,
            Long accountId,
            String clientId,
            String clientName,
            String redirectUri,
            String resource,
            String scopes,
            String codeChallenge,
            @Nullable String state,
            Instant expiresAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.accountId = accountId;
        this.clientId = clientId;
        this.clientName = clientName;
        this.redirectUri = redirectUri;
        this.resource = resource;
        this.scopes = scopes;
        this.codeChallenge = codeChallenge;
        this.state = state;
        this.status = Status.PENDING;
        this.expiresAt = expiresAt;
    }

    void approve(Instant now) {
        this.status = Status.APPROVED;
        this.approvedAt = now;
    }

    void approveWithPasskey(Instant now, Instant verifiedAt, String credentialId) {
        approve(now);
        this.passkeyVerifiedAt = verifiedAt;
        this.passkeyCredentialId = credentialId;
    }

    void refuse(Instant now) {
        this.status = Status.REFUSED;
        this.endedAt = now;
    }

    public void activate(UUID sessionId) {
        this.status = Status.ACTIVE;
        this.clientSessionId = sessionId;
    }

    void revoke(Instant now) {
        this.status = Status.REVOKED;
        this.endedAt = now;
    }

    void expire(Instant now) {
        this.status = Status.EXPIRED;
        this.endedAt = now;
    }

    boolean grants(McpScope scope) {
        return McpScope.parse(scopes).stream().anyMatch(granted -> granted.implies(scope));
    }
}
