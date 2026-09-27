package de.tum.cit.aet.hephaestus.activity.overview;

import java.time.Instant;

/** A half-open time range, {@code [from, to)}. */
public record ActivityRange(Instant from, Instant to) {}
