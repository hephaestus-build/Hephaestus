package de.tum.cit.aet.hephaestus.evidence;

import java.util.Objects;

/**
 * Auditable result for one precondition clause.
 *
 * @param aspect   which staged fact the clause read
 * @param readFrom the source that answered it
 * @param result   what looking established
 */
public record PracticePreconditionClauseCheck(
        PracticePreconditionAspect aspect, SourceKind readFrom, PracticePreconditionResult result) {
    public PracticePreconditionClauseCheck {
        Objects.requireNonNull(aspect, "aspect");
        Objects.requireNonNull(readFrom, "readFrom");
        Objects.requireNonNull(result, "result");
    }
}
