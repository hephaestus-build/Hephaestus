package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.EvidenceCollection;
import de.tum.cit.aet.hephaestus.evidence.PracticePreconditionAspect;
import de.tum.cit.aet.hephaestus.evidence.PracticePreconditionCheck;
import de.tum.cit.aet.hephaestus.evidence.PracticePreconditionResult;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceArtifact;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.PracticePrecondition;
import de.tum.cit.aet.hephaestus.practices.PracticePreconditionClause;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The evaluator's whole job is to be sure before it is quiet, so most of what follows is about the
 * cases where it must NOT be sure.
 */
class PracticePreconditionEvaluatorTest extends BaseUnitTest {

    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind THREADS = new SourceKind("scm.review-threads");
    private static final String CHANGE_PATH = "context/change.json";
    private static final String THREADS_PATH = "context/review_threads.json";

    /** Two files, neither a dependency manifest nor a test. */
    private static final ReviewChange TWO_FILE_CHANGE =
            change(Set.of("src/App.java", "docs/readme.md"), "+int answer = 42;\n+a line\n");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PracticePreconditionEvaluator evaluator = new PracticePreconditionEvaluator(mapper);

    @Nested
    @DisplayName("A subject may never hide an observation")
    class NeverHidesAnObservation {

        /**
         * The rule stated as a test. Every other case in this class is one way of reaching it: unless the
         * evaluator can point at a capture complete enough to settle the question, the practice runs.
         */
        @Test
        void shouldRunThePracticeWhenAClauseCannotBeDecided() {
            // A diff captured only in part. Nothing in it matches, but "nothing in the part I read"
            // is not "nothing", and answering as though it were is the one failure that matters here.
            PracticePreconditionCheck check = evaluate(
                    dependencySubject(),
                    manifestWith(availableDiff(SourceCompleteness.PARTIAL, SourceContentState.NON_EMPTY)),
                    TWO_FILE_CHANGE);

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses())
                    .extracting(clause -> clause.result())
                    .containsExactly(PracticePreconditionResult.UNDECIDABLE);
        }

        @Test
        void shouldRunThePracticeWhenTheSourceWasNeverCaptured() {
            PracticePreconditionCheck check = evaluate(
                    dependencySubject(),
                    manifestWith(new SourceCapture(
                            DIFF, new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_PROVIDER), List.of())),
                    TWO_FILE_CHANGE);

