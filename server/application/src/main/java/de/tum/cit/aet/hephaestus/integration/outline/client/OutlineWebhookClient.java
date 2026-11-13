package de.tum.cit.aet.hephaestus.integration.outline.client;

import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineWebhookSubscription;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface OutlineWebhookClient {
    List<OutlineWebhookSubscription> listWebhookSubscriptions(String serverUrl, String token);

    @Nullable
    String createWebhookSubscription(
            String serverUrl, String token, String name, String deliveryUrl, String signingSecret, List<String> events);

    boolean deleteWebhookSubscription(String serverUrl, String token, String subscriptionId);
}
