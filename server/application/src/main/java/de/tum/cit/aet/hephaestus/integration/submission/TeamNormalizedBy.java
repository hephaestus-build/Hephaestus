package de.tum.cit.aet.hephaestus.integration.submission;

/** Who turned the source into the normalized document. */
public enum TeamNormalizedBy {
    /** The server read the source and stores both its bytes and the normalized document. */
    SERVER,
    /** The sender previewed the normalized document and sent it: the content is that document. */
    CLIENT
}
