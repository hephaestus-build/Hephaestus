package de.tum.cit.aet.hephaestus.integration.scm.github.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.web.reactive.function.client.WebClient;

/** GitHub's throttle signal must reach the classification through the GraphQL transport, headers included. */
@Tag("unit")
class GitHubGraphQlThrottleTransportTest {

    private final MockWebServer github = new MockWebServer();
    private final GitHubExceptionClassifier classifier = new GitHubExceptionClassifier(new SimpleMeterRegistry());

    @BeforeEach
    void start() throws Exception {
        github.start();
    }

    @AfterEach
    void stop() {
        github.close();
    }

    private GitHubExceptionClassifier.ClassificationResult classifyAnswer(MockResponse answer) {
        github.enqueue(answer);
        var client = HttpGraphQlClient.create(WebClient.builder()
                .baseUrl(github.url("/graphql").toString())
                .filter(GitHubGraphQlConfig.retryFilter())
                .build());
        Throwable thrown = catchThrowable(
                () -> client.document("query { viewer { login } }").execute().block(Duration.ofSeconds(10)));
        assertThat(github.getRequestCount())
                .as("the transport hands a throttle back at once instead of retrying it blindly")
                .isEqualTo(1);
        return classifier.classifyWithDetails(thrown);
    }

    @Test
    void shouldKeepTheResetOfA429ForTheCaller() {
        Instant reset = Instant.now().plus(Duration.ofMinutes(40)).truncatedTo(ChronoUnit.SECONDS);

        var classification = classifyAnswer(new MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "2400")
                .addHeader("X-RateLimit-Remaining", "0")
                .addHeader("X-RateLimit-Reset", Long.toString(reset.getEpochSecond()))
                .body("{\"message\":\"API rate limit exceeded\"}")
                .build());

        assertThat(classification.category()).isEqualTo(Category.RATE_LIMITED);
        assertThat(classification.rateLimitResetAt()).isEqualTo(reset);
    }

    @Test
    void shouldReadASecondaryLimit403AsAThrottleWithItsRetryAfter() {
        var classification = classifyAnswer(new MockResponse.Builder()
                .code(403)
                .addHeader("Retry-After", "600")
                .body("{\"message\":\"You have exceeded a secondary rate limit. Please wait a few minutes.\"}")
                .build());

        assertThat(classification.category()).isEqualTo(Category.RATE_LIMITED);
        assertThat(classification.rateLimitResetAt()).isAfter(Instant.now().plus(Duration.ofMinutes(9)));
    }
}
