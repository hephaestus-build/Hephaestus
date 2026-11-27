package de.tum.cit.aet.hephaestus.mentor.spi;

/** Which producer handles the events of a route. The transport persists it per event at intake. */
public enum Producer {
    LEGACY,
    MENTOR
}
