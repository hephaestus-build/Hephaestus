package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CapturedApplicability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.AppliesWhen;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Records adapter decisions; only complete captured evidence can prove absence. */
@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class AppliesWhenEvaluator {
    private final List<CapturedApplicability> evaluators;

    public Set<SourceKind> sources(ItemKind kind, AppliesWhen conditions) {
        return clauses(kind, conditions, List.of(), Map.of()).stream()
                .map(AppliesWhenCheck.Clause::readFrom)
                .collect(Collectors.toSet());
    }

    public @Nullable AppliesWhenCheck evaluate(
            @Nullable AppliesWhen conditions, JobFolderIndex manifest, Map<String, byte[]> files) {
        if (conditions == null) return null;
        var clauses = clauses(new ItemKind(manifest.itemKind()), conditions, manifest.sources(), files);
        if (clauses.isEmpty()) return null;
        boolean absent = clauses.stream().allMatch(clause -> clause.result() == AppliesWhenCheck.Result.NOT_FOUND);
        return new AppliesWhenCheck(absent, conditions.otherwise(), clauses);
    }

    private List<AppliesWhenCheck.Clause> clauses(
            ItemKind kind, AppliesWhen conditions, List<SourceCapture> captures, Map<String, byte[]> files) {
        var declared = conditions.anyOf().stream()
                .map(c -> new CapturedApplicability.Condition(
                        c.changedPath(), c.diffContains(), c.hasItems(), Boolean.TRUE.equals(c.merged())))
                .toList();
        var decisions = evaluators.stream()
                .map(e -> e.evaluate(kind, declared, captures, files))
                .filter(clauses -> !clauses.isEmpty())
                .toList();
        if (decisions.size() > 1) throw new IllegalStateException("The item has multiple applicability evaluators");
        return decisions.isEmpty() ? List.of() : decisions.getFirst();
    }
}
