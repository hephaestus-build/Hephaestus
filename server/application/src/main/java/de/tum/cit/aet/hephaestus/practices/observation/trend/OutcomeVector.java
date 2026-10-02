package de.tum.cit.aet.hephaestus.practices.observation.trend;

import de.tum.cit.aet.hephaestus.practices.model.Outcome;

/** Counts every recorded result; abstentions do not change the decided denominator. */
public record OutcomeVector(int met, int notMet, int notApplicable, int undetermined) {
    static final OutcomeVector EMPTY = new OutcomeVector(0, 0, 0, 0);

    public OutcomeVector {
        if (met < 0 || notMet < 0 || notApplicable < 0 || undetermined < 0) {
            throw new IllegalArgumentException("Outcome counts must be non-negative");
        }
    }

    public static OutcomeVector of(Outcome outcome) {
        return switch (outcome) {
            case MET -> new OutcomeVector(1, 0, 0, 0);
            case NOT_MET -> new OutcomeVector(0, 1, 0, 0);
            case NOT_APPLICABLE -> new OutcomeVector(0, 0, 1, 0);
            case UNDETERMINED -> new OutcomeVector(0, 0, 0, 1);
        };
    }

    public OutcomeVector plus(OutcomeVector other) {
        return new OutcomeVector(
                met + other.met,
                notMet + other.notMet,
                notApplicable + other.notApplicable,
                undetermined + other.undetermined);
    }

    public int decided() {
        return met + notMet;
    }

    public double metShare() {
        if (decided() == 0) {
            throw new IllegalStateException("Undecided results have no met share");
        }
        return (double) met / decided();
    }
}
