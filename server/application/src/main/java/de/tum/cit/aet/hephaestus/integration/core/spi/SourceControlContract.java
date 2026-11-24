package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type of the shared integration kernel that only source control has, such as a pull request comment or a
 * monitored repository. The mentoring modules speak of people, records and threads, so they depend on no such type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SourceControlContract {}
