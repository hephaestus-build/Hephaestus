package de.tum.cit.aet.hephaestus.workspace.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@link WorkspaceScopedController} that also serves a personal space, to its owner. Opt in only for what a person
 * does alone: a route that administers members, providers or team settings stays {@link TeamOnly}.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PersonalAllowed {}
