package de.tum.cit.aet.hephaestus.core.auth.jwt;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Absolute session and interactive sign-in deadlines, preserved unchanged through token rotation.
 *
 * @param sessionId the installed-client session the token belongs to ({@code sid}); absent for browser
 *                  sessions, which only the cookie refresh may rotate
 * @param passkeyCredential the passkey credential whose assertion set {@code passkeyTime}; it travels with that time
 * @param mcp       the workspace MCP endpoint and scopes of an MCP client session; such a token names only
 *                  that endpoint in {@code aud} and carries no roles (ADR 0110 decision 11)
 */
public record TokenConstraints(
        @Nullable Instant sessionExpiresAt,
        @Nullable Instant authTime,
        @Nullable UUID sessionId,
        @Nullable Instant passkeyTime,
        @Nullable String passkeyCredential,
        @Nullable McpAudience mcp) {

    /** The one resource an MCP token is for, and the scopes its grant holds. */
    public record McpAudience(String resource, String scope) {}

    public TokenConstraints(
            @Nullable Instant sessionExpiresAt,
            @Nullable Instant authTime,
            @Nullable UUID sessionId,
            @Nullable Instant passkeyTime) {
        this(sessionExpiresAt, authTime, sessionId, passkeyTime, null, null);
    }

    /** A browser session's constraints, with the passkey proof it carries. */
    public static TokenConstraints browserSession(
            @Nullable Instant sessionExpiresAt,
            @Nullable Instant authTime,
            @Nullable Instant passkeyTime,
            @Nullable String passkeyCredential) {
        return new TokenConstraints(
                sessionExpiresAt, authTime, null, passkeyTime, passkeyTime == null ? null : passkeyCredential, null);
    }

    public static TokenConstraints session(@Nullable Instant sessionExpiresAt, @Nullable Instant authTime) {
        return new TokenConstraints(sessionExpiresAt, authTime, null, null);
    }

    /** An installed-client session: the browser-session deadlines plus the {@code sid} its refresh secret rotates. */
    public static TokenConstraints clientSession(UUID sessionId, Instant sessionExpiresAt, Instant authTime) {
        return new TokenConstraints(sessionExpiresAt, authTime, sessionId, null);
    }

    /** An MCP client session: an installed-client session whose token is shaped by its grant. */
    public static TokenConstraints mcpClientSession(
            UUID sessionId, Instant sessionExpiresAt, Instant authTime, McpAudience mcp) {
        return new TokenConstraints(sessionExpiresAt, authTime, sessionId, null, null, mcp);
    }
}
