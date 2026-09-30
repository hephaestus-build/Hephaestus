package de.tum.cit.aet.hephaestus.evidence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Stable names for countable collections used by mechanical practice preconditions.
 * Storage paths remain internal to the evaluator.
 */
public enum SubjectEvidenceCollection {
    SCM_REVIEW_THREADS("scm.review-threads", new SourceKind("scm.review-threads")),

    SCM_INLINE_REVIEW_COMMENTS("scm.inline-review-comments", new SourceKind("scm.pull-request.comments")),

    SCM_GENERAL_REVIEW_COMMENTS("scm.general-review-comments", new SourceKind("scm.general-review-comments"));

    private final String id;
    private final SourceKind sourceKind;

    SubjectEvidenceCollection(String id, SourceKind sourceKind) {
        this.id = id;
        this.sourceKind = sourceKind;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public SourceKind sourceKind() {
        return sourceKind;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SubjectEvidenceCollection of(String id) {
        Objects.requireNonNull(id, "id");
        return Arrays.stream(values())
                .filter(collection -> collection.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("“Evidence present” (evidenceHasItems) counts one of "
                        + Arrays.stream(values())
                                .map(SubjectEvidenceCollection::id)
                                .collect(Collectors.joining(", "))
                        + "; " + id + " is not one of them."));
    }

    @Override
    public String toString() {
        return id;
    }
}
