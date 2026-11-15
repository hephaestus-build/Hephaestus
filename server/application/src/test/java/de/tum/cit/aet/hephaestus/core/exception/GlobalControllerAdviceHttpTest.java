package de.tum.cit.aet.hephaestus.core.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.workspace.WorkspaceControllerAdvice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag("unit")
class GlobalControllerAdviceHttpTest {
    @Test
    void shouldWriteValidationErrorsAsTopLevelProblemExtensions() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RefusingController())
                .setControllerAdvice(new GlobalControllerAdvice())
                .build();

        mvc.perform(post("/validated").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.value[0]").exists())
                .andExpect(jsonPath("$.properties").doesNotExist());
    }

    record ValidationInputDTO(@NotBlank String value) {}

    @ParameterizedTest
    @ValueSource(strings = {"optimistic", "pessimistic"})
    void shouldReturnARetryableConflictWithoutDatabaseDetailsWhenAConcurrentWriteWins(String kind) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RefusingController())
                .setControllerAdvice(new GlobalControllerAdvice())
                .build();

        mvc.perform(get("/concurrent/{kind}", kind))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Concurrent change"))
                .andExpect(jsonPath("$.detail").value("Another request is changing this data. Reload and try again."));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 409, 429})
    void shouldPreserveProblemAndHeadersWhenFrameworkErrorIsThrown(int statusCode) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RefusingController())
                .setControllerAdvice(new GlobalControllerAdvice())
                .build();
        var response = mvc.perform(get("/refused/{status}", statusCode))
                .andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(statusCode))
                .andExpect(jsonPath("$.detail").value("This review action is not available."))
                .andExpect(jsonPath("$.reason").value("ACTION_REFUSED"));
        if (statusCode == 429) response.andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));
    }

    /**
     * The workspace advice runs first for every controller, so both advices take part: a handler for a generic type
     * there would take each {@link InvalidInputException} before the global advice could return its message.
     */
    @Test
    void shouldReturnTheMessageOfAnInvalidInputButNotOfAGenericArgumentError() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RefusingController())
                .setControllerAdvice(new WorkspaceControllerAdvice(), new GlobalControllerAdvice())
                .build();

        mvc.perform(get("/input"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("Choose a supported item kind."));
        mvc.perform(get("/argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("We could not accept this request. Check it and try again."))
                .andExpect(content().string(not(containsString("internal.example"))));
        mvc.perform(get("/state")).andExpect(content().string(not(containsString("internal.example"))));
    }

    @RestController
    static class RefusingController {
        @GetMapping("/input")
        void input() {
            throw new InvalidInputException("Choose a supported item kind.");
        }

        @GetMapping("/argument")
        void argument() {
            throw new IllegalArgumentException("https://internal.example/x id=42");
        }

        @GetMapping("/state")
        void state() {
            throw new IllegalStateException("https://internal.example/x id=42");
        }

        @PostMapping("/validated")
        ValidationInputDTO validate(@Valid @RequestBody ValidationInputDTO input) {
            return input;
        }

        @GetMapping("/concurrent/{kind}")
        void concurrent(@PathVariable String kind) {
            if (kind.equals("optimistic")) {
                throw new OptimisticLockingFailureException("private-row private-statement");
            }
            throw new PessimisticLockingFailureException("private-row private-statement");
        }

        @GetMapping("/refused/{status}")
        void refuse(@PathVariable int status) {
            var body = ProblemDetail.forStatusAndDetail(
                    HttpStatus.valueOf(status), "This review action is not available.");
            body.setProperty("reason", "ACTION_REFUSED");
            var exception = new ErrorResponseException(HttpStatus.valueOf(status), body, null);
            if (status == 429) exception.getHeaders().set(HttpHeaders.RETRY_AFTER, "60");
            throw exception;
        }
    }
}
