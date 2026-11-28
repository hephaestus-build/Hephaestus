package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.practices.review.ReviewSandboxStub.Judged;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSandboxStub.Judgement;
import de.tum.cit.aet.hephaestus.practices.scan.dto.JudgedScanReportItemDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A scan that an admin requests runs end to end against a local repository: it pins the default branch, enumerates the
 * files that each practice selects, measures each file in a sandbox, admits the results, and closes with its report
 * and SARIF.
 */
@Tag("integration")
class ScanEndToEndIntegrationTest extends ScanEndToEndFixtures {

    @Test
    void shouldCloseARequestedScanCompleteWithItsFileProblemsAndSarif() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n", "src/Util.java", "class Util {}\n"));
        var practice = practice(upstream.workspace(), "clear-files", List.of("src/**"), List.of());
        sandbox.judgeFiles((slug, path) ->
                path.equals("src/Util.java") ? Judgement.notMet("Name what the helper is for") : Judgement.met());

        var scan = scan(upstream, false, practice);

        assertThat(awaitClosed(scan))
                .as("%s", sandbox.calls)
                .containsEntry("status", "COMPLETE")
                .containsEntry("stop_reason", null);
        assertThat(sandbox.judged)
                .containsExactlyInAnyOrder(
                        new Judged("clear-files", "src/Main.java"), new Judged("clear-files", "src/Util.java"));
        assertThat(items(scan))
                .hasOnlyElementsOfType(JudgedScanReportItemDTO.class)
                .map(item -> (JudgedScanReportItemDTO) item)
                .extracting(
                        item -> item.item().path(),
                        JudgedScanReportItemDTO::practiceSlug,
                        JudgedScanReportItemDTO::outcome,
                        JudgedScanReportItemDTO::problemCount)
                .containsExactlyInAnyOrder(
                        tuple("src/Main.java", "clear-files", ResultOutcome.MET, 0),
                        tuple("src/Util.java", "clear-files", ResultOutcome.NOT_MET, 1));
        assertThat(problems(scan)).singleElement().satisfies(problem -> {
            assertThat(problem.item().path()).isEqualTo("src/Util.java");
            assertThat(problem.practiceSlug()).isEqualTo("clear-files");
            assertThat(problem.problem().summary()).isEqualTo("Name what the helper is for");
        });

        var run = mapper.readTree(sarif(scan)).path("runs").path(0);
        assertThat(run.path("versionControlProvenance")
                        .path(0)
                        .path("revisionId")
                        .asString())
                .isEqualTo(upstream.commit());
        assertThat(run.path("invocations").path(0).path("executionSuccessful").asBoolean())
                .isTrue();
        assertThat(run.path("properties").path("hephaestus").path("scanId").asString())
                .isEqualTo("scanId");
        assertThat(run.path("results")).singleElement().satisfies(result -> {
            assertThat(result.path("ruleId").asString()).isEqualTo("clear-files");
            assertThat(result.path("message").path("text").asString()).isEqualTo("Name what the helper is for");
            var location = result.path("locations").path(0).path("physicalLocation");
            assertThat(location.path("artifactLocation").path("uri").asString()).isEqualTo("src/Util.java");
            assertThat(location.path("region").path("snippet").path("text").asString())
                    .isEqualTo("class Util {}");
            assertThat(result.path("properties").path("problemId").asString()).isEqualTo("problemId");
            assertThat(result.path("properties").path("resultId").asString()).isEqualTo("resultId");
        });
    }

    @Test
    void shouldReviewOnlyTheFilesThatThePracticeIncludes() throws Exception {
        var upstream = upstream(Map.of(
                "src/Main.java", "class Main {}\n",
                "src/generated/Generated.java", "class Generated {}\n",
                "docs/Guide.md", "# Guide\n"));
        var practice = practice(upstream.workspace(), "clear-sources", List.of("src/**"), List.of("src/generated/**"));
        sandbox.judgeFiles((slug, path) -> Judgement.met());

        var scan = scan(upstream, true, practice);

        assertThat(awaitClosed(scan))
                .as("%s", sandbox.calls)
                .containsEntry("status", "COMPLETE")
                .containsEntry("stop_reason", null);
        assertThat(sandbox.judged).containsExactly(new Judged("clear-sources", "src/Main.java"));
        assertThat(items(scan))
                .hasOnlyElementsOfType(JudgedScanReportItemDTO.class)
                .map(item -> (JudgedScanReportItemDTO) item)
                .extracting(item -> item.item().path(), JudgedScanReportItemDTO::outcome)
                .containsExactly(tuple("src/Main.java", ResultOutcome.MET));
        assertThat(problems(scan)).isEmpty();
        assertThat(mapper.readTree(sarif(scan)).path("runs").path(0).path("results"))
                .isEmpty();
    }

    /** The two helpers of a later commit and a cut budget: the scan pins the pushed commit and stops after one file. */
    @Test
    void shouldEndPartialForItsBudgetWhenTheFirstFileOfThePushedCommitSpentIt() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n"));
        String pushed = push(upstream, Map.of("src/Util.java", "class Util {}\n"));
        var practice = practice(upstream.workspace(), "clear-files", List.of("src/**"), List.of());
        sandbox.judgeFiles((slug, path) -> Judgement.met());

        var scan = scan(upstream, false, oneFileBudget(), practice);

        assertThat(awaitClosed(scan))
                .as("%s", sandbox.calls)
                .containsEntry("status", "PARTIAL")
                .containsEntry("stop_reason", "BUDGET");
        assertThat(jdbc.queryForObject("SELECT item_version FROM review WHERE id = ?", String.class, scan.id()))
                .isEqualTo(pushed);
        assertThat(sandbox.judged).hasSize(1);
        assertThat(items(scan))
                .map(item -> mapper.<JsonNode>valueToTree(item).path("status").asString())
                .containsExactlyInAnyOrder("JUDGED", "NOT_REVIEWED");
    }
}
