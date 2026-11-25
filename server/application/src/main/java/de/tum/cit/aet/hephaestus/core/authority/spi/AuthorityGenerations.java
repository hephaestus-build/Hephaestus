package de.tum.cit.aet.hephaestus.core.authority.spi;

import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusal;
import de.tum.cit.aet.hephaestus.core.authority.AuthorizationStamp;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The current authorization generations. Only an authority narrowing raises one (ADR 0134 decision 2). The workspace
 * module owns the workspace's generation.
 */
public interface AuthorityGenerations {

    /** The workspace's authorization generation; empty when the workspace no longer exists. */
    OptionalLong workspace(long workspaceId);

    /**
     * The stamp for work requested now. Take it before the authority check that it covers, in the same transaction: a
     * narrowing that commits in between then makes the stamp stale, so the work is refused at its next boundary.
     */
    default AuthorizationStamp stamp(long workspaceId) {
        return new AuthorizationStamp(workspace(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Work belongs to a workspace that exists")));
    }

    /** The refusal for work that carries {@code stamp} at {@code boundary}; empty while its authority holds. */
    default Optional<AuthorityRefusal> check(AuthorizationStamp stamp, long workspaceId, AuthorityBoundary boundary) {
        OptionalLong current = workspace(workspaceId);
        if (current.isPresent() && current.getAsLong() == stamp.workspace()) return Optional.empty();
        return Optional.of(new AuthorityRefusal(boundary, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED));
    }
}
