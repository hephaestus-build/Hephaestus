package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Binds emitted paths and capture facts to contract source kinds. */
public interface EvidenceSource extends ContentSource {
    Set<SourceKind> sourceKinds();

    SourceKind sourceKindFor(String path);

    default void contributeSelected(ContextRequest request, Set<SourceKind> selectedKinds, Map<String, byte[]> files) {
        if (sourceKinds().stream().anyMatch(selectedKinds::contains)) {
            contribute(request, files);
        }
    }

    /** Collect atomically so a failed provider cannot leak a partial write into another source. */
    default EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        contributeSelected(request, selectedKinds, files);
        Set<SourceKind> capturedKinds = new HashSet<>(sourceKinds());
        capturedKinds.retainAll(selectedKinds);
        Map<SourceKind, SourceContentState> contentStates = capturedKinds.size() == 1
                ? Map.of(
                        capturedKinds.iterator().next(),
                        files.isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY)
                : Map.of();
        return new EvidenceContribution(files, Map.of(), Map.of(), Map.of(), Map.of(), contentStates);
    }
}
