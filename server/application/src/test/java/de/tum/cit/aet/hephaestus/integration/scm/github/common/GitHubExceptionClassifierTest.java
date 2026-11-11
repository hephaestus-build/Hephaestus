package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationSuspendedException;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlTestResponses;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
    class ConcurrentWriteClassification {
        static Stream<Throwable> conflicts() {
            return Stream.of(
                    new ObjectOptimisticLockingFailureException("repository", 7L),
                    new PessimisticLockingFailureException("private-sql-canary"),
                    new PessimisticLockingFailureException(
                            "private-sql-canary", new SQLException("private-row-canary", "55P03")),
                    new RuntimeException(new PessimisticLockingFailureException(
                            "private-sql-canary", new SQLException("private-row-canary", "55P03"))),
                    new CompletionException(
                            new RuntimeException(new ObjectOptimisticLockingFailureException("repository", 7L))));
        }

        @ParameterizedTest
        @MethodSource("conflicts")
        void shouldRetryATypedConcurrentWriteWithoutRepeatingItsPrivateText(Throwable conflict) {
            ClassificationResult result = classifier.classifyWithDetails(conflict);

            assertThat(result.category()).isEqualTo(Category.RETRYABLE);
            assertThat(result.message()).startsWith("Concurrent database write - will retry: ");
            assertThat(result.message()).doesNotContain("canary", "repository", "55P03");
        }

        @Test
        void shouldKeepSuspendedInstallationRefusalAheadOfConcurrentWriteRetry() {
            var conflict = new PessimisticLockingFailureException(
                    "private-sql-canary", new InstallationSuspendedException(42L));

            assertThat(classifier.classify(conflict)).isEqualTo(Category.AUTH_ERROR);
        }
    }

    @Nested
    class GraphQlResponseClassification {

        @ParameterizedTest
        @CsvSource({
            "NOT_FOUND, NOT_FOUND",
            "FORBIDDEN, AUTH_ERROR",
            "UNAUTHORIZED, AUTH_ERROR",
            "RATE_LIMITED, RATE_LIMITED",
            "RESOURCE_LIMITS_EXCEEDED, CLIENT_ERROR",
            "SOMETHING_NEW, UNKNOWN"
        })
        void shouldClassifyErrorsWhenGitHubUsesNativeTypes(String type, Category expected) {
            var response = GitHubGraphQlTestResponses.decode("""
                    {"data":{"repository":null},"errors":[{"type":"%s","path":["repository"],"message":"Synthetic provider diagnostic"}]}
                    """.formatted(type));
            var result = classifier.classifyGraphQlResponse(response);
            assertNotNull(result);
            assertThat(result.category()).isEqualTo(expected);
            assertThat(result.message()).doesNotContain("Synthetic provider diagnostic");
        }

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

    /** Sync services log the summary at WARN and ERROR, so it must name the failure without quoting its inputs. */
    @Nested
    class PrivateTextNeverReachesTheSummary {

        @Test
        void shouldLogABoundedServerDiagnosticWhenTheGraphQlTypeIsUnknown() {
            var response = GitHubGraphQlTestResponses.decode("""
                    {"errors":[{"type":"SOMETHING_NEW","message":"Provider detail\\n%sTAIL_CANARY"}]}
                    """.formatted("x".repeat(1500)));
            List<String> logged = debugLines(() -> {
                var result = classifier.classifyGraphQlResponse(response);
                assertNotNull(result);
                assertThat(result.message()).doesNotContain("Provider detail");
            });
            assertThat(logged).anyMatch(line -> line.contains("message=Provider detail_") && line.length() < 1200);
            assertThat(logged).noneMatch(line -> line.contains("TAIL_CANARY") || line.contains("\n"));
        }

        static Stream<Arguments> failures() {
            return Stream.of(
                    Arguments.of(httpFailure(404), Category.NOT_FOUND, "HTTP 404"),
                    Arguments.of(httpFailure(503), Category.RETRYABLE, "HTTP 503"),
                    Arguments.of(httpFailure(429), Category.RATE_LIMITED, "HTTP 429"),
                    Arguments.of(
                            new IOException("read private-io-canary"), Category.RETRYABLE, "IO error: IOException"),
                    Arguments.of(
                            new FieldAccessException(
                                    "Could not resolve to a User with the login of 'private-login-canary'."),
                            Category.NOT_FOUND,
                            "GraphQL resource not found"),
                    Arguments.of(
                            new RuntimeException(new DataIntegrityViolationException(
                                    "Detail: Key (email)=(private-email-canary@example.test) already exists.")),
                            Category.UNKNOWN,
                            "Unclassified: DataIntegrityViolationException"),
                    Arguments.of(
                            new CannotAcquireLockException("private-sql-canary"),
                            Category.RETRYABLE,
                            "Concurrent database write - will retry: CannotAcquireLockException"),
                    Arguments.of(
                            new WebClientRequestException(
                                    new ConnectException("Connection refused: private-host-canary"),
                                    HttpMethod.GET,
                                    URI.create("https://private-host-canary/graphql"),
                                    HttpHeaders.EMPTY),
                            Category.RETRYABLE,
                            "Network error: ConnectException"),
                    Arguments.of(
                            new TimeoutException("private-timeout-canary"),
                            Category.RETRYABLE,
                            "Timeout: TimeoutException"));
        }

        @ParameterizedTest
        @MethodSource("failures")
        void shouldSummariseByStatusOrClass(Throwable failure, Category category, String summary) {
            List<String> logged = debugLines(() -> {
                ClassificationResult result = classifier.classifyWithDetails(failure);
                assertThat(result.category()).isEqualTo(category);
                assertThat(result.message()).isEqualTo(summary);
            });
            assertThat(logged).isNotEmpty().noneMatch(line -> line.contains("canary"));
        }

        static Stream<Arguments> graphQlErrors() {
            return Stream.of(
                    Arguments.of("NOT_FOUND", Category.NOT_FOUND, "GraphQL NOT_FOUND"),
                    Arguments.of("FORBIDDEN", Category.AUTH_ERROR, "GraphQL FORBIDDEN"),
                    // The sync coordinator reduces the page size on this prefix.
                    Arguments.of(
                            "RESOURCE_LIMITS_EXCEEDED",
                            Category.CLIENT_ERROR,
                            "GraphQL resource limit: RESOURCE_LIMITS_EXCEEDED"),
                    Arguments.of(
                            "SOMETHING_NEW",
                            Category.UNKNOWN,
                            "Unclassified GraphQL error: type=SOMETHING_NEW, errors=1"),
                    Arguments.of(
                            "private type canary!",
                            Category.UNKNOWN,
                            "Unclassified GraphQL error: type=other, errors=1"));
        }

        @ParameterizedTest
        @MethodSource("graphQlErrors")
        void shouldSummariseGraphQlErrorsByType(String type, Category category, String summary) {
            ResponseError error = mock(ResponseError.class);
            when(error.getExtensions()).thenReturn(Map.of("type", type));
            when(error.getMessage())
                    .thenReturn("Could not resolve to a Repository with the name 'acme/private-repo-canary'.");
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.getErrors()).thenReturn(List.of(error));

            ClassificationResult result = classifier.classifyGraphQlResponse(response);

            assertNotNull(result);
            assertThat(result.category()).isEqualTo(category);
            assertThat(result.message()).isEqualTo(summary);
        }

        private static WebClientResponseException httpFailure(int status) {
            return WebClientResponseException.create(
                    status,
                    "private-status-canary",
                    HttpHeaders.EMPTY,
                    "{\"message\":\"private-body-canary\"}".getBytes(UTF_8),
                    UTF_8,
                    new MockClientHttpRequest(
                            HttpMethod.GET,
                            URI.create("https://api.github.com/repos/acme/private-repo-canary/issues")));
        }

        private static List<String> debugLines(Runnable classification) {
            Logger logger = (Logger) LoggerFactory.getLogger(GitHubExceptionClassifier.class);
            Level previous = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.DEBUG);
            try {
                classification.run();
            } finally {
                logger.setLevel(previous);
                logger.detachAppender(appender);
                appender.stop();
            }
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    /** The classifier matches Spring GraphQL's exception by simple name. */
    static class FieldAccessException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FieldAccessException(String message) {
            super(message);
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
