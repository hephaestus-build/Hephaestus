package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWithholdings;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The stop reason of a review whose run ended with no reason of its own, from its withheld memberships' reasons. */
@Tag("unit")
class ReviewUnmeasuredReasonTest {

    /** Every reason that a run or admission writes on a membership for a fault, and the legacy history reasons. */
    static Stream<String> failures() {
        return Stream.of(
                        Arrays.stream(ReviewStopReason.values())
                                .map(Enum::name)
                                .filter(reason -> !ReviewWithholdings.DELIBERATE.contains(reason)),
                        Arrays.stream(CandidateRefusal.values()).map(CandidateRefusal::wireValue),
                        Stream.of(
                                "COPY_AUTHORITY_UNAVAILABLE",
                                "inadmissible_results",
                                "LEGACY_AMBIGUOUS_RESULTS",
                                "LEGACY_REUSE_UNBOUND"))
                .flatMap(reasons -> reasons);
    }

    @Test
    void shouldEndAsReplacedWhenTheWorkChangedForEveryWithheldPractice() {
        assertThat(ReviewServiceImpl.unmeasuredReason(Set.of(ReviewStopReason.REPLACED.name())))
                .isEqualTo(ReviewStopReason.REPLACED);
    }

    @Test
    void shouldEndAsSourcesMovedWhenTheSourcesMovedForEveryWithheldPracticeAndNotEveryOneWasReplaced() {
        assertThat(ReviewServiceImpl.unmeasuredReason(Set.of(ReviewStopReason.SOURCES_MOVED.name())))
                .isEqualTo(ReviewStopReason.SOURCES_MOVED);
        assertThat(ReviewServiceImpl.unmeasuredReason(
                        Set.of(ReviewStopReason.REPLACED.name(), ReviewStopReason.SOURCES_MOVED.name())))
                .isEqualTo(ReviewStopReason.SOURCES_MOVED);
    }

    /** A practice that never ran is owed, not withheld on purpose: its review's execution did not finish. */
    @Test
    void shouldEndAsExecutionIncompleteWhenAPracticeNeverRan() {
        assertThat(ReviewServiceImpl.unmeasuredReason(Set.of(ReviewStopReason.NOT_RUN.name())))
                .isEqualTo(ReviewStopReason.EXECUTION_INCOMPLETE);
        assertThat(ReviewWithholdings.CARRIED).contains(ReviewStopReason.NOT_RUN.name());
    }

    @Test
    void shouldEndAsStaleRolloutWhenTheReviewSettingsChangedForEveryWithheldPractice() {
        assertThat(ReviewServiceImpl.unmeasuredReason(Set.of(ReviewWithholdings.STALE_ROLLOUT_REVISION)))
                .isEqualTo(ReviewStopReason.STALE_ROLLOUT_REVISION);
    }

    @Test
    void shouldEndAsNotReadyForEachOtherReasonThatWithholdsAPracticeOnPurpose() {
        assertThat(ReviewWithholdings.DELIBERATE)
                .filteredOn(reason -> !reason.equals(ReviewStopReason.REPLACED.name())
                        && !reason.equals(ReviewStopReason.SOURCES_MOVED.name())
                        && !reason.equals(ReviewWithholdings.STALE_ROLLOUT_REVISION))
                .allSatisfy(reason -> assertThat(ReviewServiceImpl.unmeasuredReason(Set.of(reason)))
                        .as(reason)
                        .isEqualTo(ReviewStopReason.READINESS_REFUSED));
    }

    @Test
    void shouldEndAsNotReadyForAMixOfASelectionReasonACaptureRefusalAndAReplacement() {
        assertThat(ReviewServiceImpl.unmeasuredReason(
                        Set.of(ReviewWithholdings.ROLE_UNAVAILABLE, ReviewStopReason.READINESS_REFUSED.name())))
                .isEqualTo(ReviewStopReason.READINESS_REFUSED);
        assertThat(ReviewServiceImpl.unmeasuredReason(
                        Set.of(ReviewStopReason.REPLACED.name(), ReviewWithholdings.SOURCE_CONTRACT_UNSUPPORTED)))
                .isEqualTo(ReviewStopReason.READINESS_REFUSED);
        assertThat(ReviewServiceImpl.unmeasuredReason(
                        Set.of(ReviewWithholdings.STALE_ROLLOUT_REVISION, ReviewWithholdings.WORKSPACE_INACTIVE)))
                .isEqualTo(ReviewStopReason.READINESS_REFUSED);
        assertThat(ReviewServiceImpl.unmeasuredReason(
                        Set.of(ReviewStopReason.REPLACED.name(), ReviewWithholdings.STALE_ROLLOUT_REVISION)))
                .isEqualTo(ReviewStopReason.READINESS_REFUSED);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void shouldStayIncompleteWhenARunOrAdmissionWithheldAPractice(String failure) {
        assertThat(ReviewServiceImpl.unmeasuredReason(Set.of(failure)))
                .isEqualTo(ReviewStopReason.EXECUTION_INCOMPLETE);
        assertThat(ReviewServiceImpl.unmeasuredReason(Set.of(failure, ReviewWithholdings.ROLE_UNAVAILABLE)))
                .isEqualTo(ReviewStopReason.EXECUTION_INCOMPLETE);
    }

    @Test
    void shouldStayIncompleteWithoutAWithheldReason() {
        Set<@Nullable String> unknown = new HashSet<>();
        unknown.add(null);
        assertThat(ReviewServiceImpl.unmeasuredReason(unknown)).isEqualTo(ReviewStopReason.EXECUTION_INCOMPLETE);
        assertThat(ReviewServiceImpl.unmeasuredReason(Set.of())).isEqualTo(ReviewStopReason.EXECUTION_INCOMPLETE);
    }
}
