package de.tum.cit.aet.hephaestus.practices.observation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

/**
 * One page of review runs after the visibility gate. The runs a query returns are candidates, not the page:
 * a run whose every observation about the reader is withheld is dropped, so candidates are read in slices
 * of at least fifty until the requested page and one row beyond it are filled or the candidates run out.
 * The developer's own list and a practice group's history page this way, so the rule is stated once.
 *
 * @param content the runs on the requested page
 * @param hasNext whether a further visible run follows them
 */
public record VisibleRunPage<T>(List<T> content, boolean hasNext) {

    private static final int CANDIDATE_SLICE = 50;

    /**
     * @param candidates the runs the query returns for one slice, newest first
     * @param visible the candidates the gate lets through, in their order, as the page presents them
     */
    public static <R, T> VisibleRunPage<T> collect(
            Pageable pageable, Function<Pageable, Slice<R>> candidates, Function<List<R>, List<T>> visible) {
        int first = Math.multiplyExact(pageable.getPageNumber(), pageable.getPageSize());
        int required = Math.addExact(first, pageable.getPageSize() + 1);
        List<T> visibleRuns = new ArrayList<>(required);
        int candidatePage = 0;
        boolean moreCandidates;
        do {
            Slice<R> slice = candidates.apply(
                    PageRequest.of(candidatePage++, Math.max(pageable.getPageSize(), CANDIDATE_SLICE)));
            visibleRuns.addAll(visible.apply(slice.getContent()));
            moreCandidates = slice.hasNext();
        } while (visibleRuns.size() < required && moreCandidates);

        int end = Math.min(first + pageable.getPageSize(), visibleRuns.size());
        List<T> content = first >= visibleRuns.size() ? List.of() : List.copyOf(visibleRuns.subList(first, end));
        return new VisibleRunPage<>(content, visibleRuns.size() > end);
    }
}
