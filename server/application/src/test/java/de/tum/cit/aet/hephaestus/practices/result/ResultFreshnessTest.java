package de.tum.cit.aet.hephaestus.practices.result;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.facts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessDecision;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReason;
import de.tum.cit.aet.hephaestus.evidence.SourceFingerprint;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemTransactions;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmSourceFingerprints;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmWorkCapture;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.UnverifiableReason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("unit")
class ResultFreshnessTest {
    private static final SourceKind LINKED = new SourceKind("scm.linked-work-items");
    private static final String FINGERPRINT = "a".repeat(64);
    private static final Instant CAPTURED_AT = Instant.parse("2026-11-03T14:05:00Z");

    private final ReviewPracticeRepository practices = mock(ReviewPracticeRepository.class);
    private final ProductItemQuery items = mock(ProductItemQuery.class);
    private final ResultFreshness freshness = new ResultFreshness(practices, items);
    private final ItemRef item = new ItemRef(1, 7, new ItemKind("scm.pull_request"), 70L, null, null);

    /** An unverifiable check always names why: the capture code of the read that failed, or what was missing. */
    @Test
    void shouldNameWhyAPracticeStartCannotVerifyItsSources() {
        var core = new SourceKind("scm.pull-request.core");
        var facts = JsonMapper.builder().build().createObjectNode();
        facts.putObject("sourceFingerprints").put(core.value(), FINGERPRINT).put(LINKED.value(), FINGERPRINT);
        when(items.find(1, 7)).thenReturn(Optional.of(item));
        when(items.read(eq(item), eq(Set.of(core, LINKED)), any()))
                .thenReturn(new ProductItemQuery.FingerprintRead(Map.of(), "OCCASION_EVIDENCE_UNAVAILABLE"));

        var failed = freshness.check(1, 7, null, facts);
        assertThat(failed.currentness()).isEqualTo(Currentness.UNVERIFIABLE);
        assertThat(failed.unread()).containsExactlyInAnyOrder(core.value(), LINKED.value());
        assertThat(failed.unreadReason()).isEqualTo("OCCASION_EVIDENCE_UNAVAILABLE");

        when(items.read(eq(item), eq(Set.of(core, LINKED)), any()))
                .thenReturn(new ProductItemQuery.FingerprintRead(Map.of(core, FINGERPRINT), null));
        assertThat(freshness.check(1, 7, null, facts).unreadReason())
                .as("one source read nothing while the read ran")
                .isEqualTo("SOURCE_UNREAD");

        assertThat(freshness
                        .check(1, 7, null, JsonMapper.builder().build().createObjectNode())
                        .unreadReason())
                .isEqualTo("RECORD_MISSING");

        when(items.read(eq(item), eq(Set.of(core, LINKED)), any()))
                .thenReturn(new ProductItemQuery.FingerprintRead(Map.of(core, FINGERPRINT, LINKED, FINGERPRINT), null));
        assertThat(freshness.check(1, 7, null, facts).unreadReason()).isNull();
    }

    @Test
    void shouldBeUnverifiableWithItsCaptureTimeWhenTheCurrentWorkCannotBeReadUntilItCanBeReadAgain() {
        var result = result();
        captured(result, decision(CAPTURED_AT, Map.of(LINKED.value(), FINGERPRINT)));
        when(items.findAll(eq(1L), any())).thenReturn(Map.of(7L, item));
        when(items.read(eq(item), eq(Set.of(LINKED)), any()))
                .thenReturn(new ProductItemQuery.FingerprintRead(Map.of(), null));

        assertThat(freshness.freshness(1, facts(List.of(result)), Map.of(7L, item)))
                .containsEntry(
                        result.getId(),
                        new Freshness(Currentness.UNVERIFIABLE, UnverifiableReason.WORK_UNREADABLE, CAPTURED_AT));
        assertThat(freshness.states(1, List.of(result))).containsEntry(result.getId(), Currentness.UNVERIFIABLE);

        when(items.read(eq(item), eq(Set.of(LINKED)), any()))
                .thenReturn(new ProductItemQuery.FingerprintRead(Map.of(LINKED, FINGERPRINT), null));

        assertThat(freshness.freshness(1, facts(List.of(result)), Map.of(7L, item)))
                .containsEntry(result.getId(), Freshness.current(CAPTURED_AT));
    }

    @Test
    void shouldBeUnverifiableWithoutATimeWhenTheCaptureRecordIsMissing() {
        var result = result();
        when(practices.sourceCaptures(eq(1L), any(), any())).thenReturn(List.of());

        assertThat(freshness.freshness(1, facts(List.of(result)), Map.of(7L, item)))
                .containsEntry(result.getId(), Freshness.recordMissing());
    }

