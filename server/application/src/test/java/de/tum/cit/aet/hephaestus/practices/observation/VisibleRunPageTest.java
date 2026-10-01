package de.tum.cit.aet.hephaestus.practices.observation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.ReviewRunRow;
import de.tum.cit.aet.hephaestus.practices.observation.VisibleRunPage.VisibleRun;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

class VisibleRunPageTest extends BaseUnitTest {

    private record Run(UUID getJobId, Instant getReviewedAt) implements ReviewRunRow {}

    @Test
    void shouldSkipHiddenRunsWhenTheyStraddleAPageBoundary() {
        List<Run> runs = runs(10);
        Set<Integer> hidden = Set.of(3, 4, 5);

        assertThat(page(runs, hidden, PageRequest.of(0, 3))).isEqualTo(new Page(List.of(0, 1, 2), true));
        assertThat(page(runs, hidden, PageRequest.of(1, 3))).isEqualTo(new Page(List.of(6, 7, 8), true));
        assertThat(page(runs, hidden, PageRequest.of(2, 3))).isEqualTo(new Page(List.of(9), false));
    }

    @Test
    void shouldReadFurtherSlicesWhenThePageLiesBeyondTheFirstFiftyCandidates() {
        List<Run> runs = runs(120);
        Set<Integer> hidden =
                IntStream.range(0, 120).filter(i -> i % 2 == 1).boxed().collect(Collectors.toSet());

        assertThat(page(runs, hidden, PageRequest.of(3, 10)))
                .isEqualTo(
                        new Page(IntStream.range(30, 40).map(i -> i * 2).boxed().toList(), true));
    }

    @Test
    void shouldSayNoPageFollowsWhenOnlyHiddenCandidatesRemain() {
        List<Run> runs = runs(80);
        Set<Integer> hidden = IntStream.range(5, 80).boxed().collect(Collectors.toSet());

        assertThat(page(runs, hidden, PageRequest.of(0, 5))).isEqualTo(new Page(List.of(0, 1, 2, 3, 4), false));
    }

    private record Page(List<Integer> runs, boolean hasNext) {}

    private static Page page(List<Run> runs, Set<Integer> hidden, Pageable pageable) {
        Predicate<Run> visible = run -> !hidden.contains(runs.indexOf(run));
        List<UUID> ids = runs.stream().map(Run::getJobId).toList();
        VisibleRunPage page = VisibleRunPage.collect(
                pageable,
                candidates -> slice(runs, candidates),
                slice -> slice.stream()
                        .filter(visible)
                        .collect(Collectors.toMap(Run::getJobId, run -> List.of(new Observation()))));
        return new Page(
                page.content().stream()
                        .map(VisibleRun::reviewId)
                        .map(ids::indexOf)
                        .toList(),
                page.hasNext());
    }

    private static Slice<Run> slice(List<Run> runs, Pageable candidates) {
        int from = Math.min((int) candidates.getOffset(), runs.size());
        int to = Math.min(from + candidates.getPageSize(), runs.size());
        return new SliceImpl<>(runs.subList(from, to), candidates, to < runs.size());
    }

    private static List<Run> runs(int count) {
        Instant now = Instant.parse("2026-09-01T12:00:00Z");
        return IntStream.range(0, count)
                .mapToObj(i -> new Run(UUID.randomUUID(), now.minusSeconds(i)))
                .toList();
    }
}
