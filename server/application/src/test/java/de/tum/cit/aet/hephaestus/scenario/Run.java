package de.tum.cit.aet.hephaestus.scenario;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** A run of the subject. It declares the readers whose data it may read. */
public record Run(String id, Kind kind, Place place, Set<Reader> audience) {

    public Run {
        if (audience.isEmpty()) {
            throw new IllegalArgumentException(id + " declares no reader, so nothing could judge what it reads");
        }
        // Ordered, so a report lists its findings in the same order in every run.
        audience = Collections.unmodifiableSet(new LinkedHashSet<>(audience));
    }

    public enum Kind {
        /** Answers a person's message. */
        INTERACTIVE,
        /** Writes to a person on its own, such as after a review. */
        BACKGROUND,
        /** Writes the copy on the reviewed work, which every member reads. */
        COMPOSER,
    }

    public enum Outcome {
        ANSWERED,
        CANCELLED,
    }
}
