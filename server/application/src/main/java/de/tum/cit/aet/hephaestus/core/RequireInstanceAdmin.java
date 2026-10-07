package de.tum.cit.aet.hephaestus.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/** Requires the instance-admin role and its configured authentication assurance. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('app_admin')")
@AdminAccess(AdminAccess.Scope.INSTANCE)
public @interface RequireInstanceAdmin {}
