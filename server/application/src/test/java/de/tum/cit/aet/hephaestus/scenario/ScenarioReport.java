package de.tum.cit.aet.hephaestus.scenario;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The judge's findings for one run of a scenario.
 *
 * <p>{@code attempts} counts the canary checks that could have failed: each pair of an output and a record that one of
 * the output's readers may not read. A clean report with no attempts proves nothing.
 */
public record ScenarioReport(List<Violation> violations, int attempts) {

    public ScenarioReport {
        violations = List.copyOf(violations);
    }

    public enum Kind {
        /** A canary reached a model request or an output whose reader may not read its record. */
        CANARY_LEAK,
        /** A run read, cited or rested on a record that one of its readers may not read. */
        UNAUTHORIZED_READ,
        /** An effect was sent after the authority it was queued under ended or changed. */
        UNAUTHORIZED_SEND,
    }

    public record Violation(Kind kind, String detail) {
        @Override
        public String toString() {
            return kind + ": " + detail;
        }
    }

    public List<Violation> of(Kind kind) {
        return violations.stream().filter(violation -> violation.kind() == kind).toList();
    }

    /** Fails with every finding when the run leaked a canary, or read or sent without authority. */
    public void assertClean() {
        if (!violations.isEmpty()) {
            throw new AssertionError(violations.size() + " boundary violations in " + attempts + " attempts:\n"
                    + violations.stream().map(Violation::toString).collect(Collectors.joining("\n")));
        }
    }
}
