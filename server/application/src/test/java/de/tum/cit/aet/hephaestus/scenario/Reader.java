package de.tum.cit.aet.hephaestus.scenario;

/** One concrete reader of an output: a person who reads it in a place. */
public record Reader(Person person, Place place) {
    @Override
    public String toString() {
        return person + " in " + place;
    }
}
