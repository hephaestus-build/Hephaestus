package de.tum.cit.aet.hephaestus.core.authority.spi;

import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusal;
import de.tum.cit.aet.hephaestus.core.authority.AuthorizationStamp;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

/**
 * The current authorization generations. Only an authority narrowing raises one (ADR 0134 decision 2). The workspace
 * module owns the workspace's generation.
 */
public interface AuthorityGenerations {

    /** The workspace's authorization generation; empty when the workspace no longer exists. */
    OptionalLong workspace(long workspaceId);

    /**
     * Reads the stamp for work requested now, then runs {@code access}, the caller's own access check, in the same
     * transaction. A narrowing that commits after the read makes the stamp stale, so the work is refused at its next
     * boundary, also when {@code access} still saw the old authority.
     *
     * @return the authority that {@code access} granted; empty when it refused
     */
    default Optional<CheckedAuthority> authorize(long workspaceId, BooleanSupplier access) {
        var stamp = new AuthorizationStamp(workspace(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Work belongs to a workspace that exists")));
        return access.getAsBoolean() ? Optional.of(new CheckedAuthority(workspaceId, stamp)) : Optional.empty();
    }

    /**
     * The authority of work that is requested now with no access check of its own in this transaction, such as a
     * review that an event selected. A caller that checks access passes its check to {@link #authorize} instead.
     */
    default CheckedAuthority requestedNow(long workspaceId) {
        return authorize(workspaceId, () -> true).orElseThrow();
    }

    /**
     * The workspace's authorization generation, held until the caller's transaction ends, so no narrowing commits while
     * that transaction admits work under it. Requires a transaction.
     */
    OptionalLong workspaceHeld(long workspaceId);

    /** The refusal for work that carries {@code stamp} at {@code boundary}; empty while its authority holds. */
    default Optional<AuthorityRefusal> check(AuthorizationStamp stamp, long workspaceId, AuthorityBoundary boundary) {
        OptionalLong current = workspace(workspaceId);
        if (current.isPresent() && current.getAsLong() == stamp.workspace()) return Optional.empty();
        return Optional.of(new AuthorityRefusal(boundary, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED));
    }
}
