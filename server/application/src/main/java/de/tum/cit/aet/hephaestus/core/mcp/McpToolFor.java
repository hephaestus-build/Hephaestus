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
}
