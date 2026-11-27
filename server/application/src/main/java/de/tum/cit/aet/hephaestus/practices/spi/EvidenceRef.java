package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An observation, and optionally one of its problems, by id. Advice holds these references, never copies. */
public record EvidenceRef(UUID resultId, @Nullable UUID problemId) {}
