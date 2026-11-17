package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The source freshness of one read's views, computed only for the views that a caller asks about. A freshness read
 * reads every result's sources, so a page that quotes a few results must not read the sources of every result in its
 * look-back. A caller that will ask about several views prefetches them, so their sources are read in one batch.
 */
public final class SourceFreshness {

    private final Function<Collection<ResultFacts>, Map<UUID, Freshness>> read;
    private final Map<UUID, Freshness> known = new HashMap<>();

    SourceFreshness(Function<Collection<ResultFacts>, Map<UUID, Freshness>> read) {
        this.read = read;
    }

    /** A freshness that is already known, for a view that was not read from the database. */
    public static SourceFreshness fixed(Freshness freshness) {
        return new SourceFreshness(rows -> {
            var all = new HashMap<UUID, Freshness>();
            rows.forEach(row -> all.put(row.id(), freshness));
            return all;
        });
    }

    /** Reads, in one batch for each read that produced them, the freshness of the views that are not yet known. */
    public static void prefetch(Collection<ResultView> views) {
        views.stream()
                .collect(Collectors.groupingBy(
                        ResultView::freshness,
                        IdentityHashMap::new,
                        Collectors.mapping(ResultView::result, Collectors.toList())))
                .forEach(SourceFreshness::prefetchRows);
    }

    void prefetchRows(List<ResultFacts> rows) {
        var missing = rows.stream()
                .filter(row -> !known.containsKey(row.id()))
                .distinct()
                .toList();
        if (!missing.isEmpty()) known.putAll(read.apply(missing));
    }

    Freshness of(ResultFacts result) {
        if (!known.containsKey(result.id())) known.putAll(read.apply(List.of(result)));
        return Objects.requireNonNull(known.get(result.id()), "A freshness read left out a result it was given");
    }
}
