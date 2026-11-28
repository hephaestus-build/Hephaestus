package de.tum.cit.aet.hephaestus.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a response type that carries a person's private content inside another shape: their words, or Hephaestus's
 * words to them. A type that a route of the person's own conversation, Practice profile or support shares returns is
 * private without this mark. {@code StaffRoutesReturnNoPrivateContentTest} fails when a staff route returns either
 * (ADR 0125, ADR 0164).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PrivateContent {}
