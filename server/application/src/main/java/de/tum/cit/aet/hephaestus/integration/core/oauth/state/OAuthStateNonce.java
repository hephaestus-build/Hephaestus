package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Single-use nonce row for OAuth state-parameter replay protection.
 *
 * <p>The HMAC + TTL scheme used by {@link HmacOAuthStateService} is sufficient
 * against forgery but does NOT prevent replay: an attacker who captures a state
 * during the (10-minute) TTL window can present it a second time and walk into
 * a fresh OAuth callback. The nonce store closes that window — every issued
 * state writes a row here, and {@code consume()} flips {@code consumed_at} via
 * an atomic conditional UPDATE. The first call wins; the second sees 0 rows
 * affected and is rejected.
 *
 * <p><b>Workspace-agnostic by design:</b> the nonce is consumed at the OAuth
 * callback path, BEFORE workspace context is established (workspaceId here is
 * the bound workspace from the issuing flow, not the request's current
 * workspace). Lookup is by the raw nonce — short, indexed by primary key.
 *
 * <p>Pruning: {@link OAuthStateNonceCleanupJob} drops rows older than 7 days
 * daily; with a 10-minute TTL this keeps the table effectively empty even for
 * high-frequency OAuth flows.
 */
@Entity
@Table(name = "oauth_state_nonce")
@WorkspaceAgnostic("Consumed at OAuth callback before workspace context is established")
public class OAuthStateNonce {

    /** The raw nonce produced by {@link HmacOAuthStateService#issue} — base64url, 12 random bytes. */
    @Id
    @Column(name = "nonce", length = 32, nullable = false, updatable = false)
    private String nonce;

    /** Workspace the issuing flow bound the state to (audit + cross-check). */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** {@link de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind} name, kept as string to dodge enum-DB coupling. */
    @Column(name = "kind", length = 48, nullable = false, updatable = false)
    private String kind;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    /** Set by the atomic UPDATE in {@link OAuthStateNonceStore#tryConsume}; null while pristine. */
    @Column(name = "consumed_at")
    @Nullable
    private Instant consumedAt;

    @Column(name = "actor_account_id", updatable = false)
    @Nullable
    private Long actorAccountId;

    protected OAuthStateNonce() {}

    public OAuthStateNonce(
            String nonce, long workspaceId, String kind, Instant issuedAt, @Nullable Long actorAccountId) {
        this.nonce = nonce;
        this.workspaceId = workspaceId;
        this.kind = kind;
        this.issuedAt = issuedAt;
        this.actorAccountId = actorAccountId;
    }

    @Nullable
    public Long getActorAccountId() {
        return actorAccountId;
    }

    public String getNonce() {
        return nonce;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public String getKind() {
        return kind;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    @Nullable
    public Instant getConsumedAt() {
        return consumedAt;
    }
}
