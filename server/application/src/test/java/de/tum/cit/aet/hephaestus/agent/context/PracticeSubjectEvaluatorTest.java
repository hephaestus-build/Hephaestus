package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.PracticeSubjectCheck;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceArtifact;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SubjectAspect;
import de.tum.cit.aet.hephaestus.evidence.SubjectEvidenceCollection;
import de.tum.cit.aet.hephaestus.evidence.SubjectFinding;
import de.tum.cit.aet.hephaestus.practices.PracticeSubject;
import de.tum.cit.aet.hephaestus.practices.PracticeSubjectClause;
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
class PracticeSubjectEvaluatorTest extends BaseUnitTest {

    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind THREADS = new SourceKind("scm.review-threads");
    private static final String CHANGE_PATH = "inputs/context/change.json";
    private static final String THREADS_PATH = "inputs/context/review_threads.json";

    /** Two files, neither a dependency manifest nor a test. */
    private static final ReviewChange TWO_FILE_CHANGE =
            change(Set.of("src/App.java", "docs/readme.md"), "+int answer = 42;\n+a line\n");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PracticeSubjectEvaluator evaluator = new PracticeSubjectEvaluator(mapper);

    @Nested
    @DisplayName("A subject may never hide a finding")
    class NeverHidesAFinding {

        /**
         * The rule stated as a test. Every other case in this class is one way of reaching it: unless the
         * evaluator can point at a capture complete enough to settle the question, the practice runs.
         */
        @Test
        void shouldRunThePracticeWhenAClauseCannotBeDecided() {
            // A diff captured only in part. Nothing in it matches, but "nothing in the part I read"
            // is not "nothing", and answering as though it were is the one failure that matters here.
            PracticeSubjectCheck check = evaluate(
                    dependencySubject(),
                    manifestWith(availableDiff(SourceCompleteness.PARTIAL, SourceContentState.NON_EMPTY)),
                    TWO_FILE_CHANGE);

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses())
                    .extracting(clause -> clause.finding())
                    .containsExactly(SubjectFinding.UNDECIDABLE);
        }

        @Test
        void shouldRunThePracticeWhenTheSourceWasNeverCaptured() {
            PracticeSubjectCheck check = evaluate(
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
            PracticeSubject subject = new PracticeSubject(
                    "the change touches no dependency manifest and adds no test",
                    List.of(
                            PracticeSubjectClause.changedPathMatches(List.of("**/pom.xml")),
                            PracticeSubjectClause.diffContains(List.of("@Test"))));

            PracticeSubjectCheck check = evaluate(
                    subject,
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    null);

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses())
                    .extracting(clause -> clause.finding())
                    .containsExactly(SubjectFinding.UNDECIDABLE, SubjectFinding.UNDECIDABLE);
        }

        @Test
        void shouldRunThePracticeWhenAnEvidenceFileWillNotParse() {
            PracticeSubjectCheck check = evaluate(
                    threadSubject(),
                    manifestWith(availableThreads(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    Map.of(THREADS_PATH, "{not json".getBytes(StandardCharsets.UTF_8)),
                    null);

            assertThat(check.absent()).isFalse();
        }

        /** The manifest says the threads are there and whole, and staging does not hold them. */
        @Test
        void shouldRunThePracticeWhenTheStagedEvidenceBytesAreMissing() {
            PracticeSubjectCheck check = evaluate(
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
            PracticeSubject subject = new PracticeSubject(
                    "the change touches no test file and holds no test marker",
                    List.of(
                            PracticeSubjectClause.changedPathMatches(List.of("**/*Test*")),
                            PracticeSubjectClause.evidenceHasItems(SubjectEvidenceCollection.SCM_REVIEW_THREADS)));

            PracticeSubjectCheck check = evaluate(
                    subject,
                    manifestWith(
                            availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY),
                            availableThreads(SourceCompleteness.PARTIAL, SourceContentState.NON_EMPTY)),
                    TWO_FILE_CHANGE);

            assertThat(check.clauses())
                    .extracting(clause -> clause.finding())
                    .containsExactly(SubjectFinding.NOT_FOUND, SubjectFinding.UNDECIDABLE);
            assertThat(check.absent()).isFalse();
        }
    }

    @Nested
    class DecidesWhatItCan {

        @Test
        void shouldWithholdThePracticeWhenNoChangedPathMatches() {
            PracticeSubjectCheck check = evaluate(
                    dependencySubject(),
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    TWO_FILE_CHANGE);

            assertThat(check.absent()).isTrue();
            assertThat(check.describedAs()).isEqualTo("the change touches no dependency manifest or lockfile");
            assertThat(check.clauses().getFirst().aspect()).isEqualTo(SubjectAspect.CHANGED_PATH);
            assertThat(check.clauses().getFirst().readFrom()).isEqualTo(DIFF);
        }

        @Test
        void shouldAskThePracticeWhenAChangedPathMatches() {
            PracticeSubjectCheck check = evaluate(
                    dependencySubject(),
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(Set.of("src/App.java", "docs/readme.md", "pom.xml"), "+<dependency/>\n"));

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses().getFirst().finding()).isEqualTo(SubjectFinding.FOUND);
        }

        /**
         * The old path of a rename counts. A pull request that renames the last test file away is exactly
         * the change the test-suite practice exists to look at, and the change lists both of its names.
         */
        @Test
        void shouldReadBothSidesOfARename() {
            PracticeSubjectCheck check = evaluate(
                    new PracticeSubject(
                            "no test file", List.of(PracticeSubjectClause.changedPathMatches(List.of("**/*Test*")))),
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(Set.of("src/CalculatorTest.java", "src/Calculator.java"), "+x\n"));

            assertThat(check.absent()).isFalse();
        }

        /** Removing the last test in a source file leaves no test-named path, only a removed marker. */
        @Test
        void shouldFindATestMarkerOnTheRemovedSideOfAHunk() {
            PracticeSubject subject = new PracticeSubject(
                    "the change touches no test file and neither adds nor removes a test declaration",
                    List.of(
                            PracticeSubjectClause.changedPathMatches(List.of("**/*test*", "**/*Test*")),
                            PracticeSubjectClause.diffContains(List.of("#[test]", "@Test"))));

            PracticeSubjectCheck check = evaluate(
                    subject,
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    change(
                            Set.of("src/lib.rs"),
                            "diff --git a/src/lib.rs b/src/lib.rs\n@@ -1,4 +1,1 @@\n-#[test]\n-fn works() {}\n"));

            assertThat(check.absent()).isFalse();
            assertThat(check.clauses())
                    .extracting(clause -> clause.finding())
                    .containsExactly(SubjectFinding.NOT_FOUND, SubjectFinding.FOUND);
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

            PracticeSubjectCheck check = evaluate(
                    dependencySubject(),
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY)),
                    pathsOnly);

