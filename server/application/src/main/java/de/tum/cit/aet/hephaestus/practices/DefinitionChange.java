package de.tum.cit.aet.hephaestus.practices;

import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Explicit intent for changes that affect applicability or attribution. */
public enum DefinitionChange {
    PRECONDITION,
    SUBJECT;

    public static void requireExplicit(
            PracticeDefinition before, PracticeDefinition after, @Nullable Set<DefinitionChange> changes) {
        Set<DefinitionChange> declared = changes == null ? Set.of() : changes;
        if (!Objects.equals(before.precondition(), after.precondition()) && !declared.contains(PRECONDITION)) {
            throw new IllegalArgumentException(
                    "Confirm that you want to change when this practice applies, then save again.");
        }
        if (before.subject() != after.subject() && !declared.contains(SUBJECT)) {
            throw new IllegalArgumentException(
                    "Confirm that you want to change whose work this practice reviews, then save again.");
        }
    }
}
