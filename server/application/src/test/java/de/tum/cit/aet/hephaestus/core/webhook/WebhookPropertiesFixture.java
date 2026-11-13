package de.tum.cit.aet.hephaestus.core.webhook;

import org.jspecify.annotations.Nullable;

/** Shipped-shaped {@link WebhookProperties} for tests that care about one field of it. */
public final class WebhookPropertiesFixture {

    private WebhookPropertiesFixture() {}

    public static WebhookProperties properties() {
        return withRouting(new WebhookProperties.Routing(null, null));
    }

    public static WebhookProperties withRouting(WebhookProperties.Routing routing) {
        return configured(null, routing);
    }

    /** Receiver and routing settings as a deployment that registers provider hooks has them. */
    public static WebhookProperties configured(@Nullable String secret, WebhookProperties.Routing routing) {
        return new WebhookProperties(
                secret, new WebhookProperties.TokenRotation(7, 90), new WebhookProperties.Http(26_214_400L), routing);
    }
}
