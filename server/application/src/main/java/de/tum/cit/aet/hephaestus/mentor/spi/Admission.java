package de.tum.cit.aet.hephaestus.mentor.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What admitting a provider event did. */
public sealed interface Admission {
    /** The event is admitted; a new question also opened its request. */
    record Admitted(UUID eventId, @Nullable RequestRef request) implements Admission {}

    /** The same provider event was admitted before. */
    record Duplicate(UUID eventId) implements Admission {}
}
