package de.tum.cit.aet.hephaestus.core.runtime;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;

/**
 * Registers a bean only in a process that runs background work: work that it starts on its own, outside any request.
 * Scheduled tasks, claimers, threads started at startup, and {@code LISTEN} subscribers that act on what they receive
 * are background work. The {@code test}, {@code specs} and {@code cds-training} profiles set {@value #PROPERTY} to
 * false: tests run such work by calling it, and the build profiles have no database. Only the start carries the
 * condition: a bean that a request, a health check or a test calls stays registered.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnBooleanProperty(name = ConditionalOnBackgroundWork.PROPERTY, matchIfMissing = true)
public @interface ConditionalOnBackgroundWork {

    String PROPERTY = "hephaestus.background-work.enabled";
}
