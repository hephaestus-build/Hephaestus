package de.tum.cit.aet.hephaestus.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.CapturedSourceFingerprints;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndexBuilder;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.result.ResultFreshness;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("unit")
class SourceFingerprintTest {
    private final JsonMapper mapper = new JsonMapper();
    private final SourceKind kind = new SourceKind("scm.pull-request.core");

    @Test
    void shouldKeepTheSameFingerprintWhenOnlyObservationTimesChange() {
        var first = record("{\"title\":\"A change\",\"basis\":{\"captured_at\":\"2026-01-01\"}}");
        var later = record("{\"basis\":{\"captured_at\":\"2026-02-01\"},\"title\":\"A change\"}");
        assertThat(first).isEqualTo(later);
    }

    @Test
    void shouldKeepNestedRecordContentInTheFingerprint() {
        var first = record("{\"records\":[{\"basis\":{\"text\":\"first\"}}]}");
        var changed = record("{\"records\":[{\"basis\":{\"text\":\"changed\"}}]}");
        assertThat(changed).isNotEqualTo(first);
        assertThat(record("[{\"basis\":{\"captured_at\":\"first\"},\"body\":\"same\"}]"))
                .isEqualTo(record("[{\"basis\":{\"captured_at\":\"later\"},\"body\":\"same\"}]"));
    }

    @Test
    void shouldChangeTheFingerprintForEveryChangedWorkField() {
        var original = mapper.createObjectNode().put("title", "A change").put("body", "Why it changes");
        original.put("updated_at", "2026-01-01");
        for (String field : new String[] {"title", "body", "updated_at"}) {
            for (int edit = 0; edit < 20; edit++) {
                var changed = original.deepCopy().put(field, "Changed value " + edit);
                assertThat(record(changed.toString())).isNotEqualTo(record(original.toString()));
            }
        }
    }

