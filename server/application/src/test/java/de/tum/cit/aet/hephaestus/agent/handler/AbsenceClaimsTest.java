package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AbsenceClaimsTest extends BaseUnitTest {

    @Test
    void shouldFindTheSameMarkersWhenTheRunnerAsksAndAdmissionWithholds() throws IOException {
        String runner = Files.readString(Path.of("src/main/resources/agent/pi-session-reads.ts"));
        var block = Pattern.compile("export const ABSENCE_MARKERS = \\[(.*?)\\n];", Pattern.DOTALL)
                .matcher(runner);
        assertThat(block.find())
                .as("pi-session-reads.ts declares ABSENCE_MARKERS")
                .isTrue();
        List<String> markers = Pattern.compile("String\\.raw`([^`]*)`")
                .matcher(block.group(1))
                .results()
                .map(marker -> marker.group(1))
                .toList();

        assertThat(markers).containsExactlyElementsOf(AbsenceClaims.MARKERS);
        var disclaimer = Pattern.compile("export const ABSENCE_DISCLAIMER = String\\.raw`([^`]*)`;")
                .matcher(runner);
        assertThat(disclaimer.find())
                .as("pi-session-reads.ts declares ABSENCE_DISCLAIMER")
                .isTrue();
        assertThat(disclaimer.group(1)).isEqualTo(AbsenceClaims.DISCLAIMER);
        var clauseEnd = Pattern.compile("export const ABSENCE_CLAUSE_END = String\\.raw`([^`]*)`;")
                .matcher(runner);
        assertThat(clauseEnd.find())
                .as("pi-session-reads.ts declares ABSENCE_CLAUSE_END")
                .isTrue();
        assertThat(clauseEnd.group(1)).isEqualTo(AbsenceClaims.CLAUSE_END);
    }

    @Test
    void shouldFindTheClaimWhenALimitOfTheReviewStandsBesideIt() {
        assertThat(List.of(
                        "The reviewed change was inspected, and there is no handler elsewhere in the repository.",
                        "I did not search beyond the diff, but no handler exists anywhere in the repository.",
                        "page flows into subList without any check, and no exception handler exists in the repository.",
                        "page is not checked, and the checkout contains no @ControllerAdvice."))
                .allMatch(AbsenceClaims::claimed);
    }

    @Test
    void shouldFindTheClaimThatTheRepositoryLacksSomethingWhenItIsWordedAsInReviewsOf50() {
        assertThat(List.of(
                        // The stored error-handling problem of #50 (review 2435ae89).
                        "No code catches this and the reviewed checkout contains no @ControllerAdvice or @ExceptionHandler",
                        // Other wordings of the same claim.
                        "No @ControllerAdvice or @ExceptionHandler exists in the repository.",
                        "Nothing in the project catches IndexOutOfBoundsException.",
                        "The application defines no global exception handler.",
                        "There is no exception handler anywhere in the code.",
                        "None of the callers handle the null.",
                        "Nothing else in the code base validates page.",
                        "The repository has no tests for the parser.",
                        "No other file calls this function.",
                        "The value is never validated elsewhere in the code.",
                        "The codebase never validates page.",
                        // Claims about the repository that real results made in their search boundary.
                        "no generation source for it exists in the repository",
                        "no outer route auth wrapper, dev profile, or server registration exists in the checkout",
                        "Invoice.swift is referenced by no other file",
                        "no template exists in the checkout"))
                .allMatch(AbsenceClaims::claimed);
    }

    @Test
    void shouldFindNoClaimWhenATextBoundsItselfToWhatTheResultRead() {
        assertThat(List.of(
                        // The other stored results of #50.
                        "The pinned file has no @Min, clamp or other check on the path.",
                        "?page=-1 makes from = -25, so all.subList(-25, 0) throws IndexOutOfBoundsException and the"
                                + " request 500s.",
                        "No issue is referenced in the title, description, or branch, so the change isn't traceable.",
                        "The description's one word, \"paging,\" records no reason for the 25-user cap.",
                        // Results of the error-handling and crash practices about the selected lines.
                        "The new handler throws an uncaught IOException that propagates out of the controller.",
                        "The lookup returns null, so an unhandled error is silently lost.",
                        "The catch block returns a raw 500 body with the stack trace.",
                        "The application has no timeout on this HTTP client call.",
                        "No transaction boundary around the repository call.",
                        "Missing null check before the repository lookup result.",
                        "The repository method has no null check on the id.",
                        "The repository call returns Optional, and the new code calls get() without a check.",
                        "The added handler catches IOException and returns an empty list without logging it.",
                        "No error is swallowed in the selected code: both new catch blocks rethrow.",
                        "The added unwrap() on the parsed port panics on invalid input; no fallback is given.",
                        "The expect() call crashes the process on a missing env var with no message.",
                        "Without any validation, a negative page reaches subList and throws.",
                        "None of the added bodies sprawls or nests deeply.",
                        "The diff adds no test for the new branch.",
                        // Real results bounded to the change, the record or the review's own reach.
                        "no test file or test hunk appears anywhere in the change",
                        "no print-style or logger call is added anywhere in the diff",
                        "the body contains no appeal to context made elsewhere and no links",
                        "Substantive bulk-deactivation change has no traceable issue reference anywhere in the handoff",
                        "the change adds no other files, and no repository ignore rules were consulted",
                        "no other files are in the change",
                        "The change adds no diagnostic call in application code.",
                        "One cohesive user-story deliverable; no duplicate or overlapping sibling in the project"
                                + " inventory",
                        "The checkout step in the workflow has no pinned version.",
                        "No code path in handle() returns 404.",
                        "This does not establish that unchanged settings elsewhere in the repository are sound.",
                        "I did not inspect files outside the diff or establish that no other generated artifacts"
                                + " exist elsewhere in the repository.",
                        "no generated file in the rest of the checkout was examined because no changed path suggested"
                                + " machine output",
                        "No test is added anywhere.",
                        "The diff contains no tests anywhere.",
                        "The catch block never logs the error anywhere.",
                        "No code in the handler catches the error.",
                        "There is no auth setting in application.properties."))
                .noneMatch(AbsenceClaims::claimed);
    }

    @Test
    void shouldFindNoClaimWhenABundledCaseReasonsAboutWhatItCaptured() throws IOException {
        var cases = new JsonMapper().readTree(Path.of("src/test/resources/practices/criteria-cases.json"));
        var reasons = StreamSupport.stream(cases.spliterator(), false)
                .map(entry -> entry.path("reason").asString())
                .toList();

        assertThat(reasons).hasSizeGreaterThan(100).noneMatch(AbsenceClaims::claimed);
    }
}
