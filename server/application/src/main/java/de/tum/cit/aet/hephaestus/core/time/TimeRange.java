package de.tum.cit.aet.hephaestus.core.time;

import java.time.Instant;

/** A half-open time range, {@code [from, to)}. */
public record TimeRange(Instant from, Instant to) {}
