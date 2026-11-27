package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import com.slack.api.Slack;
import com.slack.api.util.http.SlackHttpClient;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.slack.SlackProperties;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.slack.metrics.SlackMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.EventListener;

public final class SlackGatewayTestSupport {
    private SlackGatewayTestSupport() {}

    public static SlackMessageService withConnectionListener(
            SlackProperties properties, SlackCredentialProvider credentials, EventListener listener) {
        var config = SlackSendFence.nativeConfig(properties);
        var client = SlackSendFence.client(config);
        var http = client.getHttpClient()
                .getOkHttpClient()
                .newBuilder()
                .eventListener(listener)
                .build();
        var metrics = new SimpleMeterRegistry();
        return new SlackMessageService(
                Slack.getInstance(config, new SlackHttpClient(http)),
                credentials,
                new SlackRateLimitTracker(metrics),
                new OutboundEgressGuard(() -> false),
                new SlackMetrics(metrics),
                SlackTime.SYSTEM);
    }
}
