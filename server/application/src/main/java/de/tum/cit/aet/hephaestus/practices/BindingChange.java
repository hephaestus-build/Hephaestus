package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** Explicit intent for a change to the gate or the person a practice judges. */
public enum BindingChange {
    APPLIES_WHEN,
    SUBJECT;

    public static void requireExplicit(
            List<PracticeBinding> before, List<PracticeBinding> after, @Nullable Set<BindingChange> changes) {
        Set<BindingChange> declared = changes == null ? Set.of() : changes;
        Set<PracticeSubject> oldGates = before.stream()
                .map(PracticeBinding::appliesWhen)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<PracticeSubject> newGates = after.stream()
                .map(PracticeBinding::appliesWhen)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<ActorRole> oldSubjects =
                before.stream().map(PracticeBinding::subject).collect(Collectors.toSet());
        Set<ActorRole> newSubjects =
                after.stream().map(PracticeBinding::subject).collect(Collectors.toSet());
        boolean gateChanged = !oldGates.equals(newGates);
        boolean subjectChanged = !oldSubjects.equals(newSubjects);
        if (before.size() == after.size()) {
            for (int index = 0; index < before.size(); index++) {
                gateChanged |= !Objects.equals(
                        before.get(index).appliesWhen(), after.get(index).appliesWhen());
                subjectChanged |=
                        before.get(index).subject() != after.get(index).subject();
            }
        }
        if (gateChanged && !declared.contains(APPLIES_WHEN)) {
            throw new IllegalArgumentException("Changing appliesWhen requires bindingChanges: APPLIES_WHEN");
        }
        if (subjectChanged && !declared.contains(SUBJECT)) {
            throw new IllegalArgumentException("Changing subject requires bindingChanges: SUBJECT");
        }
    }
}
