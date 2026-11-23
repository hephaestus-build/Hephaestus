package de.tum.cit.aet.hephaestus.scenario;

/** A person in a scenario. */
public record Person(String name) {
    @Override
    public String toString() {
        return name;
    }
}
