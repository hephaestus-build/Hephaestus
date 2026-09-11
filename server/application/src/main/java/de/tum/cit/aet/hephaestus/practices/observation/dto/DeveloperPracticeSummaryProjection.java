package de.tum.cit.aet.hephaestus.practices.observation.dto;

import java.time.Instant;

/**
 * Interface projection for per-practice aggregation of developer observations.
 *
 * <p>Used by the summary query that groups observations by practice and computes assessment
 * counts (ADR 0022). Spring Data JPA maps the query result columns to these getter methods.
 */
public interface DeveloperPracticeSummaryProjection {
    String getPracticeSlug();

    String getPracticeName();

    Long getTotalObservations();

    /** Count of positive outcomes derived from presence and assessment. */
    Long getPositiveCount();

    /** Count of negative outcomes derived from presence and assessment. */
    Long getNegativeCount();

    Instant getLastObservedAt();
}
