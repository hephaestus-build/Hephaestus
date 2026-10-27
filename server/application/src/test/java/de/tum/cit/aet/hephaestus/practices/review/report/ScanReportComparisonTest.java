package de.tum.cit.aet.hephaestus.practices.review.report;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.spi.ClaimedCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.Comparison;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.practices.spi.StateBasis;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ScanReportComparisonTest extends BaseUnitTest {
    final ProblemComparison comparison = new ProblemComparison(new JsonMapper());

    ProblemCandidate problem(String quote, int start, int end) {
        return new ProblemCandidate(
                0,
                null,
                Severity.MAJOR,
                "A problem",
                "An explanation",
                List.of(new EvidenceCitation(
                        new SourceKind("scm.repository.tree"),
                        "inputs/sources/tree",
                        "src/File.java",
                        null,
                        "commit",
                        start,
                        end,
                        quote,
                        null)),
                new StateBasis("The file has this problem."),
                "Correct the problem.");
    }

    @Test
    void shiftedLinesAndLineEndingsKeepIdentityButInteriorWhitespaceDoesNot() {
        assertThat(comparison.identity(1, 2, problem("  first();\r\n\tsecond(); ", 1, 2), "src/File.java", 0))
                .isEqualTo(comparison.identity(1, 2, problem("first();\nsecond();", 30, 31), "src/File.java", 0));
        assertThat(comparison.identity(1, 2, problem("first(  x );", 1, 1), "src/File.java", 0))
                .isNotEqualTo(comparison.identity(1, 2, problem("first(x);", 1, 1), "src/File.java", 0));
    }

    @Test
    void repeatedSameQuotesHaveDifferentOrdinalsAndItemsDoNotCorrelate() {
        var problem = problem("call();", 1, 1);
        assertThat(comparison.identity(1, 2, problem, "src/File.java", 0))
                .isNotEqualTo(comparison.identity(1, 2, problem, "src/File.java", 1));
        assertThat(comparison.identity(1, 2, problem, "src/File.java", 0))
                .isNotEqualTo(comparison.identity(1, 3, problem, "src/File.java", 0));
    }

    @Test
    void contentDigestIgnoresLineOffsetsButKeepsExplanationAndQuoteText() {
        assertThat(comparison.content(problem("call();", 1, 1)))
                .isEqualTo(comparison.content(problem("call();", 40, 40)));
        assertThat(comparison.content(problem("call();", 1, 1)))
                .isNotEqualTo(comparison.content(problem("other();", 1, 1)));
    }

    @Test
    void knownStandardsAndMethodsDetermineComparability() {
        var standard = new ProblemComparison.Standard("selector", "standard", "model", 1);
        assertThat(ProblemComparison.comparability(null, standard, true)).isEqualTo(Comparison.FIRST_SCAN);
        assertThat(ProblemComparison.comparability(standard, standard, true)).isEqualTo(Comparison.COMPARABLE);
        assertThat(ProblemComparison.comparability(
                        standard, new ProblemComparison.Standard("selector", "changed", "model", 1), true))
                .isEqualTo(Comparison.STANDARD_CHANGED);
        assertThat(ProblemComparison.comparability(
                        standard, new ProblemComparison.Standard("selector", "standard", "other-model", 1), true))
                .isEqualTo(Comparison.METHOD_CHANGED);
        assertThat(ProblemComparison.comparability(standard, standard, false)).isEqualTo(Comparison.INCOMPLETE);
    }

    @Test
    void normalizationPreservesEmptyLinesAndInteriorSpacing() {
        assertThat(ProblemComparison.normalize(" \tcall(  x ); \r\n\r\n")).isEqualTo("call(  x );\n\n");
    }

    ResultCandidate candidate(String version, ResultOutcome outcome, ResultCoverage coverage) {
        return new ResultCandidate(
                1,
                1,
                2,
                version,
                null,
                outcome,
                "Recorded result",
                "The file supports this result.",
                new ResultEvidence(List.of(), null, null, List.of()),
                null,
                coverage,
                outcome == ResultOutcome.NOT_MET ? List.of(problem("call();", 1, 1)) : List.of());
    }

    @Test
    void absenceNeedsAChangedVersionAndCompleteNewProof() {
        var host = new HostCoverage(
                true,
                List.of(),
                1,
                0,
                0,
                List.of(new ReadReceipt(
                        new SourceKind("scm.repository.tree"),
                        "inputs/tree",
                        "src/File.java",
                        "new",
                        ReadReceiptStatus.COMPLETE,
                        "a".repeat(64),
                        1,
                        1,
                        null)));
        var old = candidate("old", ResultOutcome.NOT_MET, host);
        var current = candidate("new", ResultOutcome.MET, host);
        assertThat(ProblemComparison.provesAbsence(old, current, true, true, false, true))
                .isTrue();
        assertThat(ProblemComparison.provesAbsence(
                        old, candidate("old", ResultOutcome.MET, host), true, true, false, true))
                .isFalse();
        assertThat(ProblemComparison.provesAbsence(old, current, true, false, false, true))
                .isFalse();
        assertThat(ProblemComparison.provesAbsence(old, current, true, true, true, true))
                .isFalse();
        assertThat(ProblemComparison.provesAbsence(old, current, true, true, false, false))
                .isFalse();
        assertThat(ProblemComparison.provesAbsence(old, current, false, true, false, true))
                .isFalse();
        assertThat(ProblemComparison.provesAbsence(
                        old, candidate("new", ResultOutcome.COULD_NOT_DETERMINE, host), true, true, false, true))
                .isFalse();
        assertThat(ProblemComparison.provesAbsence(
                        old, candidate("new", ResultOutcome.DOES_NOT_APPLY, host), true, true, false, true))
                .isFalse();
        assertThat(ProblemComparison.provesAbsence(
                        old,
                        candidate("new", ResultOutcome.MET, new ClaimedCoverage(true, List.of(), 1, 1, 1)),
                        true,
                        true,
                        false,
                        true))
                .isFalse();
    }
}
