package de.tum.cit.aet.hephaestus.practices.observation.trend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WorkResolutionTest {

    private static final Instant PREPARED_AT = Instant.parse("2026-05-01T12:00:00Z");

    @Test
    void shouldResolveOnTheThirdCleanPieceOfWorkAndKeepItResolved() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        WorkResolution resolution = WorkResolution.of(
                List.of(
                        clean(11L, first, "2026-05-02T09:00:00Z"),
                        clean(12L, second, "2026-05-03T09:00:00Z"),
                        clean(13L, third, "2026-05-04T09:00:00Z"),
                        problem(14L, UUID.randomUUID(), "2026-05-05T09:00:00Z")),
                PREPARED_AT);

        assertThat(resolution.resolvedAt()).isEqualTo(Instant.parse("2026-05-04T09:00:00Z"));
        assertThat(resolution.cleanWork())
                .extracting(WorkResolution.Work::id, WorkResolution.Work::jobId)
                .containsExactly(tuple(11L, first), tuple(12L, second), tuple(13L, third));
    }

    @Test
    void shouldCountAPieceOfWorkReviewedTwiceOnce() {
        // The same pull request re-reviewed is one occasion, read off its latest run: two pieces of work, not three.
        WorkResolution resolution = WorkResolution.of(
                List.of(
                        clean(11L, UUID.randomUUID(), "2026-05-02T09:00:00Z"),
                        clean(11L, UUID.randomUUID(), "2026-05-03T09:00:00Z"),
                        clean(12L, UUID.randomUUID(), "2026-05-04T09:00:00Z")),
                PREPARED_AT);

        assertThat(resolution.cleanWork()).extracting(WorkResolution.Work::id).containsExactly(11L, 12L);
        assertThat(resolution.resolvedAt()).isNull();
    }

    @Test
    void shouldStartOverAtAProblemAndSkipWorkWithoutAVerdict() {
        WorkResolution resolution = WorkResolution.of(
                List.of(
                        clean(11L, UUID.randomUUID(), "2026-05-02T09:00:00Z"),
                        problem(12L, UUID.randomUUID(), "2026-05-03T09:00:00Z"),
                        clean(13L, UUID.randomUUID(), "2026-05-04T09:00:00Z"),
                        noVerdict(14L, UUID.randomUUID(), "2026-05-05T09:00:00Z"),
                        clean(15L, UUID.randomUUID(), "2026-05-06T09:00:00Z")),
                PREPARED_AT);

        assertThat(resolution.cleanWork()).extracting(WorkResolution.Work::id).containsExactly(13L, 15L);
        assertThat(resolution.resolvedAt()).isNull();
    }

    @Test
    void shouldIgnoreWorkReviewedAtOrBeforePreparation() {
        WorkResolution resolution = WorkResolution.of(
                List.of(
                        clean(8L, UUID.randomUUID(), "2026-04-30T09:00:00Z"),
                        clean(9L, UUID.randomUUID(), PREPARED_AT.toString()),
                        clean(11L, UUID.randomUUID(), "2026-05-02T09:00:00Z")),
                PREPARED_AT);

        assertThat(resolution.cleanWork()).extracting(WorkResolution.Work::id).containsExactly(11L);
        assertThat(resolution.resolvedAt()).isNull();
    }

    private static Observation clean(long artifactId, UUID jobId, String observedAt) {
        return observation(artifactId, jobId, observedAt, Presence.PRESENT, Assessment.GOOD);
    }

    private static Observation problem(long artifactId, UUID jobId, String observedAt) {
        return observation(artifactId, jobId, observedAt, Presence.ABSENT, Assessment.BAD);
    }

    private static Observation noVerdict(long artifactId, UUID jobId, String observedAt) {
        return observation(artifactId, jobId, observedAt, Presence.NOT_APPLICABLE, null);
    }

    private static Observation observation(
            long artifactId,
            UUID jobId,
            String observedAt,
            Presence presence,
            @org.jspecify.annotations.Nullable Assessment assessment) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(jobId)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(artifactId)
                .presence(presence)
                .assessment(assessment)
                .observedAt(Instant.parse(observedAt))
                .build();
    }
}
