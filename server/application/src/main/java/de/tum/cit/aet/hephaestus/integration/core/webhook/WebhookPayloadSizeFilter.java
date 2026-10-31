package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.web.PayloadSizeFilter;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link PayloadSizeFilter} over the public, unauthenticated ingest surface: {@code POST
 * /webhooks/**}, which includes GitHub and Slack Events API callbacks, GitLab and Outline connection hooks, and Slack
 * interactivity callbacks. Any other request falls through untouched, and each refusal is counted per provider.
 */
public class WebhookPayloadSizeFilter extends PayloadSizeFilter {

    private static final String UNIFIED_WEBHOOK_PREFIX = "/webhooks/";

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> rejectionCounters = new ConcurrentHashMap<>();

    public WebhookPayloadSizeFilter(WebhookProperties properties, MeterRegistry meterRegistry) {
        super(properties.http().maxPayloadBytes());
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !isGuardedUri(request.getRequestURI());
    }

    private static boolean isGuardedUri(String uri) {
        return uri.startsWith(UNIFIED_WEBHOOK_PREFIX);
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
        // The filter only runs for /webhooks/<kind>(/...) (see shouldNotFilter), tagged on <kind>.
        String tail = uri.substring(UNIFIED_WEBHOOK_PREFIX.length());
        int slash = tail.indexOf('/');
        return slash >= 0 ? tail.substring(0, slash) : tail;
    }
}
