package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SourceFreshnessTest {

    private final List<List<UUID>> batches = new ArrayList<>();

    private final SourceFreshness freshness = new SourceFreshness(rows -> {
        batches.add(rows.stream().map(ResultFacts::id).toList());
        return current(rows);
    });

    @Test
    void shouldReadNothingUntilAResultIsAskedAbout() {
        facts();

        assertThat(batches).isEmpty();
    }

    @Test
    void shouldReadPrefetchedResultsInOneBatchAndNeverAgain() {
        var first = facts();
        var second = facts();

        freshness.prefetchRows(List.of(first, second, first));
        freshness.of(first);
        freshness.of(second);

        assertThat(batches).containsExactly(List.of(first.id(), second.id()));
    }

    @Test
    void shouldReadAResultThatWasNotPrefetchedWhenAskedAbout() {
        var prefetched = facts();
        var later = facts();

        freshness.prefetchRows(List.of(prefetched));
        freshness.of(later);

        assertThat(batches).containsExactly(List.of(prefetched.id()), List.of(later.id()));
    }

    @Test
    void shouldRefuseAReadThatLeavesOutTheResultItWasGiven() {
        var dropping = new SourceFreshness(rows -> Map.of());

        assertThatThrownBy(() -> dropping.of(facts())).isInstanceOf(NullPointerException.class);
    }

    private static Map<UUID, Freshness> current(Collection<ResultFacts> rows) {
        return rows.stream().collect(Collectors.toMap(ResultFacts::id, row -> Freshness.current(null)));
    }

    private static ResultFacts facts() {
        return Result.builder().id(UUID.randomUUID()).workspaceId(1).build().facts();
    }
}
