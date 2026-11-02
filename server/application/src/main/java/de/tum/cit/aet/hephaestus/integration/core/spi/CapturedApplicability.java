package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Interprets an item's conditions using only the evidence captured for this attempt. */
public interface CapturedApplicability {
    /**
     * Returns one decision per clause, including its owning source, or an empty list for unsupported
     * item kinds. Before capture, empty evidence yields UNDECIDABLE decisions whose sources can be requested.
     */
    List<AppliesWhenCheck.Clause> evaluate(
            ItemKind kind, List<Condition> clauses, List<SourceCapture> captures, Map<String, byte[]> files);

    record Condition(
            @Nullable List<String> changedPath,
            @Nullable List<String> diffContains,
            @Nullable String hasItems,
            boolean merged) {
        public Condition {
            if ((changedPath == null ? 0 : 1)
                            + (diffContains == null ? 0 : 1)
                            + (hasItems == null ? 0 : 1)
                            + (merged ? 1 : 0)
                    != 1) throw new IllegalArgumentException("A condition must have exactly one aspect");
            if (changedPath != null) changedPath = List.copyOf(changedPath);
            if (diffContains != null) diffContains = List.copyOf(diffContains);
        }
    }
}
