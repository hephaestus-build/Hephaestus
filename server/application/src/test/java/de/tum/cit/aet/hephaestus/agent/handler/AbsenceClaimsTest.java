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
                        "The value is never validated elsewhere."))
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
                        "The diff adds no test for the new branch."))
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
