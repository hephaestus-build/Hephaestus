package de.tum.cit.aet.hephaestus.evidence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SourceCatalog {
    private final SourceContractVersion version;
    private final List<SourceContract> sources;
    private final Map<SourceKind, SourceContract> index;

    @JsonCreator
    public SourceCatalog(
            @JsonProperty("version") SourceContractVersion version,
            @JsonProperty("sources") List<SourceContract> sources) {
        Objects.requireNonNull(version, "version");
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (this.sources.isEmpty()) {
            throw new IllegalArgumentException("Source catalog must contain sources");
        }
        this.version = version;
        this.index = Map.copyOf(indexSources(this.sources));
    }

    @JsonProperty("version")
    public SourceContractVersion version() {
        return version;
    }

    @JsonProperty("sources")
    public List<SourceContract> sources() {
        return sources;
    }

    public Optional<SourceContract> source(SourceKind kind) {
        return Optional.ofNullable(index.get(kind));
    }

    /**
     * Every source that declares it applies to this item kind. Empty means nothing has declared itself
     * usable for the kind, which callers treat as an unknown kind rather than a review with no evidence.
     */
    public Set<SourceKind> sourcesFor(String itemKind) {
        Objects.requireNonNull(itemKind, "itemKind");
        Set<SourceKind> kinds = new LinkedHashSet<>();
        for (SourceContract source : sources) {
            if (source.appliesTo(itemKind)) kinds.add(source.kind());
        }
        return Set.copyOf(kinds);
    }

    private static Map<SourceKind, SourceContract> indexSources(List<SourceContract> sources) {
        Map<SourceKind, SourceContract> index = new HashMap<>();
        for (SourceContract source : sources) {
            if (index.put(source.kind(), source) != null) {
                throw new IllegalArgumentException("Duplicate source kind: " + source.kind());
            }
        }
        return index;
    }
}
