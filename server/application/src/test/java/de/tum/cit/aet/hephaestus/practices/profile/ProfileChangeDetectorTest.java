package de.tum.cit.aet.hephaestus.practices.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.TrendDirection;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution.Work;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileChangeDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProfileChangeDetectorTest {

    private static final Instant PREVIOUS_RUN = Instant.parse("2026-09-07T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final OverviewWindow WINDOW = new OverviewWindow(PREVIOUS_RUN, NOW);
    private static final Work PR_21 = work(21L, "2026-09-08T10:00:00Z");
    private static final Work PR_22 = work(22L, "2026-09-09T14:10:00Z");

    @Test
    @DisplayName("a standing that reads differently as of the two edges moved, with the window's work as evidence")
    void shouldDetectAStandingMoveWhenTheLabelsDiffer() {
        List<ProfileChangeDTO> changes = ProfileChangeDetector.detect(
                WINDOW,
                List.of(practice(
                        "reviewable-diff-size", PracticeStandingDTO.Standing.STRENGTH, TrendDirection.UNCERTAIN)),
                List.of(practice(
                        "reviewable-diff-size", PracticeStandingDTO.Standing.DEVELOPING, TrendDirection.UNCERTAIN)),
                List.of(),
                List.of(),
                Map.of("reviewable-diff-size", List.of(PR_22, PR_21)),
                Map.of("reviewable-diff-size", Instant.parse("2026-08-01T00:00:00Z")),
                Map.of());

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ProfileChangeDTO.Type.STANDING_MOVED);
            assertThat(change.practiceSlug()).isEqualTo("reviewable-diff-size");
            assertThat(change.groupSlug()).isEqualTo("review-ready-work");
            assertThat(change.from()).isEqualTo("STRENGTH");
            assertThat(change.to()).isEqualTo("DEVELOPING");
            assertThat(change.at()).isEqualTo(PR_22.at());
            // Named without a run to read the name off: the kind alone, never a number that was not there.
            assertThat(change.evidence())
                    .extracting(ReviewedWorkRefDTO::id, ReviewedWorkRefDTO::label)
                    .containsExactly(tuple("22", "Pull request"), tuple("21", "Pull request"));
        });
    }

    @Test
    @DisplayName("a trend that points elsewhere than before turned; one that had no direction before did not")
    void shouldDetectATrendTurnWhenBothEdgesHaveADirection() {
        List<ProfileChangeDTO> turned = ProfileChangeDetector.detect(
                WINDOW,
                List.of(practice(
                        "describe-what-and-why", PracticeStandingDTO.Standing.MIXED, TrendDirection.DECLINING)),
                List.of(practice(
                        "describe-what-and-why", PracticeStandingDTO.Standing.MIXED, TrendDirection.IMPROVING)),
                List.of(),
                List.of(),
                Map.of("describe-what-and-why", List.of(PR_22)),
                Map.of(),
                Map.of());
        List<ProfileChangeDTO> firstDirection = ProfileChangeDetector.detect(
                WINDOW,
                List.of(practice("describe-what-and-why", PracticeStandingDTO.Standing.MIXED, null)),
                List.of(practice(
                        "describe-what-and-why", PracticeStandingDTO.Standing.MIXED, TrendDirection.IMPROVING)),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of());

        assertThat(turned).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ProfileChangeDTO.Type.TREND_TURNED);
            assertThat(change.from()).isEqualTo("DECLINING");
            assertThat(change.to()).isEqualTo("IMPROVING");
        });
        assertThat(firstDirection).isEmpty();
    }

    @Test
    @DisplayName(
            "a practice observed for the first time inside the window is first observed, dated by that observation")
    void shouldDetectAFirstObservationWhenItFallsInsideTheWindow() {
        Instant first = Instant.parse("2026-09-09T14:10:00Z");
        List<ProfileChangeDTO> changes = ProfileChangeDetector.detect(
                WINDOW,
                List.of(practice("asks-answerable-questions", PracticeStandingDTO.Standing.NOT_OBSERVED, null)),
                List.of(practice("asks-answerable-questions", PracticeStandingDTO.Standing.NO_OPPORTUNITY, null)),
                List.of(),
                List.of(),
                Map.of("asks-answerable-questions", List.of(PR_22)),
                Map.of("asks-answerable-questions", first, "describe-what-and-why", PREVIOUS_RUN),
                Map.of());

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ProfileChangeDTO.Type.FIRST_OBSERVED);
            assertThat(change.at()).isEqualTo(first);
            assertThat(change.evidence()).extracting(ReviewedWorkRefDTO::id).containsExactly("22");
        });
    }

    @Test
    @DisplayName("a practice with a new verdict and a first observation keeps both: they are different types")
    void shouldKeepOneChangePerTypeWhenAPracticeChangedTwice() {
        List<ProfileChangeDTO> changes = ProfileChangeDetector.detect(
                WINDOW,
                List.of(),
                List.of(practice("asks-answerable-questions", PracticeStandingDTO.Standing.DEVELOPING, null)),
                List.of(),
                List.of(),
                Map.of("asks-answerable-questions", List.of(PR_22)),
                Map.of("asks-answerable-questions", PR_22.at()),
                Map.of());

        assertThat(changes)
                .extracting(ProfileChangeDTO::type)
                .containsExactlyInAnyOrder(ProfileChangeDTO.Type.STANDING_MOVED, ProfileChangeDTO.Type.FIRST_OBSERVED);
        assertThat(changes)
                .filteredOn(change -> change.type() == ProfileChangeDTO.Type.STANDING_MOVED)
                .singleElement()
                .satisfies(change -> assertThat(change.from()).isEqualTo("NOT_OBSERVED"));
    }

    @Test
    @DisplayName("moving between the two silences is not a change a developer can act on")
    void shouldIgnoreAMoveWhenBothEdgesAreSilences() {
        List<ProfileChangeDTO> changes = ProfileChangeDetector.detect(
                WINDOW,
                List.of(practice("keeps-the-test-suite-honest", PracticeStandingDTO.Standing.NOT_OBSERVED, null)),
                List.of(practice("keeps-the-test-suite-honest", PracticeStandingDTO.Standing.NO_OPPORTUNITY, null)),
                List.of(group(PracticeGroupStandingDTO.Standing.NOT_OBSERVED)),
                List.of(group(PracticeGroupStandingDTO.Standing.NO_OPPORTUNITY)),
                Map.of(),
                Map.of(),
                Map.of());

        assertThat(changes).isEmpty();
    }

    @Test
    @DisplayName("a group whose standing moved carries the work of every practice in it, once each, newest first")
    void shouldDetectAGroupMoveWithTheGroupsWorkWhenTheGroupLabelDiffers() {
        List<ProfileChangeDTO> changes = ProfileChangeDetector.detect(
                WINDOW,
                List.of(
                        practice("scope-one-reviewable-change", PracticeStandingDTO.Standing.MIXED, null),
                        practice("describe-what-and-why", PracticeStandingDTO.Standing.MIXED, null)),
                List.of(
                        practice("scope-one-reviewable-change", PracticeStandingDTO.Standing.MIXED, null),
                        practice("describe-what-and-why", PracticeStandingDTO.Standing.MIXED, null)),
                List.of(group(PracticeGroupStandingDTO.Standing.MIXED)),
                List.of(group(PracticeGroupStandingDTO.Standing.DEVELOPING)),
                Map.of(
                        "scope-one-reviewable-change", List.of(PR_22, PR_21),
                        "describe-what-and-why", List.of(work(22L, "2026-09-09T14:00:00Z"))),
                Map.of(),
                Map.of());

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ProfileChangeDTO.Type.GROUP_MOVED);
            assertThat(change.practiceSlug()).isNull();
            assertThat(change.groupSlug()).isEqualTo("review-ready-work");
            assertThat(change.groupName()).isEqualTo("Packaging work for review");
            assertThat(change.from()).isEqualTo("MIXED");
            assertThat(change.to()).isEqualTo("DEVELOPING");
            assertThat(change.at()).isEqualTo(PR_22.at());
            assertThat(change.evidence()).extracting(ReviewedWorkRefDTO::id).containsExactly("22", "21");
        });
    }

    private static Work work(long id, String at) {
        return new Work(ArtifactKinds.PULL_REQUEST, id, UUID.randomUUID(), Instant.parse(at));
    }

    private static PracticeStandingDTO practice(
            String slug, PracticeStandingDTO.Standing standing, @Nullable TrendDirection direction) {
        return new PracticeStandingDTO(
                slug,
                "Practice " + slug,
                "review-ready-work",
                "Packaging work for review",
                null,
                null,
                standing,
                List.of(),
                List.of(),
                direction,
                null);
    }

    private static PracticeGroupStandingDTO group(PracticeGroupStandingDTO.Standing standing) {
        return new PracticeGroupStandingDTO(
                "review-ready-work",
                "Packaging work for review",
                standing,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of());
    }
}
