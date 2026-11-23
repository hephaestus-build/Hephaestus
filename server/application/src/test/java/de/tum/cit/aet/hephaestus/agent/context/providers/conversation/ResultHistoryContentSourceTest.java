package de.tum.cit.aet.hephaestus.agent.context.providers.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.result.PracticeAssessments;
import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
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
    void shouldListEachResultWithItsIdAndThePracticePageWordsWhenTheWordsMayBeCopied() {
        var results = mock(ResultHistoryQuery.class);
        var pullRequest = new ItemRef(1, 41, new ItemKind("scm.pull_request"), 421L, null, null);
        UUID shownId = UUID.randomUUID();
        UUID problemId = UUID.randomUUID();
        var shown = notMet(shownId, pullRequest, Instant.parse("2026-11-01T22:30:48Z"), "v1");
        when(shown.problems())
                .thenReturn(List.of(new ProblemHistoryEntry(
                        problemId, Severity.MAJOR, "The catch block drops the error.", "src/app.ts", 53)));
        var withheld = mock(ResultHistoryEntry.class);
        when(withheld.developerAccountId()).thenReturn(2L);
        when(withheld.item()).thenReturn(pullRequest);
        when(withheld.origin()).thenReturn(ReviewOrigin.REQUEST);
        when(results.forDeveloper(eq(1L), eq(2L), any(), eq(91))).thenReturn(List.of(shown, withheld));
        when(results.find(1, shownId, 2, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(shown));
        var source = source(results, mock(RegisteredItemLabels.class));

        var payload = source.buildPayload(1L, 2L);
        assertThat(payload.path("coverage").path("omittedForSource").asInt()).isEqualTo(1);
        var rows = payload.path("results");
        var shownRow = rows.get(0);

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

        assertThat(rows).hasSize(1);
    }

    @Test
    void detailRechecksTheViewerAndDeveloperWithoutDisclosingOtherResults() {
        var results = mock(ResultHistoryQuery.class);
        UUID id = UUID.randomUUID();
        var entry = mock(ResultHistoryEntry.class);
        when(entry.copyAuthorityComplete()).thenReturn(true);
        when(entry.developerAccountId()).thenReturn(9L);
        when(results.find(1, id, 2, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(entry))
                .thenReturn(Optional.empty());
        var source = source(results, mock(RegisteredItemLabels.class));
        assertThat(source.inspect(1, 2, id).path("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(source.inspect(1, 2, id).path("status").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void shouldNameTheReviewedWorkOnEachRowAndInTheDetailWhenItsSourceNamesIt() {
        var results = mock(ResultHistoryQuery.class);
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
        var source = source(results, labels);

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
        var source = source(results, mock(RegisteredItemLabels.class));

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
    void shouldStateTheCaptureTimeAndStoredWorkComparisonAlikeInOverviewAndDetailWhenTheWordsMayBeCopied() {
        var results = mock(ResultHistoryQuery.class);
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
        var source = source(results, mock(RegisteredItemLabels.class));

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

    private static ResultHistoryContentSource source(ResultHistoryQuery results, RegisteredItemLabels labels) {
        return new ResultHistoryContentSource(
                assessments(results, labels),
                new JsonMapper(),
                new HistoryCopies(mock(PersonDataCopyRecorder.class)),
                mock(ConversationConsentGate.class));
    }

    private static PracticeAssessments assessments(ResultHistoryQuery results, RegisteredItemLabels labels) {
        return new PracticeAssessments(
                results, mock(ReviewAssignmentQuery.class), labels, mock(DeveloperPracticeStandings.class));
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
        when(entry.copyAuthorityComplete()).thenReturn(true);
        when(entry.developerAccountId()).thenReturn(2L);
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
        when(entry.copyAuthorityComplete()).thenReturn(true);
        when(entry.developerAccountId()).thenReturn(2L);
        when(entry.storedWorkVersion()).thenReturn(ResultHistoryEntry.StoredWorkVersion.UNKNOWN);
        when(entry.itemKind()).thenReturn(item.kind().value());
        return entry;
    }
}
