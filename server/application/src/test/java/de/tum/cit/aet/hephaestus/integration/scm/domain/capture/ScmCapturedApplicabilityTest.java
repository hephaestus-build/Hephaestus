package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CapturedApplicability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.AppliesWhen;
import de.tum.cit.aet.hephaestus.practices.AppliesWhenClause;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class ScmCapturedApplicabilityTest extends BaseUnitTest {

    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind THREADS = new SourceKind("scm.review-threads");
    private static final String CHANGE_PATH = "context/change.json";
    private static final String THREADS_PATH = "context/review_threads.json";

    private static final ReviewChange TWO_FILE_CHANGE =
            change(Set.of("src/App.java", "docs/readme.md"), "+int answer = 42;\n+a line\n");

    private static final Set<String> SWIFT_GATED = Set.of(
            "keeps-views-free-of-networking-and-persistence",
            "owns-state-at-the-right-level",
            "makes-ui-accessible-by-default",
            "uses-structured-concurrency-safely",
            "ships-a-preview-with-each-new-view",
            "declares-permissions-truthfully-at-point-of-use",
            "uses-adaptive-colors-for-every-appearance");

    private static final String COMMENT_GATED = "engaging-with-inline-review-comments";

    private static final Set<String> MERGE_GATED = Set.of(
            "merged-past-unresolved-review-threads",
            "merge-confirms-the-linked-issue-outcome",
            "defers-review-asks-into-tracked-work");

    private static final String TEST_GATED = "keeps-the-test-suite-honest";

    private static final String DEPENDENCY_GATED = "changes-dependencies-deliberately";

    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final String METADATA_PATH = "context/metadata.json";
    private static final String OPEN_RECORD = "{\"is_merged\":false,\"state\":\"OPEN\"}";

    /** A collection the comment gate reads: its owning source, its file, and the file with and without a comment. */
    private record CommentCollection(SourceKind owner, String path, String empty, String withComment) {}

    private static final List<CommentCollection> COMMENT_COLLECTIONS = List.of(
            // A review decision is no thread: the empty file still records one.
            new CommentCollection(
                    THREADS,
                    THREADS_PATH,
                    "{\"threads\":[],\"reviewDecisions\":[{\"state\":\"APPROVED\"}],\"truncated\":false}",
                    "{\"threads\":[{\"path\":\"App.java\",\"state\":\"UNRESOLVED\"}],\"reviewDecisions\":[],"
                            + "\"truncated\":false}"),
            new CommentCollection(
                    new SourceKind("scm.pull-request.comments"),
                    "context/comments.json",
                    "[]",
                    "[{\"body\":\"Please explain this branch.\"}]"),
            new CommentCollection(
                    new SourceKind("scm.general-review-comments"),
                    "context/general_comments.json",
                    "{\"comments\":[]}",
                    "{\"comments\":[{\"body\":\"Could you add a test for the empty list?\"}]}"));

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ScmCapturedApplicability evaluator = new ScmCapturedApplicability(mapper);

    @Nested
    @DisplayName("Only complete evidence can prove absence")
    class RequiresCompleteEvidence {

        @Test
        void shouldRunThePracticeWhenAClauseCannotBeDecided() {

            AppliesWhenCheck check = evaluate(
                    dependencySubject(),
                    captures(availableDiff(SourceCompleteness.PARTIAL, SourceContentState.NON_EMPTY)),
                    TWO_FILE_CHANGE);

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses())
                    .extracting(clause -> clause.result())
                    .containsExactly(AppliesWhenCheck.Result.UNDECIDABLE);
        }

        @Test
        void shouldRunThePracticeWhenTheSourceWasNeverCaptured() {
            AppliesWhenCheck check = evaluate(
                    dependencySubject(),
                    captures(new SourceCapture(
                            DIFF,
                            new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_PROVIDER),
                            EvidenceSnapshotFixtures.citation(DIFF),
                            List.of())),
                    TWO_FILE_CHANGE);

            assertThat(check.absent()).isFalse();
        }

        @Test
        void shouldRunThePracticeWhenNoChangeWasPrepared() {
            AppliesWhen subject = applies(
                    "the change touches no dependency manifest and adds no test",
                    List.of(changedPath(List.of("**/pom.xml")), diffContains(List.of("@Test"))));

            AppliesWhenCheck check = evaluate(
                    subject, captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)), null);

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses())
                    .extracting(clause -> clause.result())
                    .containsExactly(AppliesWhenCheck.Result.UNDECIDABLE, AppliesWhenCheck.Result.UNDECIDABLE);
        }

        @Test
        void shouldRunThePracticeWhenAnEvidenceFileWillNotParse() {
            AppliesWhenCheck check = evaluate(
                    threadSubject(),
                    captures(availableThreads(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    Map.of(THREADS_PATH, "{not json".getBytes(StandardCharsets.UTF_8)),
                    null);

            assertThat(check.absent()).isFalse();
        }

        @Test
        void shouldRunThePracticeWhenTheStagedEvidenceBytesAreMissing() {
            AppliesWhenCheck check = evaluate(
                    threadSubject(),
                    captures(availableThreads(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    Map.of(),
                    null);

            assertThat(check.absent()).isFalse();
        }

        @Test
        void shouldRunThePracticeWhenOnlySomeClausesCouldBeSettled() {
            AppliesWhen subject = applies(
                    "the change touches no test file and holds no test marker",
                    List.of(
                            changedPath(List.of("**/*Test*")),
                            new AppliesWhenClause(null, null, "scm.review-threads", null)));

            AppliesWhenCheck check = evaluate(
                    subject,
                    captures(
                            availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY),
                            availableThreads(SourceCompleteness.PARTIAL, SourceContentState.NON_EMPTY)),
                    TWO_FILE_CHANGE);

            assertThat(check.clauses())
                    .extracting(clause -> clause.result())
                    .containsExactly(AppliesWhenCheck.Result.NOT_FOUND, AppliesWhenCheck.Result.UNDECIDABLE);
            assertThat(check.absent()).isFalse();
        }
    }

    @Nested
    class DecidesWhatItCan {

        @Test
        void shouldProveInapplicabilityWhenNoChangedPathMatches() {
            AppliesWhenCheck check = evaluate(
                    dependencySubject(),
                    captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    TWO_FILE_CHANGE);

            assertThat(check.absent()).isTrue();
            assertThat(check.otherwise()).isEqualTo("the change touches no dependency manifest or lockfile");
            assertThat(check.clauses().getFirst().aspect()).isEqualTo("CHANGED_PATH");
            assertThat(check.clauses().getFirst().readFrom()).isEqualTo(DIFF);
        }

        @Test
        void shouldAskThePracticeWhenAChangedPathMatches() {
            AppliesWhenCheck check = evaluate(
                    dependencySubject(),
                    captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(Set.of("src/App.java", "docs/readme.md", "pom.xml"), "+<dependency/>\n"));

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses().getFirst().result()).isEqualTo(AppliesWhenCheck.Result.FOUND);
        }

        @Test
        void shouldReadBothSidesOfARename() {
            AppliesWhenCheck check = evaluate(
                    applies("no test file", List.of(changedPath(List.of("**/*Test*")))),
                    captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(Set.of("src/CalculatorTest.java", "src/Calculator.java"), "+x\n"));

            assertThat(check.absent()).isFalse();
        }

        @Test
        void shouldFindATestMarkerOnTheRemovedSideOfAHunk() {
            AppliesWhen subject = applies(
                    "the change touches no test file and neither adds nor removes a test declaration",
                    List.of(changedPath(List.of("**/*test*", "**/*Test*")), diffContains(List.of("#[test]", "@Test"))));

            AppliesWhenCheck check = evaluate(
                    subject,
                    captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(
                            Set.of("src/lib.rs"),
                            "diff --git a/src/lib.rs b/src/lib.rs\n@@ -1,4 +1,1 @@\n-#[test]\n-fn works() {}\n"));

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses())
                    .extracting(clause -> clause.result())
                    .containsExactly(AppliesWhenCheck.Result.NOT_FOUND, AppliesWhenCheck.Result.FOUND);
        }

        @Test
        void shouldProveInapplicabilityWhenANamedCollectionIsEmpty() {
            AppliesWhenCheck check = evaluate(
                    threadSubject(),
                    captures(availableThreads(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    Map.of(
                            THREADS_PATH,
                            "{\"threads\":[],\"reviewDecisions\":[{\"state\":\"APPROVED\"}],\"truncated\":false}"
                                    .getBytes(StandardCharsets.UTF_8)),
                    null);

            assertThat(check.absent()).isTrue();
        }

        @Test
        void shouldAskThePracticeWhenANamedCollectionHasEntries() {
            AppliesWhenCheck check = evaluate(
                    threadSubject(),
                    captures(availableThreads(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    Map.of(
                            THREADS_PATH,
                            "{\"threads\":[{\"path\":\"a.java\",\"state\":\"UNRESOLVED\"}]}"
                                    .getBytes(StandardCharsets.UTF_8)),
                    null);

            assertThat(check.absent()).isFalse();
        }

        @Test
        void shouldProveInapplicabilityWhenTheWholeSourceCapturedEmpty() {
            AppliesWhenCheck check = evaluate(
                    threadSubject(),
                    captures(availableThreads(SourceCompleteness.COMPLETE, SourceContentState.EMPTY)),
                    Map.of(),
                    null);

            assertThat(check.absent()).isTrue();
        }

        @Test
        void shouldProveInapplicabilityWhenTheWholeChangeCapturedEmptyWithoutReadingIt() {
            AppliesWhenCheck check = evaluate(
                    dependencySubject(),
                    captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.EMPTY)),
                    null);

            assertThat(check.absent()).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "**/pom.xml,pom.xml,true",
        "**/pom.xml,server/pom.xml,true",
        "*.json,a/b.json,false",
        "*.json,b.json,true",
        "**/a.b,x/axb,false",
        "**/a.b,x/a.b,true",
        "**/[id].ts,app/[id].ts,true",
        "**/vendor/**,a/vendor/b/c.go,true",
        "**/vendor/**,vendor/c.go,true",
        "**/vendor/**,a/vendored.go,false",
        "src/**.java,src/deep/App.java,false",
        "src/**.java,src/App.java,true",
        "src/foo**/bar.java,src/foobar.java,false",
        "src/foo**/bar.java,src/foobar/bar.java,true",
        "src/**/App.java,src/App.java,true"
    })
    void shouldUseAntPathMatching(String glob, String path, boolean matches) {
        var check = evaluate(
                applies("No matching path.", List.of(changedPath(List.of(glob)))),
                captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                change(Set.of(path), "+line"));
        assertThat(check.clauses().getFirst().result())
                .isEqualTo(matches ? AppliesWhenCheck.Result.FOUND : AppliesWhenCheck.Result.NOT_FOUND);
    }

    @Test
    void shouldMatchADirectorySegmentContainingANewline() {
        var check = evaluate(
                applies("No matching path.", List.of(changedPath(List.of("src/**/App.java")))),
                captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                change(Set.of("src/\n/App.java"), "+line"));
        assertThat(check.clauses().getFirst().result()).isEqualTo(AppliesWhenCheck.Result.FOUND);
    }

    @Test
    void shouldReadInlineCommentsFromTheOwningSourceArray() {
        var comments = new SourceKind("scm.pull-request.comments");
        var capture = new SourceCapture(
                comments,
                new SourceCaptureState.Available(
                        SourceContentState.NON_EMPTY, SourceCompleteness.COMPLETE, facts(), List.of()),
                EvidenceSnapshotFixtures.citation(comments),
                List.of(new CapturedSource("context/comments.json", "application/json", sha(), 1)));
        var subject = applies(
                "No inline comments.", List.of(new AppliesWhenClause(null, null, "scm.inline-review-comments", null)));
        var check = evaluate(
                subject,
                captures(capture),
                Map.of(
                        "context/comments.json",
                        "[{\"body\":\"Please explain this branch.\"}]".getBytes(StandardCharsets.UTF_8)),
                null);
        assertThat(check.clauses())
                .containsExactly(new AppliesWhenCheck.Clause("HAS_ITEMS", comments, AppliesWhenCheck.Result.FOUND));
        var empty = evaluate(
                subject,
                captures(capture),
                Map.of("context/comments.json", "[]".getBytes(StandardCharsets.UTF_8)),
                null);
        assertThat(empty.absent()).isTrue();
        var missing = evaluate(subject, captures(capture), Map.of(), null);
        assertThat(missing.clauses().getFirst().result()).isEqualTo(AppliesWhenCheck.Result.UNDECIDABLE);
    }

    @Test
    void shouldLeaveOtherItemKindsToTheirCaptureAdapters() {
        assertThat(evaluator.evaluate(new ItemKind("scm.issue"), conditions(dependencySubject()), List.of(), Map.of()))
                .isEmpty();
    }

    @Nested
    @DisplayName("Each bundled gate decides its own fixtures")
    class BundledGates {

        /** The bundled practices that carry an Applies when gate. Each one needs fixtures in this class. */
        private static final Map<String, CatalogPracticeDocument> BUNDLED_GATES =
                ScmCaptureFixtures.bundledPractices().entrySet().stream()
                        .filter(entry -> entry.getValue().definition().appliesWhen() != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        @Test
        void shouldCarryFixturesForEveryBundledGate() {
            assertThat(BUNDLED_GATES.keySet())
                    .as("a bundled Applies when gate without fixtures here")
                    .containsExactlyInAnyOrderElementsOf(Stream.of(
                                    SWIFT_GATED.stream(),
                                    MERGE_GATED.stream(),
                                    Stream.of(COMMENT_GATED, TEST_GATED, DEPENDENCY_GATED))
                            .flatMap(slugs -> slugs)
                            .toList());
            assertThat(BUNDLED_GATES.values())
                    .extracting(CatalogPracticeDocument::kind)
                    .containsOnly(new ItemKind("scm.pull_request"));
        }

        @Test
        void shouldAskEachSwiftPracticeWhenTheChangeTouchesASwiftFile() {
            var complete = captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY));
            var atTheRoot = change(Set.of("EventList.swift"), "+struct EventList {}\n");
            var nested = change(Set.of("App/Views/EventList.swift", "README.md"), "+struct EventList {}\n");
            for (String slug : SWIFT_GATED) {
                assertThat(evaluate(gate(slug), complete, atTheRoot).absent())
                        .as("%s with a Swift file at the root", slug)
                        .isFalse();
                assertThat(evaluate(gate(slug), complete, nested).absent())
                        .as("%s with a nested Swift file", slug)
                        .isFalse();
            }
        }

        @Test
        void shouldProveEachSwiftPracticeAbsentWhenACompleteChangeTouchesNoSwiftFile() {
            var complete = captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY));
            // A project file and the word Swift in prose are not Swift source.
            var noSwift =
                    change(Set.of("App/Info.plist", "android/app/Feed.kt", "README.md"), "+Ported from SwiftUI\n");
            for (String slug : SWIFT_GATED) {
                var check = evaluate(gate(slug), complete, noSwift);
                assertThat(check.absent()).as("%s without a Swift file", slug).isTrue();
                assertThat(check.clauses())
                        .extracting(AppliesWhenCheck.Clause::readFrom)
                        .as("%s reads the change", slug)
                        .containsOnly(DIFF);
            }
        }

        @Test
        void shouldKeepEachSwiftPracticeEligibleWhenTheChangeCannotBeRead() {
            var noSwift = change(Set.of("README.md"), "+notes\n");
            var unavailable = new SourceCapture(
                    DIFF,
                    new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_PROVIDER),
                    EvidenceSnapshotFixtures.citation(DIFF),
                    List.of());
            for (String slug : SWIFT_GATED) {
                assertUndecided(
                        evaluate(
                                gate(slug),
                                captures(availableDiff(SourceCompleteness.PARTIAL, SourceContentState.NON_EMPTY)),
                                noSwift),
                        slug + " with a partial change");
                assertUndecided(evaluate(gate(slug), captures(unavailable), noSwift), slug + " with no change capture");
                assertUndecided(
                        evaluate(
                                gate(slug),
                                captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                                null),
                        slug + " with no prepared change");
            }
        }

        @Test
        void shouldAskTheCommentPracticeWhenAnyOwningCollectionHoldsAComment() {
            var gate = gate(COMMENT_GATED);
            assertThat(evaluate(gate, commentCaptures(null), commentFiles(), null)
                            .absent())
                    .as("every collection complete and empty")
                    .isTrue();
            for (var collection : COMMENT_COLLECTIONS) {
                var files = commentFiles();
                files.put(collection.path(), collection.withComment().getBytes(StandardCharsets.UTF_8));
                var check = evaluate(gate, commentCaptures(null), files, null);
                assertThat(check.absent())
                        .as("a comment in %s", collection.path())
                        .isFalse();
                assertThat(check.clauses())
                        .filteredOn(clause -> clause.result() == AppliesWhenCheck.Result.FOUND)
                        .extracting(AppliesWhenCheck.Clause::readFrom)
                        .as("the source that owns %s", collection.path())
                        .containsExactly(collection.owner());
            }
        }

        @Test
        void shouldKeepTheCommentPracticeEligibleWhenACollectionCannotBeRead() {
            var gate = gate(COMMENT_GATED);
            for (var collection : COMMENT_COLLECTIONS) {
                var withoutBytes = commentFiles();
                withoutBytes.remove(collection.path());
                var missing = evaluate(gate, commentCaptures(null), withoutBytes, null);
                var partial = evaluate(gate, commentCaptures(collection.owner()), commentFiles(), null);
                for (var check : List.of(missing, partial)) {
                    assertThat(check.absent())
                            .as("%s unavailable or partial", collection.path())
                            .isFalse();
                    assertThat(check.clauses())
                            .filteredOn(clause -> collection.owner().equals(clause.readFrom()))
                            .extracting(AppliesWhenCheck.Clause::result)
                            .as("the unavailable collection %s", collection.path())
                            .containsExactly(AppliesWhenCheck.Result.UNDECIDABLE);
                    assertThat(check.clauses())
                            .extracting(AppliesWhenCheck.Clause::result)
                            .doesNotContain(AppliesWhenCheck.Result.FOUND);
                }
            }
        }

        @Test
        void shouldAskEachMergePracticeOnlyWhenTheRecordSaysItMerged() {
            for (String slug : MERGE_GATED) {
                var merged = evaluate(
                        gate(slug),
                        mergeCapture(SourceCompleteness.COMPLETE),
                        metadata("{\"is_merged\":true,\"state\":\"MERGED\"}"),
                        null);
                assertThat(merged.absent()).as("%s on merged work", slug).isFalse();
                var open = evaluate(gate(slug), mergeCapture(SourceCompleteness.COMPLETE), metadata(OPEN_RECORD), null);
                assertThat(open.absent()).as("%s on open work", slug).isTrue();
                assertThat(open.otherwise()).isEqualTo("the record of this pull request shows it open, not merged");
                assertThat(open.clauses())
                        .extracting(AppliesWhenCheck.Clause::readFrom)
                        .containsExactly(CORE);
            }
        }

        @Test
        void shouldKeepEachMergePracticeEligibleWhenTheRecordCannotSay() {
            for (String slug : MERGE_GATED) {
                assertUndecided(
                        evaluate(gate(slug), mergeCapture(SourceCompleteness.PARTIAL), metadata(OPEN_RECORD), null),
                        slug + " with a partial record");
                assertUndecided(
                        evaluate(gate(slug), mergeCapture(SourceCompleteness.COMPLETE), Map.of(), null),
                        slug + " with no record");
                assertUndecided(
                        evaluate(
                                gate(slug),
                                mergeCapture(SourceCompleteness.COMPLETE),
                                Map.of(METADATA_PATH, "{not json".getBytes(StandardCharsets.UTF_8)),
                                null),
                        slug + " with an unreadable record");
                assertUndecided(
                        evaluate(
                                gate(slug),
                                mergeCapture(SourceCompleteness.COMPLETE),
                                Map.of(METADATA_PATH, "{}".getBytes(StandardCharsets.UTF_8)),
                                null),
                        slug + " with a record that omits the merge");
            }
        }

        @Test
        void shouldAskEachMergePracticeWhenARecordHoldsAMergeTimeButNoMergeFlag() {
            var record = "{\"is_merged\":false,\"state\":\"CLOSED\",\"merged_at\":\"2026-11-02T10:00:00Z\"}";
            for (String slug : MERGE_GATED) {
                var check = evaluate(gate(slug), mergeCapture(SourceCompleteness.COMPLETE), metadata(record), null);
                assertThat(check.absent()).as(slug).isFalse();
                assertThat(check.clauses())
                        .extracting(AppliesWhenCheck.Clause::result)
                        .containsExactly(AppliesWhenCheck.Result.FOUND);
            }
        }

        @Test
        void shouldNotProveNoMergeFromARecordThatIsNotOpen() {
            for (String record : List.of(
                    "{\"is_merged\":false,\"state\":\"CLOSED\"}", "{\"is_merged\":false}", "{\"state\":\"OPEN\"}")) {
                for (String slug : MERGE_GATED) {
                    assertUndecided(
                            evaluate(gate(slug), mergeCapture(SourceCompleteness.COMPLETE), metadata(record), null),
                            slug + " with " + record);
                }
            }
        }

        @ParameterizedTest
        @CsvSource({
            "src/test/java/AppTest.java",
            "src/AppTest.java",
            "tests/helpers.py",
            "app/models/user_spec.rb",
            "web/login.spec.ts",
            "pkg/parser_test.go",
            "ThingTests/ThingTests.swift",
            "e2e/login.ts",
            "src/__snapshots__/view.snap",
        })
        void shouldAskTheTestPracticeWhenAPathCouldHoldATest(String path) {
            var check = evaluate(
                    gate(TEST_GATED),
                    captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(Set.of(path), "+a line\n"));
            assertThat(check.absent()).as(path).isFalse();
        }

        @Test
        void shouldAskTheTestPracticeWhenTheDiffHoldsATestInSourceCode() {
            var check = evaluate(
                    gate(TEST_GATED),
                    captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(Set.of("src/lib.rs"), "-#[cfg(test)]\n-mod tests {}\n"));
            assertThat(check.absent()).isFalse();
        }

        @Test
        void shouldRecordThatNoTestLooksTouchedWhenPathsAndLinesHoldNone() {
            var check = evaluate(
                    gate(TEST_GATED),
                    captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(Set.of("src/App.java", "docs/readme.md"), "+int answer = 42;\n"));
            assertThat(check.absent()).isTrue();
            assertThat(check.otherwise()).isEqualTo("the change touches no path or line that marks a test");
        }

        @ParameterizedTest
        @CsvSource({
            "package.json",
            "web/pnpm-lock.yaml",
            "server/build.gradle.kts",
            "pom.xml",
            "go.mod",
            "Cargo.toml",
            "Cargo.lock",
            "requirements-dev.txt",
            "ios/Podfile",
            "Package.resolved",
            "App.xcodeproj/project.pbxproj",
            "Lib/Lib.csproj",
            ".github/workflows/ci.yml",
            "docker/Dockerfile.worker",
            "vendor/acme/lib.c",
        })
        void shouldAskTheDependencyPracticeWhenAPathCanDeclareADependency(String path) {
            var check = evaluate(
                    gate(DEPENDENCY_GATED),
                    captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(Set.of(path), "+a line\n"));
            assertThat(check.absent()).as(path).isFalse();
        }

        @Test
        void shouldRecordNoDependencyFileWhenOnlySourceChanged() {
            var check = evaluate(
                    gate(DEPENDENCY_GATED),
                    captures(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(Set.of("App/ContentView.swift", "README.md"), "+import SwiftData\n"));
            assertThat(check.absent()).isTrue();
            assertThat(check.otherwise()).isEqualTo("the change touches no file that can declare a dependency");
        }

        private static List<SourceCapture> mergeCapture(SourceCompleteness completeness) {
            return List.of(available(CORE, METADATA_PATH, completeness));
        }

        private static Map<String, byte[]> metadata(String record) {
            return Map.of(METADATA_PATH, record.getBytes(StandardCharsets.UTF_8));
        }

        private static AppliesWhen gate(String slug) {
            var practice = Objects.requireNonNull(BUNDLED_GATES.get(slug), slug);
            return Objects.requireNonNull(practice.definition().appliesWhen(), slug);
        }
    }

    private static void assertUndecided(AppliesWhenCheck check, String description) {
        assertThat(check.absent()).as(description).isFalse();
        assertThat(check.clauses())
                .extracting(AppliesWhenCheck.Clause::result)
                .as(description)
                .containsOnly(AppliesWhenCheck.Result.UNDECIDABLE);
    }

    private static Map<String, byte[]> commentFiles() {
        var files = new HashMap<String, byte[]>();
        COMMENT_COLLECTIONS.forEach(
                collection -> files.put(collection.path(), collection.empty().getBytes(StandardCharsets.UTF_8)));
        return files;
    }

    private static List<SourceCapture> commentCaptures(@Nullable SourceKind partial) {
        return COMMENT_COLLECTIONS.stream()
                .map(collection -> available(
                        collection.owner(),
                        collection.path(),
                        collection.owner().equals(partial) ? SourceCompleteness.PARTIAL : SourceCompleteness.COMPLETE))
                .toList();
    }

    private static SourceCapture available(SourceKind kind, String path, SourceCompleteness completeness) {
        return new SourceCapture(
                kind,
                new SourceCaptureState.Available(SourceContentState.NON_EMPTY, completeness, facts(), List.of()),
                EvidenceSnapshotFixtures.citation(kind),
                List.of(new CapturedSource(path, "application/json", sha(), 1)));
    }

    private AppliesWhenCheck evaluate(
            AppliesWhen subject, List<SourceCapture> captures, @Nullable ReviewChange change) {
        return evaluate(subject, captures, Map.of(), change);
    }

    private AppliesWhenCheck evaluate(
            AppliesWhen subject,
            List<SourceCapture> captures,
            Map<String, byte[]> staged,
            @Nullable ReviewChange change) {
        var files = new HashMap<>(staged);
        if (change != null)
            files.put(
                    CHANGE_PATH,
                    mapper.writeValueAsBytes(Map.of("changed_paths", change.changedPaths(), "diff", change.text())));
        var clauses = evaluator.evaluate(new ItemKind("scm.pull_request"), conditions(subject), captures, files);
        AppliesWhenCheck check = new AppliesWhenCheck(
                clauses.stream().allMatch(c -> c.result() == AppliesWhenCheck.Result.NOT_FOUND),
                subject.otherwise(),
                clauses);
        assertThat(check).isNotNull();
        return check;
    }

    private static List<CapturedApplicability.Condition> conditions(AppliesWhen subject) {
        return subject.anyOf().stream()
                .map(c -> new CapturedApplicability.Condition(
                        c.changedPath(), c.diffContains(), c.hasItems(), Boolean.TRUE.equals(c.merged())))
                .toList();
    }

    private static ReviewChange change(Set<String> paths, String text) {
        return new ReviewChange() {
            @Override
            public Set<String> changedPaths() {
                return paths;
            }

            @Override
            public String text() {
                return text;
            }
        };
    }

    private static AppliesWhen applies(String reason, List<AppliesWhenClause> clauses) {
        return new AppliesWhen(clauses, reason);
    }

    private static AppliesWhenClause changedPath(List<String> globs) {
        return new AppliesWhenClause(globs, null, null, null);
    }

    private static AppliesWhenClause diffContains(List<String> text) {
        return new AppliesWhenClause(null, text, null, null);
    }

    private static AppliesWhen dependencySubject() {
        return applies(
                "the change touches no dependency manifest or lockfile",
                List.of(changedPath(List.of("**/pom.xml", "**/package.json"))));
    }

    private static AppliesWhen threadSubject() {
        return applies(
                "nobody left a review comment on this pull request",
                List.of(new AppliesWhenClause(null, null, "scm.review-threads", null)));
    }

    private static SourceCapture availableDiff(SourceCompleteness completeness, SourceContentState content) {
        return new SourceCapture(
                DIFF,
                new SourceCaptureState.Available(content, completeness, facts(), List.of()),
                EvidenceSnapshotFixtures.citation(DIFF),
                List.of(new CapturedSource(CHANGE_PATH, "application/json", sha(), 1)));
    }

    private static SourceCapture availableThreads(SourceCompleteness completeness, SourceContentState content) {
        return new SourceCapture(
                THREADS,
                new SourceCaptureState.Available(content, completeness, facts(), List.of()),
                EvidenceSnapshotFixtures.citation(THREADS),
                List.of(new CapturedSource(THREADS_PATH, "application/json", sha(), 1)));
    }

    private static SourceCaptureFacts facts() {
        return new SourceCaptureFacts(Instant.EPOCH, null, null, null);
    }

    private static String sha() {
        return "0".repeat(64);
    }

    private static List<SourceCapture> captures(SourceCapture... captures) {
        return List.of(captures);
    }
}
