package de.tum.cit.aet.hephaestus.core.authority.spi;

import de.tum.cit.aet.hephaestus.core.authority.AuthorizationStamp;
import java.util.Objects;

/**
 * Proof of the authority that new work in a workspace is requested under: the stamp that the access check read before
 * it decided (ADR 0134). A run or an action takes one, so the compiler finds every place that queues work, and no stamp
 * can be read after the check that it covers. A narrowing that commits after the stamp was read refuses the work at its
 * next boundary.
 *
 * <p>Only {@link AuthorityGenerations#authorize} reads a current stamp. {@link #inherited} keeps the stamp of stored
 * work that the new work derives from.
 */
public final class CheckedAuthority {

    private final long workspaceId;
    private final AuthorizationStamp stamp;

    CheckedAuthority(long workspaceId, AuthorizationStamp stamp) {
        this.workspaceId = workspaceId;
        this.stamp = Objects.requireNonNull(stamp);
    }

    /**
     * The authority of work that continues stored work, such as the Slack reply of a turn: it keeps the stamp that the
     * stored work was requested under, never a newer one.
     */
    public static CheckedAuthority inherited(long workspaceId, AuthorizationStamp requestedUnder) {
        return new CheckedAuthority(workspaceId, requestedUnder);
    }

    public long workspaceId() {
        return workspaceId;
    }

    public AuthorizationStamp stamp() {
        return stamp;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CheckedAuthority checked
                && checked.workspaceId == workspaceId
                && checked.stamp.equals(stamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceId, stamp);
    }

    @Override
    public String toString() {
        return "CheckedAuthority[workspace=" + workspaceId + ", stamp=" + stamp + "]";
    }
}
