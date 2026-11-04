package de.tum.cit.aet.hephaestus.core.tenancy;

import java.util.Set;

/**
 * Side-effect handler for tenancy violations detected by {@link WorkspaceStatementInspector}.
 *
 * <p>Decoupled from the inspector so the inspector itself stays a pure SQL analyzer:
 * the reporter wraps the Micrometer counter, structured logger, and conditional
 * {@code throw} decision based on the active enforcement mode.
 */
@FunctionalInterface
public interface TenancyViolationReporter {
    void report(String sql, Set<String> unguardedTables, TenancyEnforcement mode);

    /**
     * A statement that the inspector would refuse, but that runs inside a bypass that only type-level
     * {@code @WorkspaceAgnostic} markers opened. Never throws: it only counts, so that those markers can be replaced
     * by method-level ones (ADR 0077).
     */
    default void reportBypassed(String sql, Set<String> unguardedTables, Set<String> silencingTypes) {}
}