    @Test
    void shouldKeepTheDiffResultCurrentWhenAnotherPracticeNeedsAnExpiredOccasionRecord() {
        var source = mock(ScmItemService.class);
        var reader = new ScmSourceFingerprints(source, mock(ScmWorkCapture.class), mock(ScmItemTransactions.class));
        var query = new ProductItemQuery(
                mock(ProductItemRepository.class),
                List.of(source),
                List.of(),
                List.of(),
                new JsonMapper(),
                List.of(reader));
        var occasion = UUID.randomUUID();
        var base = "a".repeat(40);
        var head = "b".repeat(40);
        var version = ScmVersion.pullRequest(base, head, "Pull request", null);
        var snapshot = mock(ItemSnapshot.class);
        when(snapshot.version()).thenReturn(version);
        when(source.supports(item)).thenReturn(true);
        when(source.pin(item, null)).thenReturn(snapshot);
        when(source.pinOccasion(item, version, occasion))
                .thenThrow(new ItemCaptureException(ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE, "EXPIRED"));

        var diff = new SourceKind("scm.pull-request.diff");
        var diffResult = result();
        var recordResult = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(diffResult.getWorkspaceId())
                .reviewId(diffResult.getReviewId())
                .practiceId(4)
                .itemId(diffResult.getItemId())
                .outcome(ResultOutcome.MET)
                .recordedAt(diffResult.getRecordedAt())
                .build();
        when(practices.sourceCaptures(eq(1L), any(), any()))
                .thenReturn(List.of(
                        new ResultSourceCapture(
                                diffResult.getReviewId(),
                                diffResult.getPracticeId(),
                                diffResult.getItemId(),
                                ReviewKind.ITEM,
                                ReviewOrigin.EVENT,
                                occasion,
                                decision(
                                        CAPTURED_AT,
                                        Map.of(diff.value(), SourceFingerprint.git(diff, base + ":" + head))),
                                null),
                        new ResultSourceCapture(
                                recordResult.getReviewId(),
                                recordResult.getPracticeId(),
                                recordResult.getItemId(),
                                ReviewKind.ITEM,
                                ReviewOrigin.EVENT,
                                occasion,
                                decision(CAPTURED_AT, Map.of(LINKED.value(), FINGERPRINT)),
                                null)));

        assertThat(new ResultFreshness(practices, query)
                        .freshness(1, facts(List.of(diffResult, recordResult)), Map.of(7L, item)))
                .containsEntry(
                        recordResult.getId(),
                        new Freshness(Currentness.UNVERIFIABLE, UnverifiableReason.WORK_UNREADABLE, CAPTURED_AT))
                .containsEntry(diffResult.getId(), Freshness.current(CAPTURED_AT));
    }

    @Test
    void shouldKeepTheCaptureTimeOfAResultWhoseWorkChanged() {
        var result = result();
        captured(result, decision(CAPTURED_AT, Map.of(LINKED.value(), FINGERPRINT)));
        when(items.read(eq(item), eq(Set.of(LINKED)), any()))
                .thenReturn(new ProductItemQuery.FingerprintRead(Map.of(LINKED, "b".repeat(64)), null));

        assertThat(freshness.freshness(1, facts(List.of(result)), Map.of(7L, item)))
                .containsEntry(result.getId(), Freshness.stale(CAPTURED_AT));
    }

    @Test
    void shouldReadTheCaptureTimeFromTheReadinessDecisionThatACaptureRecords() {
        var result = result();
        ObjectNode facts = new JsonMapper()
                .valueToTree(new AutomatedReviewReadinessDecision(
                        "issue-traceability-and-lifecycle",
                        CAPTURED_AT,
                        false,
                        List.of(AutomatedReviewReadinessReason.NO_AUTOMATED_REVIEW),
                        List.of()));
        facts.putObject("sourceFingerprints").put(LINKED.value(), FINGERPRINT);
        captured(result, facts);
        when(items.read(eq(item), eq(Set.of(LINKED)), any()))
                .thenReturn(new ProductItemQuery.FingerprintRead(Map.of(), null));

        assertThat(freshness
                        .freshness(1, facts(List.of(result)), Map.of(7L, item))
                        .get(result.getId()))
                .isEqualTo(new Freshness(Currentness.UNVERIFIABLE, UnverifiableReason.WORK_UNREADABLE, CAPTURED_AT));
    }

    @Test
    void shouldRefuseToSyncInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> freshness.sync(1, List.of(result())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside a database transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(items);
    }

