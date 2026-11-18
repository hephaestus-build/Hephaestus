package de.tum.cit.aet.hephaestus.practices.spi;

public enum ReviewOrigin {
    EVENT,
    REQUEST,
    SCHEDULE,
    BACKFILL,
    /**
     * A workspace admin tries one practice on one item. Capture, the runner and admission run as for a request, but
     * the admitted candidate goes to the review's report, never to a result: a trial never seals, never composes and
     * counts in no metric (ADR 0118 decision 8).
     */
    TRIAL;
}