            assertThat(check.absent()).isFalse();
        }

        /**
         * The manifest says the diff is there and whole, and no change was prepared to read. The two
         * disagree, and a disagreement is not evidence that a dependency manifest was not touched.
         */
        @Test
        void shouldRunThePracticeWhenNoChangeWasPrepared() {
            PracticePrecondition subject = new PracticePrecondition(
                    "the change touches no dependency manifest and adds no test",
                    List.of(
                            PracticePreconditionClause.changedPathMatches(List.of("**/pom.xml")),
                            PracticePreconditionClause.diffContains(List.of("@Test"))));

            PracticePreconditionCheck check = evaluate(
                    subject,
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    null);

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses())
                    .extracting(clause -> clause.result())
                    .containsExactly(PracticePreconditionResult.UNDECIDABLE, PracticePreconditionResult.UNDECIDABLE);
        }

        @Test
        void shouldRunThePracticeWhenAnEvidenceFileWillNotParse() {
            PracticePreconditionCheck check = evaluate(
                    threadSubject(),
                    manifestWith(availableThreads(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    Map.of(THREADS_PATH, "{not json".getBytes(StandardCharsets.UTF_8)),
                    null);

            assertThat(check.absent()).isFalse();
        }

        /** The manifest says the threads are there and whole, and staging does not hold them. */
        @Test
        void shouldRunThePracticeWhenTheStagedEvidenceBytesAreMissing() {
            PracticePreconditionCheck check = evaluate(
                    threadSubject(),
                    manifestWith(availableThreads(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    Map.of(),
                    null);

            assertThat(check.absent()).isFalse();
        }

        /**
         * One clause settled and empty, one that could not be settled. The subject is a disjunction, so
         * the undecided alternative is enough to keep the practice running — adding a clause can never
         * make a practice quieter.
         */
        @Test
        void shouldRunThePracticeWhenOnlySomeClausesCouldBeSettled() {
            PracticePrecondition subject = new PracticePrecondition(
                    "the change touches no test file and holds no test marker",
                    List.of(
                            PracticePreconditionClause.changedPathMatches(List.of("**/*Test*")),
                            PracticePreconditionClause.evidenceHasItems(EvidenceCollection.SCM_REVIEW_THREADS)));

            PracticePreconditionCheck check = evaluate(
                    subject,
                    manifestWith(
                            availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY),
                            availableThreads(SourceCompleteness.PARTIAL, SourceContentState.NON_EMPTY)),
                    TWO_FILE_CHANGE);

            assertThat(check.clauses())
                    .extracting(clause -> clause.result())
                    .containsExactly(PracticePreconditionResult.NOT_FOUND, PracticePreconditionResult.UNDECIDABLE);
            assertThat(check.absent()).isFalse();
        }
    }

    @Nested
    class DecidesWhatItCan {

        @Test
        void shouldWithholdThePracticeWhenNoChangedPathMatches() {
            PracticePreconditionCheck check = evaluate(
                    dependencySubject(),
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    TWO_FILE_CHANGE);

            assertThat(check.absent()).isTrue();
            assertThat(check.describedAs()).isEqualTo("the change touches no dependency manifest or lockfile");
            assertThat(check.clauses().getFirst().aspect()).isEqualTo(PracticePreconditionAspect.CHANGED_PATH);
            assertThat(check.clauses().getFirst().readFrom()).isEqualTo(DIFF);
        }

        @Test
        void shouldAskThePracticeWhenAChangedPathMatches() {
            PracticePreconditionCheck check = evaluate(
                    dependencySubject(),
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(Set.of("src/App.java", "docs/readme.md", "pom.xml"), "+<dependency/>\n"));

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses().getFirst().result()).isEqualTo(PracticePreconditionResult.FOUND);
        }

        /**
         * The old path of a rename counts. A pull request that renames the last test file away is exactly
         * the change the test-suite practice exists to look at, and the change lists both of its names.
         */
        @Test
        void shouldReadBothSidesOfARename() {
            PracticePreconditionCheck check = evaluate(
                    new PracticePrecondition(
                            "no test file",
                            List.of(PracticePreconditionClause.changedPathMatches(List.of("**/*Test*")))),
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(Set.of("src/CalculatorTest.java", "src/Calculator.java"), "+x\n"));

            assertThat(check.absent()).isFalse();
        }

        /** Removing the last test in a source file leaves no test-named path, only a removed marker. */
        @Test
        void shouldFindATestMarkerOnTheRemovedSideOfAHunk() {
            PracticePrecondition subject = new PracticePrecondition(
                    "the change touches no test file and neither adds nor removes a test declaration",
                    List.of(
                            PracticePreconditionClause.changedPathMatches(List.of("**/*test*", "**/*Test*")),
                            PracticePreconditionClause.diffContains(List.of("#[test]", "@Test"))));

            PracticePreconditionCheck check = evaluate(
                    subject,
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(
                            Set.of("src/lib.rs"),
                            "diff --git a/src/lib.rs b/src/lib.rs\n@@ -1,4 +1,1 @@\n-#[test]\n-fn works() {}\n"));

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses())
                    .extracting(clause -> clause.result())
                    .containsExactly(PracticePreconditionResult.NOT_FOUND, PracticePreconditionResult.FOUND);
        }

        /** The change is read only when a clause asks about it, and only the aspect the clause names. */
        @Test
        void shouldReadOnlyTheAspectAClauseNames() {
            ReviewChange pathsOnly = new ReviewChange() {
                @Override
                public Set<String> changedPaths() {
                    return Set.of("pom.xml");
                }

                @Override
                public String text() {
                    throw new AssertionError("a path clause must not read the diff text");
                }
            };

            PracticePreconditionCheck check = evaluate(
                    dependencySubject(),
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    pathsOnly);

            assertThat(check.clauses().getFirst().result()).isEqualTo(PracticePreconditionResult.FOUND);
        }

        @Test
        void shouldWithholdThePracticeWhenANamedCollectionIsEmpty() {
            PracticePreconditionCheck check = evaluate(
                    threadSubject(),
                    manifestWith(availableThreads(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    Map.of(
                            THREADS_PATH,
                            "{\"threads\":[],\"reviewDecisions\":[{\"state\":\"APPROVED\"}],\"truncated\":false}"
                                    .getBytes(StandardCharsets.UTF_8)),
                    null);

            assertThat(check.absent()).isTrue();
        }

        @Test
        void shouldAskThePracticeWhenANamedCollectionHasEntries() {
            PracticePreconditionCheck check = evaluate(
                    threadSubject(),
                    manifestWith(availableThreads(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    Map.of(
                            THREADS_PATH,
                            "{\"threads\":[{\"path\":\"a.java\",\"state\":\"UNRESOLVED\"}]}"
                                    .getBytes(StandardCharsets.UTF_8)),
                    null);

            assertThat(check.absent()).isFalse();
        }

        /** A source captured whole and holding nothing settles its clause without reading anything. */
        @Test
        void shouldWithholdThePracticeWhenTheWholeSourceCapturedEmpty() {
            PracticePreconditionCheck check = evaluate(
                    threadSubject(),
                    manifestWith(availableThreads(SourceCompleteness.COMPLETE, SourceContentState.EMPTY)),
                    Map.of(),
                    null);

            assertThat(check.absent()).isTrue();
        }

        @Test
        void shouldWithholdThePracticeWhenTheWholeChangeCapturedEmptyWithoutReadingIt() {
            ReviewChange unreadable = new ReviewChange() {
                @Override
                public Set<String> changedPaths() {
                    throw new AssertionError("an empty change is settled by the manifest alone");
                }

                @Override
                public String text() {
                    throw new AssertionError("an empty change is settled by the manifest alone");
                }
            };

            PracticePreconditionCheck check = evaluate(
                    dependencySubject(),
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.EMPTY)),
                    unreadable);

            assertThat(check.absent()).isTrue();
        }

        @Test
        void shouldApplyEveryPracticeThatDeclaresNoSubject() {
            JobFolderIndex manifest =
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY));

            assertThat(evaluator.evaluate(null, manifest, Map.of(), TWO_FILE_CHANGE))
                    .isNull();
        }
    }

    @Nested
    class GlobVocabulary {

        @Test
        void shouldMatchARootFileFromALeadingDoubleStar() {
            assertThat(PracticePreconditionEvaluator.globToPattern("**/pom.xml")
                            .matcher("pom.xml")
                            .matches())
                    .isTrue();
            assertThat(PracticePreconditionEvaluator.globToPattern("**/pom.xml")
                            .matcher("server/pom.xml")
                            .matches())
                    .isTrue();
        }

        @Test
        void shouldNotLetASingleStarCrossADirectoryBoundary() {
            assertThat(PracticePreconditionEvaluator.globToPattern("*.json")
                            .matcher("a/b.json")
                            .matches())
                    .isFalse();
            assertThat(PracticePreconditionEvaluator.globToPattern("*.json")
                            .matcher("b.json")
                            .matches())
                    .isTrue();
        }

        /**
         * A glob is not a regular expression here: characters a regex would read as syntax are literal,
         * so a path with a bracket or a dot in it cannot quietly widen somebody's declaration.
         */
        @Test
        void shouldTreatRegexSyntaxAsLiteralText() {
            assertThat(PracticePreconditionEvaluator.globToPattern("**/a.b")
                            .matcher("x/axb")
                            .matches())
                    .isFalse();
            assertThat(PracticePreconditionEvaluator.globToPattern("**/a.b")
                            .matcher("x/a.b")
                            .matches())
                    .isTrue();
            assertThat(PracticePreconditionEvaluator.globToPattern("**/[id].ts")
                            .matcher("app/[id].ts")
                            .matches())
                    .isTrue();
        }

        @Test
        void shouldMatchEveryPathUnderADoubleStarDirectory() {
            assertThat(PracticePreconditionEvaluator.globToPattern("**/vendor/**")
                            .matcher("a/vendor/b/c.go")
                            .matches())
                    .isTrue();
            assertThat(PracticePreconditionEvaluator.globToPattern("**/vendor/**")
                            .matcher("vendor/c.go")
                            .matches())
                    .isTrue();
            assertThat(PracticePreconditionEvaluator.globToPattern("**/vendor/**")
                            .matcher("a/vendored.go")
                            .matches())
                    .isFalse();
        }
    }

    private PracticePreconditionCheck evaluate(
            PracticePrecondition subject, JobFolderIndex manifest, @Nullable ReviewChange change) {
        return evaluate(subject, manifest, Map.of(), change);
    }

    private PracticePreconditionCheck evaluate(
            PracticePrecondition subject,
            JobFolderIndex manifest,
            Map<String, byte[]> staged,
            @Nullable ReviewChange change) {
        PracticePreconditionCheck check = evaluator.evaluate(subject, manifest, staged, change);
        assertThat(check).isNotNull();
        return check;
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

    private static PracticePrecondition dependencySubject() {
        return new PracticePrecondition(
                "the change touches no dependency manifest or lockfile",
                List.of(PracticePreconditionClause.changedPathMatches(List.of("**/pom.xml", "**/package.json"))));
    }

    private static PracticePrecondition threadSubject() {
        return new PracticePrecondition(
                "nobody left a review comment on this pull request",
                List.of(PracticePreconditionClause.evidenceHasItems(EvidenceCollection.SCM_REVIEW_THREADS)));
    }

    private static SourceCapture availableDiff(SourceCompleteness completeness, SourceContentState content) {
        return new SourceCapture(
                DIFF,
                new SourceCaptureState.Available(content, completeness, facts(), List.of()),
                List.of(new SourceArtifact(CHANGE_PATH, "application/json", sha(), 1)));
    }

    private static SourceCapture availableThreads(SourceCompleteness completeness, SourceContentState content) {
        return new SourceCapture(
                THREADS,
                new SourceCaptureState.Available(content, completeness, facts(), List.of()),
                List.of(new SourceArtifact(THREADS_PATH, "application/json", sha(), 1)));
    }

    private static SourceCaptureFacts facts() {
        return new SourceCaptureFacts(Instant.EPOCH, null, null, null);
    }

    private static String sha() {
        return "0".repeat(64);
    }

    private static JobFolderIndex manifestWith(SourceCapture... captures) {
        return new JobFolderIndex(
                new SourceContractVersion("1.3.0"),
                "0".repeat(64),
                "scm.pull_request",
                Instant.EPOCH,
                List.of(captures));
    }
}
