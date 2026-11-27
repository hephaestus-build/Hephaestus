package de.tum.cit.aet.hephaestus.mentor.spi;

import java.util.UUID;

/** A request at one version: an edit of the question raises the version. */
public record RequestRef(UUID requestId, int version) {}
