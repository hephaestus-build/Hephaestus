package de.tum.cit.aet.hephaestus.core;

import java.time.Instant;

/** Time-driven work that an isolated scenario runs when its clock moves. */
@FunctionalInterface
public interface EvaluationClockStep {
    void advance(Instant instant);
}
