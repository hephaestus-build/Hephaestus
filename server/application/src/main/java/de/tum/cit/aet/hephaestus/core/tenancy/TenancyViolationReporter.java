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
}
