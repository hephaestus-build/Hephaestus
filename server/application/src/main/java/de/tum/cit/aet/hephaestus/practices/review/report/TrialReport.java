package de.tum.cit.aet.hephaestus.practices.review.report;

import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import java.util.ArrayList;
import java.util.List;

/**
 * What a trial keeps in its review's report: each candidate that admission accepted, in place of a result (ADR 0118
 * decision 8). A withheld or refused practice keeps its reason on its membership, as in any review.
 */
public record TrialReport(int schemaVersion, List<ResultCandidate> candidates) {
    public static final int VERSION = 1;

    public TrialReport {
        candidates = List.copyOf(candidates);
    }

    public static TrialReport empty() {
        return new TrialReport(VERSION, List.of());
    }

    public TrialReport with(ResultCandidate candidate) {
        var all = new ArrayList<>(candidates);
        all.add(candidate);
        return new TrialReport(schemaVersion, all);
    }
}
