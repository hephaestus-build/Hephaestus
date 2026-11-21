package de.tum.cit.aet.hephaestus.core.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The HTTP handler that a workspace MCP tool stands for. The tool calls that handler's service, and its
 * {@code call} method carries the same authorization; {@code McpToolHandlerParityArchTest} compares them.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpToolFor {

    Class<?> controller();

    /** The handler method's name; it is unique within the controller. */
    String method();

    /**
     * Whether the handler is a POST only because it takes a request body, and saves nothing, as a check does. The
     * handler is then {@code @AuditExempt}, and the tool reads: it needs only {@code practices:read} and repeats the
     * handler's {@code @AuditExempt}.
     */
    boolean savesNothing() default false;

    /**
     * Whether the tool reads, but returns what only {@code practices:write} may read, as trial output is (ADR 0110, update
     * of 2026-11-16). The tool then declares that it writes, so its call needs that scope, and repeats no audit
     * annotation, as a read does.
     */
    boolean needsWriteScope() default false;
}
