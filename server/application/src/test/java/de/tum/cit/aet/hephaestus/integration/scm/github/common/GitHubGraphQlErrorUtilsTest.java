package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;

@Tag("unit")
class GitHubGraphQlErrorUtilsTest {

    @Nested
    class IsNotFoundError {

        @Test
        void nullResponse() {
            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(null, "repository.issue"))
                    .isFalse();
        }

        @Test
        void noErrors() {
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(Collections.emptyList());

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isFalse();
        }

        @Test
        void notFoundErrorAtMatchingPath() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(error.getParsedPath()).thenReturn(List.of("repository", "issue"));

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isTrue();
        }

        @Test
        void notFoundErrorAtDifferentPath() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(error.getParsedPath()).thenReturn(List.of("repository", "pullRequest"));

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isFalse();
        }

        @Test
        void forbiddenErrorAtMatchingPath() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "FORBIDDEN"));
            when(error.getParsedPath()).thenReturn(List.of("repository", "issue"));

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isFalse();
        }

        @Test
        void pathPrefixMatchesBeginning() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(error.getParsedPath()).thenReturn(List.of("repository", "issue", "comments"));

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isTrue();
        }

        @Test
        @DisplayName("handles errors without extensions")
        void noExtensions() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(null);
            when(error.getParsedPath()).thenReturn(List.of("repository", "issue"));

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isFalse();
        }

        @Test
        void fallbackToStringPath() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(error.getParsedPath()).thenReturn(Collections.emptyList());
            when(error.getPath()).thenReturn("repository.issue");

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isTrue();
        }

        @Test
        void pathPrefixLongerThanActualPath() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(error.getParsedPath()).thenReturn(List.of("repository"));

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            // Error at "repository" should NOT match "repository.issue" - it's a parent path
            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isFalse();
        }

        @Test
        void nullStringPathInFallback() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(error.getParsedPath()).thenReturn(Collections.emptyList());
            when(error.getPath()).thenReturn(null);

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isFalse();
        }

        @Test
        void emptyStringPathInFallback() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(error.getParsedPath()).thenReturn(Collections.emptyList());
            when(error.getPath()).thenReturn("");

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isFalse();
        }

        @Test
        void nullParsedPath() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(error.getParsedPath()).thenReturn(null);
            when(error.getPath()).thenReturn(null);

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isFalse();
        }

        @Test
        void multipleErrorsFirstMatches() {
            ResponseError notFoundError = mock(ResponseError.class);
            when(notFoundError.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(notFoundError.getParsedPath()).thenReturn(List.of("repository", "issue"));

            ResponseError forbiddenError = mock(ResponseError.class);
            when(forbiddenError.getExtensions()).thenReturn(Map.of("type", "FORBIDDEN"));
            when(forbiddenError.getParsedPath()).thenReturn(List.of("repository", "issue"));

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(notFoundError, forbiddenError));

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isTrue();
        }

        @Test
        void multipleErrorsSecondMatches() {
            ResponseError forbiddenError = mock(ResponseError.class);
            when(forbiddenError.getExtensions()).thenReturn(Map.of("type", "FORBIDDEN"));
            when(forbiddenError.getParsedPath()).thenReturn(List.of("repository", "issue"));

            ResponseError notFoundError = mock(ResponseError.class);
            when(notFoundError.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(notFoundError.getParsedPath()).thenReturn(List.of("repository", "issue"));

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(forbiddenError, notFoundError));

            assertThat(GitHubGraphQlErrorUtils.isNotFoundError(response, "repository.issue"))
                    .isTrue();
        }
    }

    @Nested
    class HasAnyNotFoundError {

        @Test
        void anyNotFoundError() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(error.getParsedPath()).thenReturn(List.of("some", "random", "path"));

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.hasAnyNotFoundError(response)).isTrue();
        }
    }

    @Nested
    class GetNotFoundErrorMessage {

        @Test
        void nullResponse() {
            assertThat(GitHubGraphQlErrorUtils.getNotFoundErrorMessage(null)).isNull();
        }

        @Test
        void returnsMessage() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(error.getMessage()).thenReturn("Could not resolve to an Issue with the number of 7.");

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.getNotFoundErrorMessage(response))
                    .isEqualTo("Could not resolve to an Issue with the number of 7.");
        }

        @Test
        void noNotFoundError() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "FORBIDDEN"));
            when(error.getMessage()).thenReturn("Forbidden");

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.getNotFoundErrorMessage(response))
                    .isNull();
        }
    }

    @Nested
    class DetectTransientError {

        @Test
        void timeoutCouldntRespond() {
            ClientGraphQlResponse response = responseWithError("couldn't respond in time", null);
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.TIMEOUT);
        }

        @Test
        void timeoutCouldNotRespond() {
            ClientGraphQlResponse response = responseWithError("could not respond in time", null);
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.TIMEOUT);
        }

        @Test
        void timeoutSomethingWentWrong() {
            ClientGraphQlResponse response = responseWithError("Something went wrong while executing your query", null);
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.TIMEOUT);
        }

        @Test
        void timeoutTimedout() {
            ClientGraphQlResponse response = responseWithError("Request timedout", null);
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.TIMEOUT);
        }

        @Test
        void timeoutTimedOut() {
            ClientGraphQlResponse response = responseWithError("Request timed out", null);
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.TIMEOUT);
        }

        @Test
        void rateLimitExceeded() {
            ClientGraphQlResponse response = responseWithError("API rate limit exceeded", null);
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.RATE_LIMIT);
        }

        @Test
        void rateLimitOneWord() {
            ClientGraphQlResponse response = responseWithError("You have exceeded a ratelimit", null);
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.RATE_LIMIT);
        }

        @Test
        void abuseDetection() {
            ClientGraphQlResponse response = responseWithError("You have triggered an abuse detection mechanism", null);
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.RATE_LIMIT);
        }

        @Test
        void secondaryRateLimit() {
            ClientGraphQlResponse response = responseWithError("You have exceeded a secondary rate limit", null);
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.RATE_LIMIT);
        }

        @Test
        void resourceLimitsExceeded() {
            ClientGraphQlResponse response =
                    responseWithError("Query too complex", Map.of("type", "RESOURCE_LIMITS_EXCEEDED"));
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.RESOURCE_LIMIT);
        }

        @Test
        void maxNodeLimitExceeded() {
            ClientGraphQlResponse response =
                    responseWithError("Exceeded max node limit", Map.of("type", "MAX_NODE_LIMIT_EXCEEDED"));
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.RESOURCE_LIMIT);
        }

        @Test
        void internalError() {
            ClientGraphQlResponse response =
                    responseWithError("Internal server error", Map.of("type", "INTERNAL_ERROR"));
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.SERVER_ERROR);
        }

        @Test
        void serviceUnavailable() {
            ClientGraphQlResponse response =
                    responseWithError("Service unavailable", Map.of("type", "SERVICE_UNAVAILABLE"));
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.SERVER_ERROR);
        }

        @Test
        void forbiddenWithRateMessage() {
            ClientGraphQlResponse response =
                    responseWithError("rate limit exceeded for this resource", Map.of("type", "FORBIDDEN"));
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertNotNull(result);
            assertThat(result.type()).isEqualTo(GitHubGraphQlErrorUtils.TransientErrorType.RATE_LIMIT);
        }

        @Test
        void nonTransientError() {
            ClientGraphQlResponse response = responseWithError("Some other error", Map.of("type", "VALIDATION_ERROR"));
            var result = GitHubGraphQlErrorUtils.detectTransientError(response);
            assertThat(result).isNull();
        }

        @Test
        void nullResponse() {
            assertThat(GitHubGraphQlErrorUtils.detectTransientError(null)).isNull();
        }

        @Test
        void emptyErrors() {
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(Collections.emptyList());
            assertThat(GitHubGraphQlErrorUtils.detectTransientError(response)).isNull();
        }

        @Test
        void nullMessage() {
            ResponseError error = mock(ResponseError.class);
            when(error.getMessage()).thenReturn(null);
            when(error.getExtensions()).thenReturn(null);

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            assertThat(GitHubGraphQlErrorUtils.detectTransientError(response)).isNull();
        }

        /**
         * Helper to create a response with a single error having the given message and optional extensions.
         */
        private ClientGraphQlResponse responseWithError(String message, @Nullable Map<String, Object> extensions) {
            ResponseError error = mock(ResponseError.class);
            when(error.getMessage()).thenReturn(message);
            when(error.getExtensions()).thenReturn(extensions);

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));
            return response;
        }
    }

    @Nested
    class TransientErrorProperties {

        @Test
        void timeoutWait() {
            var error = new GitHubGraphQlErrorUtils.TransientError(
                    GitHubGraphQlErrorUtils.TransientErrorType.TIMEOUT, "test");
            assertThat(error.getRecommendedWait()).isEqualTo(Duration.ofSeconds(5));
        }

        @Test
        void rateLimitWait() {
            var error = new GitHubGraphQlErrorUtils.TransientError(
                    GitHubGraphQlErrorUtils.TransientErrorType.RATE_LIMIT, "test");
            assertThat(error.getRecommendedWait()).isEqualTo(Duration.ofMinutes(1));
        }

        @Test
        void serverErrorWait() {
            var error = new GitHubGraphQlErrorUtils.TransientError(
                    GitHubGraphQlErrorUtils.TransientErrorType.SERVER_ERROR, "test");
            assertThat(error.getRecommendedWait()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        void resourceLimitWait() {
            var error = new GitHubGraphQlErrorUtils.TransientError(
                    GitHubGraphQlErrorUtils.TransientErrorType.RESOURCE_LIMIT, "test");
            assertThat(error.getRecommendedWait()).isEqualTo(Duration.ofSeconds(0));
        }

        @Test
        void shouldReduceComplexity() {
            assertThat(new GitHubGraphQlErrorUtils.TransientError(
                                    GitHubGraphQlErrorUtils.TransientErrorType.RESOURCE_LIMIT, "test")
                            .shouldReduceComplexity())
                    .isTrue();
            assertThat(new GitHubGraphQlErrorUtils.TransientError(
                                    GitHubGraphQlErrorUtils.TransientErrorType.TIMEOUT, "test")
                            .shouldReduceComplexity())
                    .isFalse();
            assertThat(new GitHubGraphQlErrorUtils.TransientError(
                                    GitHubGraphQlErrorUtils.TransientErrorType.RATE_LIMIT, "test")
                            .shouldReduceComplexity())
                    .isFalse();
            assertThat(new GitHubGraphQlErrorUtils.TransientError(
                                    GitHubGraphQlErrorUtils.TransientErrorType.SERVER_ERROR, "test")
                            .shouldReduceComplexity())
                    .isFalse();
        }
    }
}
