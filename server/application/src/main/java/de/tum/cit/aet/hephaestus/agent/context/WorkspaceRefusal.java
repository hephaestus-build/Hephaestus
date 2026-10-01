package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import java.util.Objects;

/** A refused folder target contains no source content; the index retains only its typed reason. */
public record WorkspaceRefusal(Target target, String id, SourceAbsenceReason reason) {
    public WorkspaceRefusal {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reason, "reason");
        if (id.isBlank()) throw new IllegalArgumentException("Refusal target must not be blank");
    }

    public enum Target {
        AREA,
        REPOSITORY,
        SOURCE,
        RECORD
    }
}
