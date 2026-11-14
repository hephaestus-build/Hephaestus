package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessDecision;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReason;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.UnverifiableReason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    @Test
    void shouldBeUnverifiableWithItsCaptureTimeWhenTheCurrentWorkCannotBeReadUntilItCanBeReadAgain() {
        var result = result();
        captured(result, decision(CAPTURED_AT, Map.of(LINKED.value(), FINGERPRINT)));
        when(items.findAll(eq(1L), any())).thenReturn(Map.of(7L, item));
        when(items.fingerprints(eq(item), eq(Set.of(LINKED)), any())).thenReturn(Map.of());

        assertThat(freshness.freshness(1, List.of(result), Map.of(7L, item)))
                .containsEntry(
                        result.getId(),
                        new Freshness(Currentness.UNVERIFIABLE, UnverifiableReason.WORK_UNREADABLE, CAPTURED_AT));
        assertThat(freshness.states(1, List.of(result))).containsEntry(result.getId(), Currentness.UNVERIFIABLE);

        when(items.fingerprints(eq(item), eq(Set.of(LINKED)), any())).thenReturn(Map.of(LINKED, FINGERPRINT));

        assertThat(freshness.freshness(1, List.of(result), Map.of(7L, item)))
                .containsEntry(result.getId(), Freshness.current(CAPTURED_AT));
    }

    @Test
    void shouldBeUnverifiableWithoutATimeWhenTheCaptureRecordIsMissing() {
        var result = result();
        when(practices.sourceCaptures(eq(1L), any(), any())).thenReturn(List.of());

        assertThat(freshness.freshness(1, List.of(result), Map.of(7L, item)))
                .containsEntry(result.getId(), Freshness.recordMissing());
    }

    @Test
    void shouldKeepTheCaptureTimeOfAResultWhoseWorkChanged() {
        var result = result();
        captured(result, decision(CAPTURED_AT, Map.of(LINKED.value(), FINGERPRINT)));
        when(items.fingerprints(eq(item), eq(Set.of(LINKED)), any())).thenReturn(Map.of(LINKED, "b".repeat(64)));

        assertThat(freshness.freshness(1, List.of(result), Map.of(7L, item)))
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
        when(items.fingerprints(eq(item), eq(Set.of(LINKED)), any())).thenReturn(Map.of());

        assertThat(freshness.freshness(1, List.of(result), Map.of(7L, item)).get(result.getId()))
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
