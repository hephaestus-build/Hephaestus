package de.tum.cit.aet.hephaestus.practices.result;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.resultBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultMark;
import de.tum.cit.aet.hephaestus.practices.review.ReviewTestData;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.UnverifiableReason;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ResultVisibilityPolicyTest {
    private final EvidenceAuthorization authorization = mock(EvidenceAuthorization.class);
    private final ResultMarkRepository marks = mock(ResultMarkRepository.class);
    private final PracticeRepository practices = mock(PracticeRepository.class);
    private final PracticeVersionRepository versions = mock(PracticeVersionRepository.class);
    private final ResultSourceAuthorization sources = mock(ResultSourceAuthorization.class);
    private final ResultFreshness freshness = mock(ResultFreshness.class);
    private final ResultVisibilityPolicy policy =
            new ResultVisibilityPolicy(authorization, marks, practices, versions, sources, freshness);
    private final Practice practice = ReviewTestData.practice(1, 1, "test-practice");

    @BeforeEach
    void catalog() {
        when(freshness.states(ArgumentMatchers.eq(1L), ArgumentMatchers.anyCollection()))
                .thenAnswer(call -> {
                    Collection<Result> rows = call.getArgument(1);
                    return rows.stream().collect(Collectors.toMap(Result::getId, row -> Currentness.CURRENT));
                });
        when(practices.findByWorkspaceIdAndIdIn(ArgumentMatchers.eq(1L), ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(practice));
        when(versions.findByWorkspaceIdAndIdIn(ArgumentMatchers.eq(1L), ArgumentMatchers.any()))
                .thenReturn(List.of(practice.getCurrentVersion()));
        when(marks.findByWorkspaceIdAndResultIdInAndRemovedAtIsNull(ArgumentMatchers.eq(1L), ArgumentMatchers.any()))
                .thenReturn(List.of());
    }

    @Test
    void forwardsTheActualSourceAuthorizationForCurrentProof() {
        var r = result();
        when(authorization.permitsAll(1L, List.of(r), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of(r.getId()));
        assertThat(policy.permitsAll(1L, List.of(r), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .containsExactly(r.getId());
    }

    @Test
    void endedProofNeverCountsAsCurrent() {
        var r = result();
        r.end(ResultEndReason.REPLACED, r.getRecordedAt().plusSeconds(1));
        assertThat(policy.permitsAll(1L, List.of(r), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isEmpty();
    }

    @Test
    void archivedProofIsUnverifiableForNewClaims() {
        var r = result();
        r = resultBuilder(r).evidenceVersion((short) 0).build();
        assertThat(ResultVisibilityPolicy.freshness(
                        r,
                        practice.getCurrentVersion(),
                        practice.getCurrentVersion(),
                        Freshness.current(Instant.parse("2026-11-01T10:00:00Z"))))
                .isEqualTo(new Freshness(Currentness.UNVERIFIABLE, UnverifiableReason.RECORD_MISSING, null));
        assertThat(policy.permitsAll(1L, List.of(r), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isEmpty();
        r.end(ResultEndReason.REPLACED, r.getRecordedAt().plusSeconds(1));
        assertThat(ResultVisibilityPolicy.freshness(
                                r, practice.getCurrentVersion(), practice.getCurrentVersion(), Freshness.current(null))
                        .currentness())
                .isEqualTo(Currentness.UNVERIFIABLE);
    }

    @Test
    void shouldKeepWhyTheSourcesAreUnverifiableAndWhenTheyWereLastVerified() {
        var r = result();
        var capturedAt = Instant.parse("2026-11-03T14:05:00Z");
        var unreadable = new Freshness(Currentness.UNVERIFIABLE, UnverifiableReason.WORK_UNREADABLE, capturedAt);

        assertThat(ResultVisibilityPolicy.freshness(
                        r, practice.getCurrentVersion(), practice.getCurrentVersion(), unreadable))
                .isEqualTo(unreadable);
        r.end(ResultEndReason.REPLACED, r.getRecordedAt().plusSeconds(1));
        assertThat(ResultVisibilityPolicy.freshness(
                        r, practice.getCurrentVersion(), practice.getCurrentVersion(), unreadable))
                .isEqualTo(Freshness.stale(capturedAt));
    }

    @Test
    void aChangedStandardMakesProofStale() {
        var r = result();
        var old = new PracticeVersion(
                practice, 1, new JsonMapper().valueToTree(DefinitionTestFixtures.definition()), null);
        ReflectionTestUtils.setField(old, "reviewDigest", "v6:" + "b".repeat(64));
        assertThat(ResultVisibilityPolicy.freshness(r, old, practice.getCurrentVersion(), Freshness.current(null))
                        .currentness())
                .isEqualTo(Currentness.STALE);
    }

    @Test
    void anIncorrectMarkWithholdsCurrentProof() {
        var r = result();
        var mark = ResultMark.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .resultId(r.getId())
                .kind(ResultMarkKind.INCORRECT)
                .reason("Incorrect attribution")
                .createdAt(r.getRecordedAt())
                .build();
        when(marks.findByWorkspaceIdAndResultIdInAndRemovedAtIsNull(1L, List.of(r.getId())))
                .thenReturn(List.of(mark));
        assertThat(policy.permitsAll(1L, List.of(r), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isEmpty();
    }

    @Test
    void acceptanceDoesNotEraseAuthorizedProof() {
        var r = result();
        var mark = ResultMark.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .resultId(r.getId())
                .kind(ResultMarkKind.ACCEPTED)
                .reason("Recorded and accepted")
                .createdAt(r.getRecordedAt())
                .build();
        when(marks.findByWorkspaceIdAndResultIdInAndRemovedAtIsNull(1L, List.of(r.getId())))
                .thenReturn(List.of(mark));
        when(authorization.permitsAll(1L, List.of(r), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of(r.getId()));
        assertThat(policy.permitsAll(1L, List.of(r), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .containsExactly(r.getId());
    }

    @Test
    void foreignRowsAreNotSentForSourceAuthorization() {
        var r = result();
        r = resultBuilder(r).workspaceId(2).build();
        assertThat(policy.permitsHistory(1L, List.of(r), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isEmpty();
    }

    private Result result() {
        return Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .reviewId(UUID.randomUUID())
                .practiceId(1)
                .practiceVersionId(practice.getCurrentVersion().getId())
                .itemId(1)
                .outcome(ResultOutcome.MET)
                .summary("The captured work meets this practice.")
                .explanation("The recorded work supports this judgment.")
                .evidence(new JsonMapper().createObjectNode())
                .coverage(new JsonMapper().createObjectNode())
                .recordedAt(Instant.parse("2026-08-01T00:00:00Z"))
                .build();
    }

    @Test
    void shouldWithholdUnverifiableProofFromHistoricalStandings() {
        var missingSummary = resultBuilder(result()).summary(null).build();
        when(authorization.permitsAll(
                        ArgumentMatchers.eq(1L),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenAnswer(call -> call.<List<Result>>getArgument(1).stream()
                        .map(Result::getId)
                        .collect(Collectors.toSet()));
        assertThat(policy.permitsAt(
                        1L,
                        List.of(missingSummary),
                        SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY,
                        missingSummary.getRecordedAt().plusSeconds(1)))
                .isEmpty();
    }

    @Test
    void shouldRetainProofThatWasCurrentBeforeItEndedAndTheStandardChanged() {
        var r = result();
        var edge = r.getRecordedAt().plusSeconds(1);
        r.end(ResultEndReason.REPLACED, edge.plusSeconds(1));
        var oldVersion = practice.getCurrentVersion();
        when(versions.findAt(1L, List.of(1L), edge)).thenReturn(List.of(oldVersion));
        var changed = new PracticeVersion(
                practice, 2, new JsonMapper().valueToTree(DefinitionTestFixtures.definition()), null);
        ReflectionTestUtils.setField(changed, "reviewDigest", "v6:" + "b".repeat(64));
        practice.setCurrentVersion(changed);
        when(authorization.permitsAll(1L, List.of(r), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of(r.getId()));
        assertThat(policy.permitsAt(1L, List.of(r), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, edge))
                .containsExactly(r.getId());
        assertThat(policy.permitsAt(1L, List.of(r), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, edge.plusSeconds(1)))
                .isEmpty();
    }

    @Test
    void shouldShowAuthorizedMigratedFeedbackWithoutACertifiedPracticeVersion() {
        var archived = resultBuilder(result())
                .evidenceVersion((short) 0)
                .practiceVersionId(null)
                .build();
        when(authorization.permitsAll(1L, List.of(archived), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of(archived.getId()));
        assertThat(policy.permitsShown(1L, List.of(archived), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .containsExactly(archived.getId());
        assertThat(policy.permitsAll(1L, List.of(archived), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isEmpty();
    }
}
