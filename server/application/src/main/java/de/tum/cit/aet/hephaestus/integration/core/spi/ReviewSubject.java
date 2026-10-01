package de.tum.cit.aet.hephaestus.integration.core.spi;

import org.jspecify.annotations.Nullable;

public record ReviewSubject(@Nullable Long actorId, boolean human, ActorRole role) {
    public ReviewSubject(@Nullable Long actorId, boolean human) {
        this(actorId, human, ActorRole.AUTHOR);
    }

    public static ReviewSubject reviewer(@Nullable Long actorId, boolean human) {
        return new ReviewSubject(actorId, human, ActorRole.REVIEWER);
    }

    public static final ReviewSubject MISSING = new ReviewSubject(null, false);
}