    @Test
    void shouldLeaveUnavailableSourcesWithoutContentProof() {
        for (var absent : new SourceCaptureState[] {
            new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND),
            new SourceCaptureState.NotCollected(SourceAbsenceReason.DISABLED)
        }) {
            assertThat(SourceFingerprint.of(mapper, kind, absent, Map.of())).isNull();
        }
        for (String git : new String[] {"scm.repository.tree", "scm.pull-request.diff"})
            assertThat(SourceFingerprint.of(
                            mapper, new SourceKind(git), available(SourceCompleteness.COMPLETE), Map.of()))
                    .isNull();
        var empty = available(SourceCompleteness.COMPLETE);
        assertThat(SourceFingerprint.of(mapper, kind, empty, Map.of()))
                .isNotEqualTo(SourceFingerprint.of(
                        mapper, kind, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND), Map.of()));
    }

    /**
     * A review can capture while the discussion sync still runs, and start its practices after the sync finished
     * without finding anything new. The mirror read more of the same work, so the work's fingerprint stays.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "scm.general-review-comments|context/general_comments.json|{\"comments\":[{\"author\":\"reviewer\",\"body\":\"A remark\",\"createdAt\":\"2026-10-01T11:00:00Z\"}],\"truncated\":%s}",
                "scm.review-threads|context/review_threads.json|{\"decisionHistoryComplete\":false,\"reviewDecisions\":[],\"threads\":[{\"comments\":[{\"body\":\"A thread remark\"}],\"id\":7,\"path\":\"src/Main.java\",\"state\":\"UNRESOLVED\"}],\"truncated\":%s}",
                "scm.linked-work-items|context/linked_work_items.json|{\"unresolvedReferences\":[],\"workItems\":[{\"number\":31,\"title\":\"A linked issue\"}],\"truncated\":%s}",
                "scm.pull-request.comments|context/comments.json|[{\"author\":\"reviewer\",\"body\":\"An inline remark\",\"path\":\"src/Main.java\",\"truncated\":%s}]"
            })
    void shouldFingerprintTheWorkAndNotHowMuchOfItTheMirrorHadRead(String source, String path, String record) {
        var recordKind = new SourceKind(source);
        var whileSyncing = new SourceCaptureState.Available(
                SourceContentState.NON_EMPTY,
                SourceCompleteness.PARTIAL,
                new SourceCaptureFacts(Instant.parse("2026-10-01T12:00:00Z"), null, null, null));
        var afterSync = new SourceCaptureState.Available(
                SourceContentState.NON_EMPTY,
                SourceCompleteness.COMPLETE,
                new SourceCaptureFacts(Instant.parse("2026-10-01T12:00:05Z"), null, null, null));
        String captured = SourceFingerprint.of(
                mapper,
                recordKind,
                whileSyncing,
                Map.of(path, record.formatted("true").getBytes(StandardCharsets.UTF_8)));

        assertThat(captured).isNotNull();
        assertThat(SourceFingerprint.of(
                        mapper,
                        recordKind,
                        afterSync,
                        Map.of(path, record.formatted("false").getBytes(StandardCharsets.UTF_8))))
                .as("the same work, read whole")
                .isEqualTo(captured);
        assertThat(SourceFingerprint.of(
                        mapper,
                        recordKind,
                        afterSync,
                        Map.of(
                                path,
                                record.replace("A ", "Another ")
                                        .replace("An ", "Another ")
                                        .formatted("false")
                                        .getBytes(StandardCharsets.UTF_8))))
                .as("changed work")
                .isNotEqualTo(captured);
    }

    @Test
    void shouldIgnoreOnlyTheChecksObservationTimeAndKeepRunContent() {
        var checks = new SourceKind("scm.pull-request.checks");
        var first = mapper.readTree(
                "{\"head_check_runs\":{\"observed_at\":\"first\",\"complete\":true,\"runs\":[{\"name\":\"Build\",\"completed_at\":\"first\"}]}}");
        var later = first.deepCopy();
        ((ObjectNode) later.path("head_check_runs")).put("observed_at", "later");
        var state = available(SourceCompleteness.COMPLETE);
        var captured = SourceFingerprint.of(
                mapper, checks, state, Map.of("context/checks.json", mapper.writeValueAsBytes(first)));
        assertThat(SourceFingerprint.of(
                        mapper, checks, state, Map.of("context/checks.json", mapper.writeValueAsBytes(later))))
                .isEqualTo(captured);
        ((ObjectNode) Objects.requireNonNull(
                        later.path("head_check_runs").path("runs").get(0)))
                .put("completed_at", "later");
        assertThat(SourceFingerprint.of(
                        mapper, checks, state, Map.of("context/checks.json", mapper.writeValueAsBytes(later))))
                .isNotEqualTo(captured);
    }

    @TempDir
    Path folder;

    @Test
    void shouldFingerprintGitWithoutReadingCapturedFileBytes() {
        var diff = new SourceKind("scm.pull-request.diff");
        String identity = "a".repeat(40) + ":" + "b".repeat(40);
        var contribution = new CaptureContribution(
                Map.of("context/diff.patch", "Captured diff".getBytes(StandardCharsets.UTF_8)),
                Map.of(diff, SourceCompleteness.COMPLETE),
                Map.of(diff, identity),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                List.of(),
                List.of(),
                List.of());
        var clock = Clock.systemUTC();
        var index = new JobFolderIndexBuilder(new ClasspathSourceCatalogRegistry(mapper, clock), mapper, clock);
        try (var captured = index.build(new ItemKind("scm.pull_request"), Map.of(diff, contribution))) {
            var withoutFiles = new PreparedEvidence(
                    Map.of(),
                    Map.of("context/diff.patch", folder.resolve("missing.patch")),
                    List.of(),
                    captured.manifest());
            assertThat(CapturedSourceFingerprints.of(mapper, withoutFiles))
                    .containsExactlyEntriesOf(Map.of(diff.value(), SourceFingerprint.git(diff, identity)));
        }
        assertThat(SourceFingerprint.of(mapper, kind, CaptureContribution.empty()))
                .isNull();
    }

    @Test
    void shouldBindTheChangeToBothGitObjects() {
        var diff = new SourceKind("scm.pull-request.diff");
        assertThat(SourceFingerprint.git(diff, "base:head"))
                .isNotEqualTo(SourceFingerprint.git(diff, "other:head"))
                .isNotEqualTo(SourceFingerprint.git(diff, "base:other"));
    }

    @Test
    void shouldCompareEveryReadableSourceAndKeepUnchangedSourcesCurrent() {
        var diff = new SourceKind("scm.pull-request.diff");
        var captured = Map.of(kind, "a".repeat(64), diff, "b".repeat(64));
        var changed = Map.of(kind, "c".repeat(64), diff, "b".repeat(64));
        assertThat(ResultFreshness.compare(captured, changed)).isEqualTo(Currentness.STALE);
        assertThat(ResultFreshness.compare(Map.of(diff, "b".repeat(64)), changed))
                .isEqualTo(Currentness.CURRENT);
        assertThat(ResultFreshness.compare(captured, Map.of(diff, "b".repeat(64))))
                .isEqualTo(Currentness.UNVERIFIABLE);
        assertThat(ResultFreshness.compare(Map.of(), changed)).isEqualTo(Currentness.UNVERIFIABLE);
    }

    @Test
    void shouldBindReadableRefsByNameAndRawObjectId() {
        var original = Map.of("refs/tags/v1", "a".repeat(40), "refs/remotes/origin/main", "b".repeat(40));
        assertThat(SourceFingerprint.refs(original))
                .isEqualTo(SourceFingerprint.refs(
                        Map.of("refs/remotes/origin/main", "b".repeat(40), "refs/tags/v1", "a".repeat(40))));
        assertThat(SourceFingerprint.refs(original))
                .isNotEqualTo(SourceFingerprint.refs(
                        Map.of("refs/tags/v1", "c".repeat(40), "refs/remotes/origin/main", "b".repeat(40))))
                .isNotEqualTo(SourceFingerprint.refs(
                        Map.of("refs/tags/v2", "a".repeat(40), "refs/remotes/origin/main", "b".repeat(40))));
    }

    @Test
    void shouldBindCoreCommitRecordsToTheirWholeGitRange() {
        var first = new SourceCaptureState.Available(
                SourceContentState.NON_EMPTY,
                SourceCompleteness.COMPLETE,
                new SourceCaptureFacts(Instant.now(), null, null, "base-one:head"));
        var next = new SourceCaptureState.Available(
                SourceContentState.NON_EMPTY,
                SourceCompleteness.COMPLETE,
                new SourceCaptureFacts(Instant.now(), null, null, "base-two:head"));
        var files = Map.of("context/metadata.json", "{\"commit_sha\":\"head\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(SourceFingerprint.of(mapper, kind, first, files))
                .isNotEqualTo(SourceFingerprint.of(mapper, kind, next, files));
    }

    private @Nullable String record(String json) {
        return SourceFingerprint.of(
                mapper,
                kind,
                available(SourceCompleteness.COMPLETE),
                Map.of("context/metadata.json", json.getBytes(StandardCharsets.UTF_8)));
    }

    private static SourceCaptureState.Available available(SourceCompleteness completeness) {
        return new SourceCaptureState.Available(
                SourceContentState.EMPTY, completeness, new SourceCaptureFacts(Instant.now(), null, null, null));
    }
}
