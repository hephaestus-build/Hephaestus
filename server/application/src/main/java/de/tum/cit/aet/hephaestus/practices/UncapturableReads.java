package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.evidence.SourceCatalog;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The declared reads that the current source catalog cannot capture for an item kind: a pinned contract that is no
 * longer the current one, or a source the catalog does not offer for the kind. An optional read counts, because
 * dropping it would review the practice with less than its pinned definition.
 */
public record UncapturableReads(String contract, String currentContract, List<PracticeReadSource> sources) {
    public UncapturableReads {
        sources = List.copyOf(sources);
    }

    /** Returns null when the catalog captures every declared read for the item kind. */
    public static @Nullable UncapturableReads of(SourceCatalog current, PracticeReads reads, String itemKind) {
        var sources = reads.sources().stream()
                .filter(read -> current.source(read.source())
                        .filter(contract -> contract.appliesTo(itemKind))
                        .isEmpty())
                .toList();
        String currentContract = current.version().value();
        if (reads.contract().equals(currentContract) && sources.isEmpty()) return null;
        return new UncapturableReads(reads.contract(), currentContract, sources);
    }
}
