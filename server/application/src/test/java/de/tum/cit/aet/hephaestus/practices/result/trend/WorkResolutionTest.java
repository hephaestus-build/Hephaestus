package de.tum.cit.aet.hephaestus.practices.result.trend;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.ended;
import static de.tum.cit.aet.hephaestus.practices.result.trend.TrendResults.clean;
import static de.tum.cit.aet.hephaestus.practices.result.trend.TrendResults.noVerdict;
import static de.tum.cit.aet.hephaestus.practices.result.trend.TrendResults.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.practices.result.ResultView;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WorkResolutionTest {

    private static final Instant PREPARED_AT = Instant.parse("2026-05-01T12:00:00Z");

    @Test
    void shouldStayResolvedWhenAProblemFollowsTheThirdCleanPieceOfWork() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        WorkResolution resolution = resolve(List.of(
                clean(11L, first, "2026-05-02T09:00:00Z"),
                clean(12L, second, "2026-05-03T09:00:00Z"),
                clean(13L, third, "2026-05-04T09:00:00Z"),
                problem(14L, UUID.randomUUID(), "2026-05-05T09:00:00Z")));

        assertThat(resolution.resolvedAt()).isEqualTo(Instant.parse("2026-05-04T09:00:00Z"));
        assertThat(resolution.cleanWork())
                .extracting(WorkResolution.Work::itemId, WorkResolution.Work::reviewId)
                .containsExactly(tuple(11L, first), tuple(12L, second), tuple(13L, third));
    }

    @Test
    void shouldCountAPieceOfWorkOnceWhenItWasReviewedTwice() {
        UUID firstRun = UUID.randomUUID();
        UUID secondRun = UUID.randomUUID();
        UUID otherWork = UUID.randomUUID();
        var prior = clean(11L, firstRun, "2026-05-02T09:00:00Z");
        prior = ended(prior, ResultEndReason.REPLACED, Instant.parse("2026-05-03T09:00:00Z"));
        WorkResolution resolution = resolve(List.of(
                prior, clean(11L, secondRun, "2026-05-03T09:00:00Z"), clean(12L, otherWork, "2026-05-04T09:00:00Z")));

        assertThat(resolution.cleanWork())
                .extracting(WorkResolution.Work::itemId, WorkResolution.Work::reviewId, WorkResolution.Work::at)
                .containsExactly(
                        tuple(11L, secondRun, Instant.parse("2026-05-03T09:00:00Z")),
                        tuple(12L, otherWork, Instant.parse("2026-05-04T09:00:00Z")));
        assertThat(resolution.resolvedAt()).isNull();
    }

    @Test
    void shouldStartOverAtAProblemAndSkipWorkWhenItHasNoVerdict() {
        WorkResolution resolution = resolve(List.of(
                clean(11L, UUID.randomUUID(), "2026-05-02T09:00:00Z"),
                problem(12L, UUID.randomUUID(), "2026-05-03T09:00:00Z"),
                clean(13L, UUID.randomUUID(), "2026-05-04T09:00:00Z"),
                noVerdict(14L, UUID.randomUUID(), "2026-05-05T09:00:00Z"),
                clean(15L, UUID.randomUUID(), "2026-05-06T09:00:00Z")));

        assertThat(resolution.cleanWork())
                .extracting(WorkResolution.Work::itemId)
                .containsExactly(13L, 15L);
        assertThat(resolution.resolvedAt()).isNull();
    }

    @Test
    void shouldIgnoreWorkWhenItWasReviewedAtOrBeforePreparation() {
        WorkResolution resolution = resolve(List.of(
                clean(8L, UUID.randomUUID(), "2026-04-30T09:00:00Z"),
                clean(9L, UUID.randomUUID(), PREPARED_AT.toString()),
                clean(11L, UUID.randomUUID(), "2026-05-02T09:00:00Z")));

        assertThat(resolution.cleanWork())
                .extracting(WorkResolution.Work::itemId)
                .containsExactly(11L);
        assertThat(resolution.resolvedAt()).isNull();
    }

    private static WorkResolution resolve(List<ResultView> practiceResults) {
        return WorkResolution.Opportunities.of(practiceResults, Instant.EPOCH).resolve(PREPARED_AT);
    }
}
