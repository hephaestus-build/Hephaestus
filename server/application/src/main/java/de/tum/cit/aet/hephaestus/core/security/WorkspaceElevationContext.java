package de.tum.cit.aet.hephaestus.core.security;

import org.jspecify.annotations.Nullable;

/**
 * The workspace, if any, that the current request reached through instance-admin elevation rather
 * than through membership. {@code WorkspaceContextFilter} sets it on the elevated branch and clears
 * it in the same {@code finally} block that clears the workspace context.
 *
 * <p>Both audit ledgers read the answer from here rather than taking it from their callers, so a
 * producer can neither forget the flag nor claim one it did not earn — the reasoning
 * {@code ConfigAuditActor} records for actor attribution, applied to the same row.
 *
 * <p>A plain (non-inheritable) {@code ThreadLocal} on purpose: elevation is a property of one
 * request, so a task handed to an executor must start unelevated. An {@code InheritableThreadLocal}
 * would let a background thread spawned mid-request keep writing "elevated" rows long after the
 * request that earned it ended.
 */
public final class WorkspaceElevationContext {

    private static final ThreadLocal<Long> ELEVATED_WORKSPACE = new ThreadLocal<>();

    private WorkspaceElevationContext() {}

    /** Marks this request as having reached {@code workspaceId} by instance-admin elevation. */
    public static void set(long workspaceId) {
        ELEVATED_WORKSPACE.set(workspaceId);
    }

    /**
     * Whether the current request reached <em>this</em> workspace by elevation. Scoped to the id so an
     * instance-scoped change (a null {@code workspaceId}) or a change to a different workspace is
     * never tagged by an elevation the request earned somewhere else.
     */
    public static boolean isElevated(@Nullable Long workspaceId) {
        return workspaceId != null && workspaceId.equals(ELEVATED_WORKSPACE.get());
    }

    public static void clear() {
        ELEVATED_WORKSPACE.remove();
    }
}