            assertThat(check.clauses().getFirst().finding()).isEqualTo(SubjectFinding.FOUND);
        }

        @Test
        void shouldWithholdThePracticeWhenANamedCollectionIsEmpty() {
            PracticeSubjectCheck check = evaluate(
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
            PracticeSubjectCheck check = evaluate(
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
            PracticeSubjectCheck check = evaluate(
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

            PracticeSubjectCheck check = evaluate(
                    dependencySubject(),
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.EMPTY)),
                    unreadable);

            assertThat(check.absent()).isTrue();
        }

        @Test
        void shouldApplyEveryPracticeThatDeclaresNoSubject() {
            ArtifactSourceManifest manifest =
                    manifestWith(availableDiff(SourceCompleteness.COMPLETE, SourceContentState.NON_EMPTY));

            assertThat(evaluator.evaluate(null, manifest, Map.of(), TWO_FILE_CHANGE))
                    .isNull();
        }
    }

    @Nested
    class GlobVocabulary {

        @Test
        void shouldMatchARootFileFromALeadingDoubleStar() {
            assertThat(PracticeSubjectEvaluator.globToPattern("**/pom.xml")
                            .matcher("pom.xml")
                            .matches())
                    .isTrue();
            assertThat(PracticeSubjectEvaluator.globToPattern("**/pom.xml")
                            .matcher("server/pom.xml")
                            .matches())
                    .isTrue();
        }

        @Test
        void shouldNotLetASingleStarCrossADirectoryBoundary() {
            assertThat(PracticeSubjectEvaluator.globToPattern("*.json")
                            .matcher("a/b.json")
                            .matches())
                    .isFalse();
            assertThat(PracticeSubjectEvaluator.globToPattern("*.json")
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
            assertThat(PracticeSubjectEvaluator.globToPattern("**/a.b")
                            .matcher("x/axb")
                            .matches())
                    .isFalse();
            assertThat(PracticeSubjectEvaluator.globToPattern("**/a.b")
                            .matcher("x/a.b")
                            .matches())
                    .isTrue();
            assertThat(PracticeSubjectEvaluator.globToPattern("**/[id].ts")
                            .matcher("app/[id].ts")
                            .matches())
                    .isTrue();
        }

        @Test
        void shouldMatchEveryPathUnderADoubleStarDirectory() {
            assertThat(PracticeSubjectEvaluator.globToPattern("**/vendor/**")
                            .matcher("a/vendor/b/c.go")
                            .matches())
                    .isTrue();
            assertThat(PracticeSubjectEvaluator.globToPattern("**/vendor/**")
                            .matcher("vendor/c.go")
                            .matches())
                    .isTrue();
            assertThat(PracticeSubjectEvaluator.globToPattern("**/vendor/**")
                            .matcher("a/vendored.go")
                            .matches())
                    .isFalse();
        }
    }

    private PracticeSubjectCheck evaluate(
            PracticeSubject subject, ArtifactSourceManifest manifest, @Nullable ReviewChange change) {
        return evaluate(subject, manifest, Map.of(), change);
    }

    private PracticeSubjectCheck evaluate(
            PracticeSubject subject,
            ArtifactSourceManifest manifest,
            Map<String, byte[]> staged,
            @Nullable ReviewChange change) {
        PracticeSubjectCheck check = evaluator.evaluate(subject, manifest, staged, change);
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

    private static PracticeSubject dependencySubject() {
        return new PracticeSubject(
                "the change touches no dependency manifest or lockfile",
                List.of(PracticeSubjectClause.changedPathMatches(List.of("**/pom.xml", "**/package.json"))));
    }

    private static PracticeSubject threadSubject() {
        return new PracticeSubject(
                "nobody left a review comment on this pull request",
                List.of(PracticeSubjectClause.evidenceHasItems(SubjectEvidenceCollection.SCM_REVIEW_THREADS)));
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

    private static ArtifactSourceManifest manifestWith(SourceCapture... captures) {
        return new ArtifactSourceManifest(
                new SourceContractVersion("1.2.0"),
                "0".repeat(64),
                "scm.pull_request",
                Instant.EPOCH,
                List.of(captures));
    }
}
