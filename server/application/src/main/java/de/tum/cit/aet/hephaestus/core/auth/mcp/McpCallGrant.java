package de.tum.cit.aet.hephaestus.core.auth.mcp;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The grant that a workspace MCP request passed {@link McpGrantGuard} with. The guard leaves it on the request, so
 * the write-scope check and the config audit trail read the grant that was checked, not one they look up again.
 *
 * @param grantId the grant's id, which the config audit trail records as the client that made a change
 * @param writes whether the grant holds {@link McpScope#WRITE}
 * @param passkeyBackedUntil until when the passkey proof given at consent stands for the grant's calls, or null when
 *     the grant holds no passkey proof
 */
public record McpCallGrant(
        UUID grantId, boolean writes, @Nullable Instant passkeyBackedUntil) {

    /** The request attribute that holds the checked grant. */
    public static final String ATTRIBUTE = McpCallGrant.class.getName();

    /** The grant of the current request, if it is a workspace MCP request that passed the guard. */
    public static Optional<McpCallGrant> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        return attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof McpCallGrant grant
                ? Optional.of(grant)
                : Optional.empty();
    }
}
