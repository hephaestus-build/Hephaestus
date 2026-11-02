package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.web.PayloadSizeFilter;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;

/**
 * The {@link PayloadSizeFilter} over the public, unauthenticated ingest surface: {@code POST
 * /webhooks/**}, which includes GitHub and Slack Events API callbacks, GitLab and Outline connection hooks, and Slack
 * interactivity callbacks. Any other request falls through untouched, and each refusal is counted per provider.
 */
public class WebhookPayloadSizeFilter extends PayloadSizeFilter {

    private static final String UNIFIED_WEBHOOK_PREFIX = "/webhooks/";

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> rejectionCounters = new ConcurrentHashMap<>();
    private final WebhookInFlightBudget budget;

    public WebhookPayloadSizeFilter(
            WebhookProperties properties, MeterRegistry meterRegistry, WebhookInFlightBudget budget) {
        super(properties.http().maxPayloadBytes());
        this.meterRegistry = meterRegistry;
        this.budget = budget;
    }

    @Override
    protected void accepted(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain, long contentLength)
            throws ServletException, IOException {
        if (contentLength >= 0 && contentLength <= WebhookInFlightBudget.SMALL_BODY_BYTES) {
            chain.doFilter(request, response);
            return;
        }
        try (WebhookInFlightBudget.Holding holding = budget.forArriving()) {
            try {
                chain.doFilter(new CountedBody(request, holding), response);
            } catch (ServletException | RuntimeException failure) {
                if (!holding.refused()) {
                    throw failure;
                }
            }
            if (holding.refused()) {
                refused(request, WebhookIngestPipeline.BUSY_REASON);
                // A reader outside the MVC handlers lets the refusal reach this filter unanswered.
                if (!response.isCommitted()) {
                    response.reset();
                    response.setHeader(HttpHeaders.RETRY_AFTER, WebhookIngestPipeline.BUSY_RETRY_AFTER_SECONDS);
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                }
            }
        }
    }

    /** Any reader of the body counts through here: the pipeline, and the controllers that read a body themselves. */
    private static final class CountedBody extends HttpServletRequestWrapper {
        private final WebhookInFlightBudget.Holding holding;

        private CountedBody(HttpServletRequest request, WebhookInFlightBudget.Holding holding) {
            super(request);
            this.holding = holding;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream body = super.getInputStream();
            return new ServletInputStream() {
                @Override
                public int read() throws IOException {
                    int read = body.read();
                    if (read >= 0) count(1);
                    return read;
                }

                @Override
                public int read(byte[] target, int offset, int length) throws IOException {
                    int read = body.read(target, offset, length);
                    if (read > 0) count(read);
                    return read;
                }

                @Override
                public boolean isFinished() {
                    return body.isFinished();
                }

                @Override
                public boolean isReady() {
                    return body.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    body.setReadListener(listener);
                }

                private void count(int read) {
                    if (!holding.add(read)) {
                        throw new WebhookReceiverBusyException();
                    }
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /** The URL pattern of its registration decides which paths reach this filter, on the path as routing decodes it. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void refused(HttpServletRequest request, String reason) {
        String provider = providerTag(request.getRequestURI());
        rejectionCounters
                .computeIfAbsent(
                        provider + ":" + reason,
                        key -> Counter.builder(IntegrationCoreMetrics.WEBHOOK_REJECTED)
                                .tag("provider", provider)
                                .tag("reason", reason)
                                .register(meterRegistry))
                .increment();
    }

    private static String providerTag(String uri) {
        // The filter only runs for /webhooks/<kind>(/...), tagged on <kind>. An encoded path has no plain prefix.
        if (!uri.startsWith(UNIFIED_WEBHOOK_PREFIX)) {
            return "unknown";
        }
        String tail = uri.substring(UNIFIED_WEBHOOK_PREFIX.length());
        int slash = tail.indexOf('/');
        return slash >= 0 ? tail.substring(0, slash) : tail;
    }
}
