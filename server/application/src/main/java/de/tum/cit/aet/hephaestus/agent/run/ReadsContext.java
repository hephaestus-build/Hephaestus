package de.tum.cit.aet.hephaestus.agent.run;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a sandbox route that reads a source for its run: the context read boundary of ADR 0134. The run's authority is
 * checked before the route reads, and a run whose authority narrowed since it was requested ends and is refused. A
 * route that takes a {@code SandboxRun} is checked by its argument resolver; a route that an attempt token
 * authenticates checks the attempt's fence itself through {@link AgentRunAuthority}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadsContext {}
