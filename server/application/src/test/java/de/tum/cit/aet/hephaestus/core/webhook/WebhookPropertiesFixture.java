package de.tum.cit.aet.hephaestus.core.webhook;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.util.unit.DataSize;

/** Shipped-shaped {@link WebhookProperties} for tests that care about one field of it. */
public final class WebhookPropertiesFixture {

    public static final long GIBIBYTE = 1024L * 1024 * 1024;

    private WebhookPropertiesFixture() {}

    /** {@code n} gibibytes, the unit every webhook stream bound is quoted in. */
    public static DataSize gibibytes(long n) {
        return DataSize.ofBytes(n * GIBIBYTE);
    }

    public static WebhookProperties.Stream stream() {
        return new WebhookProperties.Stream(
                Duration.ofMinutes(10),
                Duration.ofDays(180),
                Map.of(),
                gibibytes(1),
                Map.of(),
                gibibytes(12),
                false,
                Duration.ofMinutes(5),
                Duration.ofSeconds(60));
    }

    public static WebhookProperties properties() {
        return with(stream());
    }

    public static WebhookProperties with(WebhookProperties.Stream stream) {
        return configured(null, null, stream, new WebhookProperties.Routing(null, null));
    }

    public static WebhookProperties withRouting(WebhookProperties.Routing routing) {
        return configured(null, null, stream(), routing);
    }

    /** Receiver and registration settings as a deployment that registers GitLab hooks has them. */
    public static WebhookProperties configured(
            @Nullable String externalUrl,
            @Nullable String secret,
            WebhookProperties.Stream stream,
            WebhookProperties.Routing routing) {
        return new WebhookProperties(
                externalUrl,
                secret,
                new WebhookProperties.TokenRotation(7, 90),
                new WebhookProperties.Publish(Duration.ofSeconds(9), 5, Duration.ofMillis(200)),
                stream,
                new WebhookProperties.Shutdown(Duration.ofSeconds(15)),
                new WebhookProperties.Http(26_214_400L),
                routing);
    }
}