    /**
     * One read reads each kind of a piece of work once. A later check reads only the kinds that no check read yet, and
     * outside one read every check reads.
     */
    @Test
    void shouldReadEachSourceOfAPieceOfWorkOnceInOneRead() {
        var core = new SourceKind("scm.pull-request.core");
        var checks = new SourceKind("scm.pull-request.checks");
        var coreOnly = result();
        var both = result();
        capturedAll(Map.of(
                coreOnly, decision(CAPTURED_AT, Map.of(core.value(), FINGERPRINT)),
                both, decision(CAPTURED_AT, Map.of(core.value(), FINGERPRINT, checks.value(), FINGERPRINT))));
        when(items.read(eq(item), any(), any())).thenAnswer(call -> {
            Set<SourceKind> kinds = call.getArgument(1);
            return new ProductItemQuery.FingerprintRead(
                    kinds.stream().collect(Collectors.toMap(Function.identity(), kind -> FINGERPRINT)), null);
        });
        var refs = Map.of(7L, item);

        var states = freshness.asOneRead(
                1,
                () -> List.of(
                        freshness.freshness(1, facts(List.of(coreOnly)), refs).get(coreOnly.getId()),
                        freshness.freshness(1, facts(List.of(both)), refs).get(both.getId()),
                        freshness.freshness(1, facts(List.of(both)), refs).get(both.getId())));

        assertThat(states).extracting(Freshness::currentness).containsOnly(Currentness.CURRENT);
        verify(items).read(item, Set.of(core), null);
        verify(items).read(item, Set.of(checks), null);
        verify(items, times(2)).read(eq(item), any(), any());
        freshness.freshness(1, facts(List.of(coreOnly)), refs);
        verify(items, times(3)).read(eq(item), any(), any());
    }

    /**
     * A read that fails keeps no answer. Within one read, a later check of the same or more kinds answers nothing without
     * reading again, as its own read would fail, while a check of fewer kinds still reads its own.
     */
    @Test
    void shouldAnswerNothingForMoreKindsThanAReadThatFailedInOneRead() {
        var core = new SourceKind("scm.pull-request.core");
        var both = result();
        var coreOnly = result();
        capturedAll(Map.of(
                both, decision(CAPTURED_AT, Map.of(core.value(), FINGERPRINT, LINKED.value(), FINGERPRINT)),
                coreOnly, decision(CAPTURED_AT, Map.of(core.value(), FINGERPRINT))));
        when(items.read(eq(item), any(), any())).thenAnswer(call -> {
            Set<SourceKind> kinds = call.getArgument(1);
            return kinds.contains(LINKED)
                    ? new ProductItemQuery.FingerprintRead(Map.of(), "OCCASION_EVIDENCE_UNAVAILABLE")
                    : new ProductItemQuery.FingerprintRead(Map.of(core, FINGERPRINT), null);
        });
        var refs = Map.of(7L, item);

        var states = freshness.asOneRead(
                1,
                () -> List.of(
                        freshness.freshness(1, facts(List.of(both)), refs).get(both.getId()),
                        freshness.freshness(1, facts(List.of(both)), refs).get(both.getId()),
                        freshness.freshness(1, facts(List.of(coreOnly)), refs).get(coreOnly.getId())));

        assertThat(states)
                .extracting(Freshness::currentness)
                .containsExactly(Currentness.UNVERIFIABLE, Currentness.UNVERIFIABLE, Currentness.CURRENT);
        verify(items).read(item, Set.of(core, LINKED), null);
        verify(items).read(item, Set.of(core), null);
        verify(items, times(2)).read(eq(item), any(), any());
    }

    private void capturedAll(Map<Result, ObjectNode> decisions) {
        when(practices.sourceCaptures(eq(1L), any(), any()))
                .thenReturn(decisions.entrySet().stream()
                        .map(entry -> new ResultSourceCapture(
                                entry.getKey().getReviewId(),
                                entry.getKey().getPracticeId(),
                                entry.getKey().getItemId(),
                                ReviewKind.ITEM,
                                ReviewOrigin.REQUEST,
                                null,
                                entry.getValue(),
                                null))
                        .toList());
    }

    private Result result() {
        return Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .reviewId(UUID.randomUUID())
                .practiceId(3)
                .itemId(7)
                .outcome(ResultOutcome.MET)
                .recordedAt(CAPTURED_AT.plusSeconds(600))
                .build();
    }

    private void captured(Result result, ObjectNode decision) {
        when(practices.sourceCaptures(eq(1L), any(), any()))
                .thenReturn(List.of(new ResultSourceCapture(
                        result.getReviewId(),
                        result.getPracticeId(),
                        result.getItemId(),
                        ReviewKind.ITEM,
                        ReviewOrigin.REQUEST,
                        null,
                        decision,
                        null)));
    }

    private static ObjectNode decision(Instant decidedAt, Map<String, String> fingerprints) {
        var decision = new JsonMapper().createObjectNode();
        decision.put("decidedAt", decidedAt.toString());
        var sources = decision.putObject("sourceFingerprints");
        fingerprints.forEach(sources::put);
        return decision;
    }
}
