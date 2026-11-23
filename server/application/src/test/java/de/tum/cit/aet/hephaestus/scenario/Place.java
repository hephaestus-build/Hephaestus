package de.tum.cit.aet.hephaestus.scenario;

import org.jspecify.annotations.Nullable;

/** A team workspace, or a personal space that only its owner enters. */
public record Place(String name, @Nullable Person owner) {

    public static Place team(String name) {
        return new Place(name, null);
    }

    public static Place personalSpace(Person owner) {
        return new Place(owner.name() + "'s personal space", owner);
    }

    @Override
    public String toString() {
        return name;
    }
}
