package de.tum.cit.aet.hephaestus.workspace.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@link WorkspaceScopedController} that serves team workspaces only. A personal space answers its routes as a
 * workspace that does not exist. Every workspace-scoped controller says either this or {@link PersonalAllowed}.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TeamOnly {}
