package de.tum.cit.aet.hephaestus.practices.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Review data that must pass through the assessment port before a conversation can use it.
 * SQL treats every column of a marked entity as restricted unless the column is marked {@link Metadata}.
 * A field marker lets the Java boundary detect data copied through an accessor.
 */
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RawObservationAccess {
    /** Operational metadata that an explicit projection may read from a marked row. */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Metadata {}
}
