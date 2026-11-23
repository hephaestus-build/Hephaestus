package de.tum.cit.aet.hephaestus.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a response component that counts people, or a figure read from a group of people, such as a range. Without
 * a reason, {@link SmallCounts} decides the value, so the component is nullable. With a reason, the value is exact,
 * and the reason says why it tells no reader anything about one person: the reader already sees these people by
 * name, and no action of the reader isolates one of them. {@code ResponsePeopleCountTest} requires every number that
 * a route returns to carry this annotation or to be listed as counting no people.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface PeopleCount {

    /** Why the exact value can show. Empty: the value goes through {@link SmallCounts}. */
    String exactBecause() default "";
}
