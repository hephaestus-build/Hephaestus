package de.tum.cit.aet.hephaestus.core.runtime;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/**
 * The agent runtime of a server: the sandboxes, their gateway, the LLM proxy, the review executor and the conversation
 * turns. It runs on the server role with agent jobs on ({@link RuntimeRole#AGENT_ENABLED_PROPERTY}), which the chart
 * sets with {@code sandbox.enabled}. A server without it serves the API and syncs, and starts no sandbox.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression(
        "${" + RuntimeRole.SERVER_PROPERTY + ":true} and ${" + RuntimeRole.AGENT_ENABLED_PROPERTY + ":false}")
public @interface ConditionalOnAgentRuntime {}
