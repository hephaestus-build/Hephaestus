package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The person columns, as {@code table.column}, that the stores of a catalog or a contributor erase in a way that a
 * {@link JdbcPersonDataStore}'s predicate and redaction do not state plainly, such as a match on the person's exact
 * native identities or erasure code of its own. The person-data map test reads it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ErasesColumns {
    String[] value();
}
