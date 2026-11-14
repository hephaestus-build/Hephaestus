package de.tum.cit.aet.hephaestus.agent.context.providers.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.agent.context.HistoryCopy;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ResultHistoryContentSourceTest extends BaseUnitTest {
    @Test
    void unavailableWordsKeepTheRecordedOutcomeWithoutInventingEvidence() {
        var results = mock(ResultHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        var entry = mock(ResultHistoryEntry.class);
        UUID id = UUID.randomUUID();
        when(results.forDeveloper(eq(1L), eq(2L), any(), eq(91))).thenReturn(List.of(entry));
        when(entry.resultId()).thenReturn(id);
        when(entry.reviewId()).thenReturn(UUID.randomUUID());
        when(entry.recordedAt()).thenReturn(Instant.now());
        when(entry.outcome()).thenReturn(ResultOutcome.MET);
        when(copies.of(entry)).thenReturn(new HistoryCopy(false, List.of(), List.of(), List.of()));
        var source = new ResultHistoryContentSource(
                results,
                new JsonMapper(),
                copies,
                mock(ConversationConsentGate.class),
                mock(RegisteredItemLabels.class));
        var row = source.buildPayload(1L, 2L).path("results").get(0);
        assertThat(row.path("outcome").asString()).isEqualTo("MET");
        assertThat(row.path("wordsUnavailable").asString()).isEqualTo("COPY_AUTHORITY_UNAVAILABLE");
        assertThat(row.has("summary")).isFalse();
        assertThat(ResultHistoryContentSource.resultIdOf(ResultHistoryContentSource.resourceOf(id)))
                .contains(id);
    }

    @Test
    void shouldListEachResultWithItsIdAndThePracticePageWordsWhenTheWordsMayBeCopied() {
        var results = mock(ResultHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        var pullRequest = new ItemRef(1, 41, new ItemKind("scm.pull_request"), 421L, null, null);
        UUID shownId = UUID.randomUUID();
        UUID withheldId = UUID.randomUUID();
        UUID problemId = UUID.randomUUID();
        var shown = notMet(shownId, pullRequest, Instant.parse("2026-11-01T22:30:48Z"), "v1");
        when(shown.problems())
                .thenReturn(List.of(new ProblemHistoryEntry(
                        problemId, Severity.MAJOR, "The catch block drops the error.", "src/app.ts", 53)));
        var withheld = notMet(withheldId, pullRequest, Instant.parse("2026-11-01T22:30:48Z"), "v1");
        when(results.forDeveloper(eq(1L), eq(2L), any(), eq(91))).thenReturn(List.of(shown, withheld));
        when(results.find(1, shownId, 2, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(shown));
        var authorized = new HistoryCopy(true, List.of(), List.of(), List.of());
        var unauthorized = new HistoryCopy(false, List.of(), List.of(), List.of());
        when(copies.of(shown)).thenReturn(authorized);
        when(copies.of(withheld)).thenReturn(unauthorized);
        when(copies.record(authorized)).thenReturn(true);
        var source = new ResultHistoryContentSource(
                results,
                new JsonMapper(),
                copies,
                mock(ConversationConsentGate.class),
                mock(RegisteredItemLabels.class));

        var rows = source.buildPayload(1L, 2L).path("results");
        var shownRow = rows.get(0);
        var withheldRow = rows.get(1);

        assertThat(shownRow.path("resultId").asString()).isEqualTo(shownId.toString());
        assertThat(shownRow.path("practiceName").asString()).isEqualTo("Explains why");
        assertThat(shownRow.path("summary").asString()).isEqualTo("The description omits why.");
        var problem = shownRow.path("problems").get(0);
        assertThat(problem.path("problemId").asString()).isEqualTo(problemId.toString());
        assertThat(problem.path("severity").asString()).isEqualTo("MAJOR");
        assertThat(problem.path("summary").asString()).isEqualTo("The catch block drops the error.");
        assertThat(problem.path("path").asString()).isEqualTo("src/app.ts");
        assertThat(problem.path("startLine").asInt()).isEqualTo(53);
        assertThat(source.inspect(1, 2, shownId).path("result").path("problems"))
                .as("the overview lists the problems in the shape of the detail")
                .isEqualTo(shownRow.path("problems"));

        assertThat(withheldRow.path("resultId").asString()).isEqualTo(withheldId.toString());
        assertThat(withheldRow.path("practiceName").asString()).isEqualTo("Explains why");
        assertThat(withheldRow.path("wordsUnavailable").asString()).isEqualTo("COPY_AUTHORITY_UNAVAILABLE");
        assertThat(withheldRow.has("summary")).isFalse();
        assertThat(withheldRow.has("problems")).isFalse();
    }

    @Test
    void detailRechecksTheViewerAndDeveloperWithoutDisclosingOtherResults() {
        var results = mock(ResultHistoryQuery.class);
        UUID id = UUID.randomUUID();
        var entry = mock(ResultHistoryEntry.class);
        when(entry.developerAccountId()).thenReturn(9L);
        when(results.find(1, id, 2, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(entry))
                .thenReturn(Optional.empty());
        var source = new ResultHistoryContentSource(
                results,
                new JsonMapper(),
                mock(HistoryCopies.class),
                mock(ConversationConsentGate.class),
                mock(RegisteredItemLabels.class));
        assertThat(source.inspect(1, 2, id).path("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(source.inspect(1, 2, id).path("status").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void shouldNameTheReviewedWorkOnEachRowAndInTheDetailWhenItsSourceNamesIt() {
        var results = mock(ResultHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        var labels = mock(RegisteredItemLabels.class);
        UUID named = UUID.randomUUID();
        UUID unnamed = UUID.randomUUID();
        var pullRequest = new ItemRef(1, 41, new ItemKind("scm.pull_request"), 421L, null, null);
        var gone = new ItemRef(1, 42, new ItemKind("scm.issue"), 422L, null, null);
        var first = entry(named, pullRequest);
        var second = entry(unnamed, gone);
        when(results.forDeveloper(eq(1L), eq(2L), any(), eq(91))).thenReturn(List.of(first, second));
        when(results.find(1, named, 2, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(first));
        var unavailable = new HistoryCopy(false, List.of(), List.of(), List.of());
        when(copies.of(first)).thenReturn(unavailable);
        when(copies.of(second)).thenReturn(unavailable);
        var label = new ItemLabel(
                41,
                "#421",
                "Add retry",
                "pull request",
                421,
                "org/repo",
                "https://example.com/pr/421",
                IntegrationKind.GITHUB);
        when(labels.labels(1, List.of(pullRequest, gone))).thenReturn(Map.of(41L, label));
        when(labels.labels(1, List.of(pullRequest))).thenReturn(Map.of(41L, label));
        var source = new ResultHistoryContentSource(
                results, new JsonMapper(), copies, mock(ConversationConsentGate.class), labels);

        var rows = source.buildPayload(1L, 2L).path("results");
        assertThat(rows.get(0).path("work").path("number").asInt()).isEqualTo(421);
        assertThat(rows.get(0).path("work").path("title").asString()).isEqualTo("Add retry");
        assertThat(rows.get(0).path("work").path("url").asString()).isEqualTo("https://example.com/pr/421");
        assertThat(rows.get(1).has("work")).isFalse();
        assertThat(source.inspect(1, 2, named)
                        .path("result")
                        .path("work")
                        .path("number")
                        .asInt())
                .isEqualTo(421);
    }

    @Test
    void shouldCarryTheRecordedEndToOverviewAndDetailWhenAResultEnded() {
        var results = mock(ResultHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        var pullRequest = new ItemRef(1, 41, new ItemKind("scm.pull_request"), 421L, null, null);
        UUID endedId = UUID.randomUUID();
        UUID openId = UUID.randomUUID();
        Instant endedAt = Instant.parse("2026-10-15T11:30:00Z");
        var ended = notMet(
                endedId,
                pullRequest,
                Instant.parse("2026-10-14T09:00:00Z"),
                "scm.pull_request:v1:" + "a".repeat(40) + ":" + "b".repeat(40) + ":" + "c".repeat(64));
        when(ended.endedAt()).thenReturn(endedAt);
        when(ended.endReason()).thenReturn(ResultEndReason.WORK_CHANGED);
        var open = notMet(
                openId,
                pullRequest,
                Instant.parse("2026-10-16T08:00:00Z"),
                "scm.pull_request:v1:" + "a".repeat(40) + ":" + "d".repeat(40) + ":" + "e".repeat(64));
        when(results.forDeveloper(eq(1L), eq(2L), any(), eq(91))).thenReturn(List.of(open, ended));
        when(results.find(1, endedId, 2, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(ended));
        when(results.find(1, openId, 2, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(open));
        var authorized = new HistoryCopy(true, List.of(), List.of(), List.of());
        when(copies.of(ended)).thenReturn(authorized);
        when(copies.of(open)).thenReturn(authorized);
        when(copies.record(authorized)).thenReturn(true);
        var source = new ResultHistoryContentSource(
                results,
                new JsonMapper(),
                copies,
                mock(ConversationConsentGate.class),
                mock(RegisteredItemLabels.class));

        var rows = source.buildPayload(1L, 2L).path("results");
        var openRow = rows.get(0);
        var endedRow = rows.get(1);
        var openDetail = source.inspect(1, 2, openId).path("result");
        var endedDetail = source.inspect(1, 2, endedId).path("result");

        for (var row : List.of(endedRow, endedDetail)) {
            assertThat(row.path("resultId").asString()).isEqualTo(endedId.toString());
            assertThat(row.path("outcome").asString()).isEqualTo("NOT_MET");
            assertThat(row.path("summary").asString()).isEqualTo("The description omits why.");
            assertThat(row.path("endedAt").asString()).isEqualTo(endedAt.toString());
            assertThat(row.path("endReason").asString()).isEqualTo("WORK_CHANGED");
        }
        for (var row : List.of(openRow, openDetail)) {
            assertThat(row.path("resultId").asString()).isEqualTo(openId.toString());
            assertThat(row.path("outcome").asString()).isEqualTo("NOT_MET");
            assertThat(row.has("endedAt")).isFalse();
            assertThat(row.has("endReason")).isFalse();
            assertThat(row.toString()).doesNotContain("CURRENT").doesNotContain("REPAIRED");
        }
    }

    @Test
    void shouldKeepTheEndButNotTheWordsWhenAReplacedResultHasNoCopyAuthority() {
        var results = mock(ResultHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        UUID id = UUID.randomUUID();
        Instant endedAt = Instant.parse("2026-10-15T11:30:00Z");
        var replaced = notMet(
                id,
                new ItemRef(1, 41, new ItemKind("scm.pull_request"), 421L, null, null),
                Instant.parse("2026-10-14T09:00:00Z"),
                "opaque-version");
        when(replaced.endedAt()).thenReturn(endedAt);
        when(replaced.endReason()).thenReturn(ResultEndReason.REPLACED);
        when(results.forDeveloper(eq(1L), eq(2L), any(), eq(91))).thenReturn(List.of(replaced));
        when(results.find(1, id, 2, SourceUsePurpose.CONVERSATIONAL_MENTORING)).thenReturn(Optional.of(replaced));
        var unavailable = new HistoryCopy(false, List.of(), List.of(), List.of());
        when(copies.of(replaced)).thenReturn(unavailable);
        var source = new ResultHistoryContentSource(
                results,
                new JsonMapper(),
                copies,
                mock(ConversationConsentGate.class),
                mock(RegisteredItemLabels.class));

        var row = source.buildPayload(1L, 2L).path("results").get(0);
        var detail = source.inspect(1, 2, id).path("result");

        for (var shown : List.of(row, detail)) {
            assertThat(shown.path("outcome").asString()).isEqualTo("NOT_MET");
            assertThat(shown.path("endedAt").asString()).isEqualTo(endedAt.toString());
            assertThat(shown.path("endReason").asString()).isEqualTo("REPLACED");
            assertThat(shown.path("wordsUnavailable").asString()).isEqualTo("COPY_AUTHORITY_UNAVAILABLE");
            assertThat(shown.has("summary")).isFalse();
            assertThat(shown.has("explanation")).isFalse();
        }
    }

    @Test
    void shouldStateTheCaptureTimeAndStoredWorkComparisonAlikeInOverviewAndDetailWhenTheWordsMayBeCopied() {
        var results = mock(ResultHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        UUID id = UUID.randomUUID();
        Instant capturedAt = Instant.parse("2026-10-14T08:45:00Z");
        var entry = notMet(
                id,
                new ItemRef(1, 41, new ItemKind("scm.pull_request"), 421L, null, null),
                Instant.parse("2026-10-14T09:00:00Z"),
                "opaque-version");
        when(entry.capturedAt()).thenReturn(capturedAt);
        when(entry.storedWorkVersion()).thenReturn(ResultHistoryEntry.StoredWorkVersion.DIFFERENT);
        when(results.forDeveloper(eq(1L), eq(2L), any(), eq(91))).thenReturn(List.of(entry));
        when(results.find(1, id, 2, SourceUsePurpose.CONVERSATIONAL_MENTORING)).thenReturn(Optional.of(entry));
        var authorized = new HistoryCopy(true, List.of(), List.of(), List.of());
        when(copies.of(entry)).thenReturn(authorized);
        when(copies.record(authorized)).thenReturn(true);
        var source = new ResultHistoryContentSource(
                results,
                new JsonMapper(),
                copies,
                mock(ConversationConsentGate.class),
                mock(RegisteredItemLabels.class));

        var row = source.buildPayload(1L, 2L).path("results").get(0);
        var detail = source.inspect(1, 2, id).path("result");

        for (var shown : List.of(row, detail)) {
            assertThat(shown.path("capturedAt").asString()).isEqualTo(capturedAt.toString());
            assertThat(shown.path("recordedAt").asString()).isEqualTo("2026-10-14T09:00:00Z");
            assertThat(shown.path("storedWorkVersion").asString()).isEqualTo("DIFFERENT");
            assertThat(shown.path("itemVersion").asString()).isEqualTo("opaque-version");
            assertThat(shown.path("outcome").asString()).isEqualTo("NOT_MET");
            assertThat(shown.has("endedAt")).isFalse();
        }
    }

    @Test
    void shouldLeaveTheStoredWorkUnknownAndTheCaptureTimeOutWhenTheWordsMayNotBeCopied() {
        var results = mock(ResultHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        UUID id = UUID.randomUUID();
        var entry = notMet(
                id,
                new ItemRef(1, 41, new ItemKind("scm.pull_request"), 421L, null, null),
                Instant.parse("2026-10-14T09:00:00Z"),
                "opaque-version");
        // The query found both before the final copy fence refused the words.
        lenient().when(entry.capturedAt()).thenReturn(Instant.parse("2026-10-14T08:45:00Z"));
        lenient().when(entry.storedWorkVersion()).thenReturn(ResultHistoryEntry.StoredWorkVersion.SAME);
        when(results.forDeveloper(eq(1L), eq(2L), any(), eq(91))).thenReturn(List.of(entry));
        when(results.find(1, id, 2, SourceUsePurpose.CONVERSATIONAL_MENTORING)).thenReturn(Optional.of(entry));
        var refused = new HistoryCopy(true, List.of(), List.of(), List.of());
        when(copies.of(entry)).thenReturn(refused);
        when(copies.record(refused)).thenReturn(false);
        var source = new ResultHistoryContentSource(
                results,
                new JsonMapper(),
                copies,
                mock(ConversationConsentGate.class),
                mock(RegisteredItemLabels.class));

        var row = source.buildPayload(1L, 2L).path("results").get(0);
        var detail = source.inspect(1, 2, id).path("result");

        for (var shown : List.of(row, detail)) {
            assertThat(shown.path("storedWorkVersion").asString()).isEqualTo("UNKNOWN");
            assertThat(shown.has("capturedAt")).isFalse();
            assertThat(shown.path("wordsUnavailable").asString()).isEqualTo("COPY_AUTHORITY_UNAVAILABLE");
            assertThat(shown.path("outcome").asString()).isEqualTo("NOT_MET");
        }
    }

    private static ResultHistoryEntry notMet(UUID id, ItemRef item, Instant recordedAt, String itemVersion) {
        var entry = mock(ResultHistoryEntry.class);
        when(entry.resultId()).thenReturn(id);
        when(entry.reviewId()).thenReturn(UUID.randomUUID());
        when(entry.practiceSlug()).thenReturn("explains-why");
        lenient().when(entry.practiceName()).thenReturn("Explains why");
        when(entry.itemId()).thenReturn(item.itemId());
        when(entry.itemKind()).thenReturn(item.kind().value());
        when(entry.itemVersion()).thenReturn(itemVersion);
        when(entry.outcome()).thenReturn(ResultOutcome.NOT_MET);
        when(entry.recordedAt()).thenReturn(recordedAt);
        when(entry.item()).thenReturn(item);
        // Read only by a detail read.
        lenient().when(entry.developerAccountId()).thenReturn(2L);
        // Read only where copy authority allows the result's words.
        lenient().when(entry.summary()).thenReturn("The description omits why.");
        lenient().when(entry.explanation()).thenReturn("The captured description names the change but not its reason.");
        lenient().when(entry.storedWorkVersion()).thenReturn(ResultHistoryEntry.StoredWorkVersion.UNKNOWN);
        return entry;
    }

    private static ResultHistoryEntry entry(UUID id, ItemRef item) {
        var entry = mock(ResultHistoryEntry.class);
        when(entry.resultId()).thenReturn(id);
        when(entry.reviewId()).thenReturn(UUID.randomUUID());
        when(entry.recordedAt()).thenReturn(Instant.now());
        when(entry.outcome()).thenReturn(ResultOutcome.MET);
        when(entry.itemId()).thenReturn(item.itemId());
        when(entry.item()).thenReturn(item);
        lenient().when(entry.developerAccountId()).thenReturn(2L);
        return entry;
    }
}
