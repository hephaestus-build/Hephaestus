package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebhookPayloadSizeFilterTest extends BaseUnitTest {

    private static final long MAX = 1024;

    private final WebhookProperties properties = new WebhookProperties(
            null,
            null,
            new WebhookProperties.TokenRotation(7, 90),
            new WebhookProperties.Publish(Duration.ofSeconds(8), Duration.ofMillis(200)),
            WebhookPropertiesFixture.stream(),
            new WebhookProperties.Shutdown(Duration.ofSeconds(15)),
            new WebhookProperties.Http(MAX),
            new WebhookProperties.Routing(null, null));

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final WebhookPayloadSizeFilter filter = new WebhookPayloadSizeFilter(
            properties,
            meters,
            WebhookInFlightBudget.forHeap(Runtime.getRuntime().maxMemory()));

    /** A body above the size that is not counted, inside a limit that admits it. */
    private static final int LARGE = (int) WebhookInFlightBudget.SMALL_BODY_BYTES + 1024;

    private final WebhookProperties roomy = new WebhookProperties(
            null,
            null,
            new WebhookProperties.TokenRotation(7, 90),
            new WebhookProperties.Publish(Duration.ofSeconds(8), Duration.ofMillis(200)),
            WebhookPropertiesFixture.stream(),
            new WebhookProperties.Shutdown(Duration.ofSeconds(15)),
            new WebhookProperties.Http(10L * LARGE),
            new WebhookProperties.Routing(null, null));

    private static MockHttpServletRequest delivery(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github");
        request.setContent(new byte[bytes]);
        return request;
    }

    /** Room for {@code bodies} large bodies arriving at once. */
    private WebhookPayloadSizeFilter withRoomFor(int bodies) {
        return new WebhookPayloadSizeFilter(
                roomy, meters, new WebhookInFlightBudget((long) bodies * LARGE, 1024, Duration.ofMillis(1)));
    }

    private static void readBody(ServletRequest request) throws IOException {
        request.getInputStream().readAllBytes();
    }

    @Test
    void aSenderThatDeclaresALargeBodyAndSendsNothingHoldsNothing() throws Exception {
        WebhookPayloadSizeFilter bounded = withRoomFor(1);
        MockHttpServletResponse served = new MockHttpServletResponse();
        FilterChain stalled =
                (request, response) -> bounded.doFilter(delivery(LARGE), served, (inner, ignored) -> readBody(inner));

        bounded.doFilter(delivery(LARGE), new MockHttpServletResponse(), stalled);

        assertThat(served.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void refusesWhenTheBytesThatArriveDoNotFitAndLetsTheNextOneThroughAfterwards() throws Exception {
        WebhookPayloadSizeFilter bounded = withRoomFor(1);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        FilterChain arrived = (request, response) -> {
            readBody(request);
            bounded.doFilter(delivery(LARGE), refused, (inner, ignored) -> readBody(inner));
        };

        bounded.doFilter(delivery(LARGE), new MockHttpServletResponse(), arrived);

        assertThat(refused.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("30");
        assertThat(meters.get(IntegrationCoreMetrics.WEBHOOK_REJECTED)
                        .tag("reason", "busy")
                        .counter()
                        .count())
                .isEqualTo(1);

        MockHttpServletResponse next = new MockHttpServletResponse();
        bounded.doFilter(delivery(LARGE), next, (request, ignored) -> readBody(request));
        assertThat(next.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void aReaderThatHandlesAFailedReadCannotTurnARefusalIntoAClientError() throws Exception {
        WebhookPayloadSizeFilter bounded = withRoomFor(1);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        FilterChain readerSays400 = (request, response) -> {
            try {
                readBody(request);
            } catch (IOException e) {
                ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
            }
        };
        FilterChain arrived = (request, response) -> {
            readBody(request);
            bounded.doFilter(delivery(LARGE), refused, readerSays400);
        };

        bounded.doFilter(delivery(LARGE), new MockHttpServletResponse(), arrived);

        assertThat(refused.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void aSmallDeliveryPassesWhileLargeOnesFillTheBudget() throws Exception {
        WebhookPayloadSizeFilter bounded = withRoomFor(1);
        MockHttpServletResponse small = new MockHttpServletResponse();
        FilterChain arrived = (request, response) -> {
            readBody(request);
            bounded.doFilter(delivery(1024), small, (inner, ignored) -> readBody(inner));
        };

        bounded.doFilter(delivery(LARGE), new MockHttpServletResponse(), arrived);

        assertThat(small.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void aDeliveryLargerThanTheWholeBudgetProceedsAlone() throws Exception {
        WebhookPayloadSizeFilter bounded = new WebhookPayloadSizeFilter(
                roomy, meters, new WebhookInFlightBudget(1024, 1024, Duration.ofMillis(1)));
        MockHttpServletResponse served = new MockHttpServletResponse();

        bounded.doFilter(delivery(LARGE), served, (request, ignored) -> readBody(request));

        assertThat(served.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void returnsTheBudgetWhenTheDeliveryFails() throws Exception {
        WebhookPayloadSizeFilter bounded = withRoomFor(1);
        FilterChain failing = (request, response) -> {
            readBody(request);
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> bounded.doFilter(delivery(LARGE), new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);

        MockHttpServletResponse next = new MockHttpServletResponse();
        bounded.doFilter(delivery(LARGE), next, (request, ignored) -> readBody(request));
        assertThat(next.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void anEncodedPathIsGuardedLikeThePathItRoutesTo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/%77ebhooks/github");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    }

    @Test
    void rejectsOversizedGitlabPostWith413() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github");
        request.setContentType("application/json");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(chain, never()).doFilter(request, response);
        assertCounter("github", "payload-too-large", 1);
        assertCounter("github", "length-required", 0); // no swapped tags
    }

    @Test
    void allowsBodyExactlyAtMaxSize() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github");
        request.setContentType("application/json");
        request.setContent(new byte[(int) MAX]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void rejectsBodyOneByteOverMaxSize() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github");
        request.setContentType("application/json");
        request.setContent(new byte[(int) MAX + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void rejectsMissingContentLengthWith411() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.addHeader("Transfer-Encoding", "chunked");
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_LENGTH_REQUIRED);
        verify(chain, never()).doFilter(request, response);
        assertCounter("github", "length-required", 1);
        assertCounter("github", "payload-too-large", 0); // no swapped tags
    }

    private void assertCounter(String provider, String reason, double expected) {
        Counter counter = meters.find("webhook.rejected")
                .tag("provider", provider)
                .tag("reason", reason)
                .counter();
        double actual = counter == null ? 0.0 : counter.count();
        assertThat(actual)
                .as("webhook.rejected{provider=%s, reason=%s}", provider, reason)
                .isEqualTo(expected);
    }

    @Test
    void rejectsOversizedSlackWebhookPostWith413() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/slack");
        request.setContentType("application/json");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(chain, never()).doFilter(request, response);
        assertCounter("slack", "payload-too-large", 1);
    }

    @Test
    void rejectsOversizedSlackInteractivityPostWithSlackProviderTag() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/slack/interactivity");
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(chain, never()).doFilter(request, response);
        assertCounter("slack", "payload-too-large", 1);
    }

    @Test
    void onlyTheWebhookPathsReachIt() {
        assertThat(new WebhookHttpConfiguration()
                        .webhookPayloadSizeFilter(properties, meters, WebhookInFlightBudget.forHeap(1L << 30))
                        .getUrlPatterns())
                .containsExactly("/webhooks/*");
    }

    @Test
    void bypassesGetRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/webhooks/github");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
    }
}
