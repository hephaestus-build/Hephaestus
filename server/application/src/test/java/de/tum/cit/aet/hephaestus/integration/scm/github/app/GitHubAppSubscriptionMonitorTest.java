package de.tum.cit.aet.hephaestus.integration.scm.github.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.web.client.RestClient;

class GitHubAppSubscriptionMonitorTest extends BaseUnitTest {

    @Mock
    private GitHubAppTokenService tokens;

    @Test
    void shouldReportSubscriptionDifferencesAndClearThemAfterTheAppIsFixed() throws Exception {
        // Same default_events as the guide, in an order GitHub could return.
        String expected = """
                "check_suite", "issue_comment", "issue_dependencies", "issues", "label", "member", "membership",
                "milestone", "organization", "pull_request", "pull_request_review", "pull_request_review_comment",
                "pull_request_review_thread", "push", "repository", "status", "sub_issues", "team"
                """;
        var meters = new SimpleMeterRegistry();
        try (var github = new MockWebServer()) {
            github.start();
            var monitor = new GitHubAppSubscriptionMonitor(
                    tokens, meters, RestClient.builder(), github.url("/").toString());
            when(tokens.isConfigured()).thenReturn(true);
            when(tokens.generateAppJWT()).thenReturn("test-app-jwt");
            var extra = meters.get("github.app.subscription.drift")
                    .tag("change", "extra")
                    .gauge();
            var missing = meters.get("github.app.subscription.drift")
                    .tag("change", "missing")
                    .gauge();
            assertThat(extra.value()).isNaN();
            assertThat(missing.value()).isNaN();

            github.enqueue(events(expected.replace("\"push\"", "\"workflow_job\", \"check_run\", \"installation\"")));
            monitor.check();

            var request = github.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(request);
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getTarget()).isEqualTo("/app");
            assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer test-app-jwt");
            assertThat(extra.value()).isEqualTo(2);
            assertThat(missing.value()).isEqualTo(1);

            github.enqueue(new MockResponse.Builder().code(502).build());
            monitor.check();
            assertThat(extra.value()).isEqualTo(2);
            assertThat(missing.value()).isEqualTo(1);

            github.enqueue(events(expected));
            monitor.check();
            assertThat(extra.value()).isZero();
            assertThat(missing.value()).isZero();
        }
    }

    private static MockResponse events(String events) {
        return new MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("{\"events\":[" + events + "]}")
                .build();
    }
}
