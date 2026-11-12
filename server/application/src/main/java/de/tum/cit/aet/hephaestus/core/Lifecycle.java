package de.tum.cit.aet.hephaestus.core;

/** The legal state changes of one stored lifecycle. */
public interface Lifecycle<S extends Enum<S> & Lifecycle<S>> {
    boolean canTransitionTo(S next);
}
