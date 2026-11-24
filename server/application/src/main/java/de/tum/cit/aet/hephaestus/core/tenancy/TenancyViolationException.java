package de.tum.cit.aet.hephaestus.core.tenancy;

import java.io.Serial;
import java.util.Set;

/**
 * Thrown by {@link WorkspaceStatementInspector} when enforcement mode is
 * {@link TenancyEnforcement#THROW} and a SQL statement against a workspace-scoped table
 * lacks a {@code workspace_id} predicate, or when the inspector cannot analyze the statement.
 *
 * <p>Maps to HTTP 500 (server bug — never 4xx). The exception message is sanitized for
 * client display: the SQL itself is logged server-side but never echoed in the HTTP
 * response, to avoid leaking schema details.
 */
public final class TenancyViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    // Set.copyOf produces a serializable immutable set when its String elements are serializable.
    @SuppressWarnings("serial")
    private final Set<String> unguardedTables;

    public TenancyViolationException(Set<String> unguardedTables) {
        super("Tenancy violation: " + reason(unguardedTables));
        this.unguardedTables = Set.copyOf(unguardedTables);
    }

    public Set<String> unguardedTables() {
        return unguardedTables;
    }

    static String reason(Set<String> unguardedTables) {
        return unguardedTables.isEmpty()
                ? "the statement could not be analyzed"
                : "workspace-scoped table(s) queried without workspace_id predicate: " + unguardedTables;
    }
}
