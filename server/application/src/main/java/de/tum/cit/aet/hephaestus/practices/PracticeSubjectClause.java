package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SubjectAspect;
import de.tum.cit.aet.hephaestus.evidence.SubjectEvidenceCollection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One mechanically decidable alternative in a practice precondition.
 *
 * <p>Exactly one field is set. Literal substring matching avoids executing administrator-supplied regular
 * expressions on untrusted diffs.
 *
 * <p>An author writes a clause as JSON, so a refusal names each field by the editor's label and the key they
 * typed together — “Diff contains” (diffContains) — which is the one convention for every message about
 * the gate.
 *
 * @param changedPathMatches glob patterns; the clause holds when the change touches a matching path.
 *                           {@code *} does not cross a {@code /} and {@code **} does
 * @param diffContains       literal strings; the clause holds when the diff contains one of them
 *                           anywhere — added, removed or context — so that removing the last test in a
 *                           file still counts as the subject being present
 * @param evidenceHasItems   a named collection; the clause holds when it has at least one entry
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One shape the subject a practice judges can take in a piece of work")
public record PracticeSubjectClause(
        @Schema(description = "Globs; holds when the change touches a matching path") @Nullable
        List<String> changedPathMatches,

        @Schema(description = "Literal strings; holds when the diff contains one of them") @Nullable
        List<String> diffContains,

        @Schema(description = "Named evidence collection; holds when it has at least one entry") @Nullable
        SubjectEvidenceCollection evidenceHasItems) {
    public static final SourceKind DIFF_SOURCE = new SourceKind("scm.pull-request.diff");

    static final int MAX_TERM_LENGTH = 200;

    static final int MAX_TERMS = 100;

    private static final String CHANGED_PATH_MATCHES = "“Changed path matches” (changedPathMatches)";

    private static final String DIFF_CONTAINS = "“Diff contains” (diffContains)";

    private static final String EVIDENCE_HAS_ITEMS = "“Evidence present” (evidenceHasItems)";

    @JsonCreator
    public PracticeSubjectClause(
            @JsonProperty("changedPathMatches") @Nullable List<String> changedPathMatches,
            @JsonProperty("diffContains") @Nullable List<String> diffContains,
            @JsonProperty("evidenceHasItems") @Nullable SubjectEvidenceCollection evidenceHasItems) {
        this.changedPathMatches = copyTerms(changedPathMatches, CHANGED_PATH_MATCHES);
        this.diffContains = copyTerms(diffContains, DIFF_CONTAINS);
        this.evidenceHasItems = evidenceHasItems;
        long declared = java.util.stream.Stream.of(this.changedPathMatches, this.diffContains, this.evidenceHasItems)
                .filter(Objects::nonNull)
                .count();
        if (declared != 1) {
            throw new IllegalArgumentException("Each gate clause states exactly one of " + CHANGED_PATH_MATCHES + ", "
                    + DIFF_CONTAINS + " or " + EVIDENCE_HAS_ITEMS + ".");
        }
    }

    public static PracticeSubjectClause changedPathMatches(List<String> globs) {
        return new PracticeSubjectClause(globs, null, null);
    }

    public static PracticeSubjectClause diffContains(List<String> literals) {
        return new PracticeSubjectClause(null, literals, null);
    }

    public static PracticeSubjectClause evidenceHasItems(SubjectEvidenceCollection collection) {
        return new PracticeSubjectClause(null, null, collection);
    }

    public SubjectAspect aspect() {
        if (changedPathMatches != null) return SubjectAspect.CHANGED_PATH;
        if (diffContains != null) return SubjectAspect.DIFF_TEXT;
        return SubjectAspect.EVIDENCE_ITEMS;
    }

    public SourceKind readsFrom() {
        return evidenceHasItems == null ? DIFF_SOURCE : evidenceHasItems.sourceKind();
    }

    public String describe() {
        if (changedPathMatches != null) return "changedPathMatches " + changedPathMatches;
        if (diffContains != null) return "diffContains " + diffContains;
        return "evidenceHasItems " + evidenceHasItems;
    }

    private static @Nullable List<String> copyTerms(@Nullable List<String> terms, String field) {
        if (terms == null) {
            return null;
        }
        if (terms.isEmpty()) {
            throw new IllegalArgumentException(field + " needs at least one entry. Add one, or leave it out.");
        }
        if (terms.size() > MAX_TERMS) {
            throw new IllegalArgumentException(field + " may list at most " + MAX_TERMS + " entries.");
        }
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                throw new IllegalArgumentException(field + " has an empty entry. Remove it.");
            }
            if (term.length() > MAX_TERM_LENGTH) {
                throw new IllegalArgumentException(
                        field + " entries may be at most " + MAX_TERM_LENGTH + " characters long.");
            }
        }
        return List.copyOf(terms);
    }
}
