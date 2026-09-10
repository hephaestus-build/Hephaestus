package de.tum.cit.aet.hephaestus.integration.outline.client;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.util.List;

/** A working subscription endpoint for content/collection tests; registrar tests own failure cases. */
public final class OutlineWebhookTestFixtures {
    private OutlineWebhookTestFixtures() {}

    public static void acceptsSubscriptions(OutlineWebhookClient client) {
        String id = "00000000-0000-0000-0000-000000000001";
        lenient()
                .when(client.createWebhookSubscription(
                        anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(id);
        lenient()
                .when(client.listWebhookSubscriptions(anyString(), anyString()))
                .thenReturn(List.of(OutlineClientModels.webhookSubscription(
                        id, "Hephaestus", "https://hooks.example.test/webhooks/outline", true, List.of())));
    }
}
