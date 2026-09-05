package de.tum.cit.aet.hephaestus.core.auth.spi;

/**
 * Records that an account reached a workspace on instance-admin authority instead of membership.
 * A port rather than a direct call so {@code workspace} keeps its dependency on the auth module's
 * named interface and never on {@code core.auth.audit}'s internals.
 *
 * <p>Implementations absorb their own failures: an audit write must never break the request that
 * triggered it.
 */
public interface WorkspaceElevationAudit {
    void recordElevatedAccess(long accountId, long workspaceId);
}
