package de.tum.cit.aet.hephaestus.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

/**
 * A count of people that {@link SmallCounts} let through: zero, or five or more. Only {@link SmallCounts#count(int)}
 * builds one, and it returns null for one to four, so a response field of this type can never carry a small count.
 * It serializes as the bare number.
 */
public final class ShownCount {

    private final int people;

    private ShownCount(int people) {
        this.people = people;
    }

    /** Only {@link SmallCounts} calls this, after it decided that the count may show. Refuses 1 to 4 all the same. */
    static ShownCount of(int people) {
        if (people < 0 || (people > 0 && !SmallCounts.enough(people))) {
            throw new IllegalArgumentException("A shown count of people is zero or at least " + SmallCounts.MINIMUM);
        }
        return new ShownCount(people);
    }

    /** Reads a count back, as a client of the API does; the same check applies. */
    @JsonCreator
    private static ShownCount read(int people) {
        return of(people);
    }

    @JsonValue
    public int people() {
        return people;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ShownCount count && count.people == people;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(people);
    }

    @Override
    public String toString() {
        return Integer.toString(people);
    }
}
