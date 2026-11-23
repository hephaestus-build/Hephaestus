package de.tum.cit.aet.hephaestus.core.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code String} component of an event as an identifier, such as a provider id, a slug or a key, and never
 * words that a person wrote. The event publication registry stores every event it delivers, so an event carries ids
 * only.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Identifier {}
