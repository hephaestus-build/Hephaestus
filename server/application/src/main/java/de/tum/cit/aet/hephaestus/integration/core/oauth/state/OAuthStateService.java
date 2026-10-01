package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Signed, single-use OAuth state-parameter helper.
 *
 * <p>Issued at the start of an OAuth redirect; consumed exactly once at the callback.
 * Default impl signs (HMAC) the payload + an issue timestamp; verifying rejects
 * tokens older than the configured TTL. Prevents CSRF on the OAuth callback path —
 * an attacker cannot fabricate a state that binds to a workspace they don't own.
 *
 * <p>The optional {@code actorAccountId} carried by {@link StateBinding} records WHO
 * initiated the flow (authenticated account id of the admin who clicked "Connect"). It is
 * encoded in the signed payload so the callback can attribute the audit row to the
 * actual user — the vendor redirect arrives unauthenticated, so there is no
 * {@code Authentication} in the SecurityContext at that point to read.
 */
public interface OAuthStateService {
    /** Mints a state parameter binding the OAuth flow to {@code workspaceId} + {@code kind}. */
    default String issue(long workspaceId, IntegrationKind kind) {
        return issue(workspaceId, kind, null);
    }

    /** Binds the exact initiating account, or null for a system flow. */
    String issue(long workspaceId, IntegrationKind kind, @Nullable Long actorAccountId);

    /** Verifies the state; returns the binding if valid and not expired+used; throws otherwise. */
    StateBinding consume(String state);

    /**
     * Decoded payload from a valid state token.
     *
     * <p>{@code actorAccountId} is the authenticated account id of the user that initiated the OAuth
     * flow; {@code null} for tokens issued via the no-actor overload.
     */
    record StateBinding(
            long workspaceId,
            IntegrationKind kind,
            Instant issuedAt,
            @Nullable Long actorAccountId) {
        /** Convenience constructor that defaults actorAccountId to null. */
        public StateBinding(long workspaceId, IntegrationKind kind, Instant issuedAt) {
            this(workspaceId, kind, issuedAt, null);
        }
    }
}
