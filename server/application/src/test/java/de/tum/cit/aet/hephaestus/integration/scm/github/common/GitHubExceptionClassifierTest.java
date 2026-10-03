package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationSuspendedException;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Unit tests for {@link GitHubExceptionClassifier}.
 */
@Tag("unit")
class GitHubExceptionClassifierTest {

    private GitHubExceptionClassifier classifier;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        classifier = new GitHubExceptionClassifier(meterRegistry);
    }

    @Nested
    class HttpStatusCodeClassification {

        @Test
        void badRequest() {
            var exception = createWebClientResponseException(400, "Bad Request");

            assertThat(classifier.classify(exception)).isEqualTo(Category.CLIENT_ERROR);
        }

        @Test
        @DisplayName("classifies 401 Unauthorized as AUTH_ERROR")
        void unauthorized() {
            var exception = createWebClientResponseException(401, "Unauthorized");

            assertThat(classifier.classify(exception)).isEqualTo(Category.AUTH_ERROR);
        }

        @Test
        @DisplayName("classifies 403 Forbidden (non-rate-limit) as AUTH_ERROR")
        void forbidden() {
            var exception = createWebClientResponseException(403, "Forbidden");

            assertThat(classifier.classify(exception)).isEqualTo(Category.AUTH_ERROR);
        }

        @Test
        void forbiddenRateLimit() {
            var exception = createWebClientResponseExceptionWithBody(
                    403, "Forbidden", "{\"message\": \"API rate limit exceeded\"}");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RATE_LIMITED);
        }

        @Test
        void forbiddenRateLimitHeader() {
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-RateLimit-Remaining", "0");
            headers.add(
                    "X-RateLimit-Reset",
                    String.valueOf(Instant.now().plusSeconds(3600).getEpochSecond()));

            var exception = createWebClientResponseExceptionWithHeaders(403, "Forbidden", headers);

            ClassificationResult result = classifier.classifyWithDetails(exception);
            assertThat(result.category()).isEqualTo(Category.RATE_LIMITED);
            assertThat(result.rateLimitResetAt()).isNotNull();
        }

        @Test
        void notFound() {
            var exception = createWebClientResponseException(404, "Not Found");

            assertThat(classifier.classify(exception)).isEqualTo(Category.NOT_FOUND);
        }

        @Test
        void unprocessableEntity() {
            var exception = createWebClientResponseException(422, "Unprocessable Entity");

            assertThat(classifier.classify(exception)).isEqualTo(Category.CLIENT_ERROR);
        }

        @Test
        void tooManyRequests() {
            var exception = createWebClientResponseException(429, "Too Many Requests");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RATE_LIMITED);
        }

        @Test
        @DisplayName("classifies 429 with Retry-After header and extracts wait time")
        void tooManyRequestsWithRetryAfter() {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Retry-After", "120");

            var exception = createWebClientResponseExceptionWithHeaders(429, "Too Many Requests", headers);

            ClassificationResult result = classifier.classifyWithDetails(exception);
            assertThat(result.category()).isEqualTo(Category.RATE_LIMITED);
            assertThat(result.suggestedWait()).isNotNull();
            assertThat(result.suggestedWait().toSeconds()).isGreaterThanOrEqualTo(119);
        }

        @Test
        void internalServerError() {
            var exception = createWebClientResponseException(500, "Internal Server Error");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }

        @Test
        void badGateway() {
            var exception = createWebClientResponseException(502, "Bad Gateway");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }

        @Test
        void serviceUnavailable() {
            var exception = createWebClientResponseException(503, "Service Unavailable");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }

        @Test
        void gatewayTimeout() {
            var exception = createWebClientResponseException(504, "Gateway Timeout");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }
    }

    @Nested
    class NetworkExceptionClassification {

        @Test
        void connectException() {
            var exception = new ConnectException("Connection refused");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }

        @Test
        void socketException() {
            var exception = new SocketException("Connection reset");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }

        @Test
        void unknownHostException() {
            var exception = new UnknownHostException("api.github.com");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }

        @Test
        @DisplayName("classifies IOException as RETRYABLE")
        void ioException() {
            var exception = new IOException("Network error");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }

        @Test
        void webClientRequestException() {
            var cause = new ConnectException("Connection refused");
            var exception = mock(WebClientRequestException.class);
            when(exception.getCause()).thenReturn(cause);

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }
    }

    @Nested
    class TimeoutExceptionClassification {

        @Test
        void timeoutException() {
            var exception = new TimeoutException("Request timed out");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }

        @Test
        void socketTimeoutException() {
            var exception = new SocketTimeoutException("Read timed out");

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }
    }

    @Nested
    class WrappedExceptionClassification {

        @Test
        void unwrapsRuntimeException() {
            var cause = createWebClientResponseException(503, "Service Unavailable");
            var exception = new RuntimeException("Wrapped", cause);

            assertThat(classifier.classify(exception)).isEqualTo(Category.RETRYABLE);
        }

        @Test
        void shouldClassifyAsAuthErrorWhenSuspendedInstallationIsWrappedTwice() {
            var suspended = new InstallationSuspendedException(42L);
            var exception = new CompletionException(new RuntimeException("Wrapped", suspended));

            ClassificationResult result = classifier.classifyWithDetails(exception);

            assertThat(result.category()).isEqualTo(Category.AUTH_ERROR);
            assertThat(result.message()).contains("42");
        }

        @Test
        void unknownException() {
            var exception = new IllegalStateException("Something unexpected");

            assertThat(classifier.classify(exception)).isEqualTo(Category.UNKNOWN);
        }

        @Test
        void nullException() {
            assertThat(classifier.classify(null)).isEqualTo(Category.UNKNOWN);
        }
    }

    @Nested
    class GraphQlResponseClassification {

        @Test
        @DisplayName("returns null for response without errors")
        void noErrors() {
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(Collections.emptyList());

            assertThat(classifier.classifyGraphQlResponse(response)).isNull();
        }

        @Test
        void nullResponse() {
            assertThat(classifier.classifyGraphQlResponse(null)).isNull();
        }

        @Test
        void notFoundError() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "NOT_FOUND"));
            when(error.getMessage()).thenReturn("Could not resolve to an Issue");

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            ClassificationResult result = classifier.classifyGraphQlResponse(response);
            assertNotNull(result);
            assertThat(result.category()).isEqualTo(Category.NOT_FOUND);
        }

        @Test
        void forbiddenError() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "FORBIDDEN"));
            when(error.getMessage()).thenReturn("Resource not accessible");

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            ClassificationResult result = classifier.classifyGraphQlResponse(response);
            assertNotNull(result);
            assertThat(result.category()).isEqualTo(Category.AUTH_ERROR);
        }

        @Test
        void forbiddenRateLimitError() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "FORBIDDEN"));
            when(error.getMessage()).thenReturn("API rate limit exceeded");

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            ClassificationResult result = classifier.classifyGraphQlResponse(response);
            assertNotNull(result);
            assertThat(result.category()).isEqualTo(Category.RATE_LIMITED);
        }

        @Test
        void unauthorizedError() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", "UNAUTHORIZED"));
            when(error.getMessage()).thenReturn("Bad credentials");

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            ClassificationResult result = classifier.classifyGraphQlResponse(response);
            assertNotNull(result);
            assertThat(result.category()).isEqualTo(Category.AUTH_ERROR);
        }

        @Test
        void errorWithoutExtensions() {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(null);
            when(error.getMessage()).thenReturn("Some error");

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            ClassificationResult result = classifier.classifyGraphQlResponse(response);
            assertNotNull(result);
            assertThat(result.category()).isEqualTo(Category.UNKNOWN);
        }
    }

    @Nested
    class IsRetryable {

        @Test
        void retryable() {
            var exception = createWebClientResponseException(503, "Service Unavailable");

            assertThat(classifier.isRetryable(exception)).isTrue();
        }

        @Test
        void rateLimited() {
            var exception = createWebClientResponseException(429, "Too Many Requests");

            assertThat(classifier.isRetryable(exception)).isTrue();
        }

        @Test
        void notFound() {
            var exception = createWebClientResponseException(404, "Not Found");

            assertThat(classifier.isRetryable(exception)).isFalse();
        }

        @Test
        void authError() {
            var exception = createWebClientResponseException(401, "Unauthorized");

            assertThat(classifier.isRetryable(exception)).isFalse();
        }

        @Test
        void clientError() {
            var exception = createWebClientResponseException(400, "Bad Request");

            assertThat(classifier.isRetryable(exception)).isFalse();
        }
    }

    @Nested
    class Metrics {

        @Test
        void incrementsRetryableCounter() {
            var exception = createWebClientResponseException(503, "Service Unavailable");

            classifier.classify(exception);

            assertThat(meterRegistry
                            .counter("github.sync.errors.total", "category", "retryable")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void incrementsRateLimitedCounter() {
            var exception = createWebClientResponseException(429, "Too Many Requests");

            classifier.classify(exception);

            assertThat(meterRegistry
                            .counter("github.sync.errors.total", "category", "rate_limited")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void incrementsNotFoundCounter() {
            var exception = createWebClientResponseException(404, "Not Found");

            classifier.classify(exception);

            assertThat(meterRegistry
                            .counter("github.sync.errors.total", "category", "not_found")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void incrementsAuthErrorCounter() {
            var exception = createWebClientResponseException(401, "Unauthorized");

            classifier.classify(exception);

            assertThat(meterRegistry
                            .counter("github.sync.errors.total", "category", "auth_error")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void incrementsClientErrorCounter() {
            var exception = createWebClientResponseException(400, "Bad Request");

            classifier.classify(exception);

            assertThat(meterRegistry
                            .counter("github.sync.errors.total", "category", "client_error")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void incrementsUnknownCounter() {
            var exception = new IllegalStateException("Unknown error");

            classifier.classify(exception);

            assertThat(meterRegistry
                            .counter("github.sync.errors.total", "category", "unknown")
                            .count())
                    .isEqualTo(1.0);
        }
    }

    // Helper methods for creating test exceptions

    private WebClientResponseException createWebClientResponseException(int statusCode, String statusText) {
        return WebClientResponseException.create(statusCode, statusText, HttpHeaders.EMPTY, new byte[0], null);
    }

    private WebClientResponseException createWebClientResponseExceptionWithBody(
            int statusCode, String statusText, String body) {
        return WebClientResponseException.create(statusCode, statusText, HttpHeaders.EMPTY, body.getBytes(UTF_8), null);
    }

    private WebClientResponseException createWebClientResponseExceptionWithHeaders(
            int statusCode, String statusText, HttpHeaders headers) {
        return WebClientResponseException.create(statusCode, statusText, headers, new byte[0], null);
    }
}
