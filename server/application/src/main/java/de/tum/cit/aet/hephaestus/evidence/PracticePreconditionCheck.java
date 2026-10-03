package de.tum.cit.aet.hephaestus.evidence;

import java.util.List;
import java.util.Objects;

/**
 * Result of evaluating a practice's mechanical precondition.
 *
 * @param absent      whether the subject was proven not to be in the work
 * @param describedAs the practice author's explanation of that absence
 * @param clauses     one entry per declared alternative, in declaration order
 */
public record PracticePreconditionCheck(
        boolean absent, String describedAs, List<PracticePreconditionClauseCheck> clauses) {
    public PracticePreconditionCheck {
        Objects.requireNonNull(describedAs, "describedAs");
        if (describedAs.isBlank()) {
            throw new IllegalArgumentException("A subject check must carry the sentence describing its absence");
        }
        clauses = List.copyOf(Objects.requireNonNull(clauses, "clauses"));
        if (clauses.isEmpty()) {
            throw new IllegalArgumentException("A subject check requires at least one clause");
        }
        boolean everyClauseSettledAndEmpty =
                clauses.stream().allMatch(clause -> clause.result() == PracticePreconditionResult.NOT_FOUND);
        if (absent != everyClauseSettledAndEmpty) {
            throw new IllegalArgumentException(
                    "A subject is absent exactly when every declared alternative was decided and none was found");
        }
    }
}
