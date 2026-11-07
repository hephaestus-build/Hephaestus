package de.tum.cit.aet.hephaestus.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository or repository method as intentionally workspace-agnostic.
 *
 * <p>Effects:
 * <ul>
 *   <li>Architecture tests skip workspace-filter validation for annotated elements.</li>
 *   <li>{@link de.tum.cit.aet.hephaestus.core.tenancy.WorkspaceAgnosticAspect} opens a
 *       {@code TenancyBypass} scope for the duration of the annotated call, so
 *       {@code WorkspaceStatementInspector} does not flag the emitted SQL.</li>
 * </ul>
 *
 * <p><b>When to use:</b>
 *
 * <ul>
 *   <li>Lookup tables (UserRepository)</li>
 *   <li>Sync operations that find by external GitHub ID</li>
 *   <li>Cross-workspace queries for admin operations</li>
 * </ul>
 *
 * <p>The annotation is load-bearing at runtime: dropping it from a repository that queries
 * a workspace-scoped table causes {@code TenancyViolationException} under
 * {@code hephaestus.tenancy.enforcement=throw}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WorkspaceAgnostic {
    /** Reason why this is intentionally workspace-agnostic. */
    String value();
}
