package de.tum.cit.aet.hephaestus.core.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a free-form component of an event as holding no words that a person wrote: an id, a key, a fixed code, the URL
 * of a public resource, or a configuration snapshot. An event that crosses a module boundary, or that the event
 * publication registry stores, carries content only by reference: the registry keeps each event in
 * {@code event_publication}, where no erasure reaches it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface ContentFree {}
