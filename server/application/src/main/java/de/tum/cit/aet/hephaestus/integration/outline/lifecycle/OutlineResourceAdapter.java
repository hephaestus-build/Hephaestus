package de.tum.cit.aet.hephaestus.integration.outline.lifecycle;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineWebhookClient;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookController;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
public class OutlineResourceAdapter implements ProviderResourceAdapter {
    private final ObjectProvider<OutlineWebhookClient> clients;
    private final PublicApiUrls urls;

    public OutlineResourceAdapter(ObjectProvider<OutlineWebhookClient> clients, PublicApiUrls urls) {
        this.clients = clients;
        this.urls = urls;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.OUTLINE;
    }

    @Override
    public List<Resource> resources(
            IntegrationRef ref, ConnectionConfig config, @Nullable CredentialBundle credential) {
        if (!(config instanceof ConnectionConfig.OutlineConfig outline)
                || outline.webhookSubscriptionId() == null
                || outline.serverUrl() == null) return List.of();
        String hash = null;
        String route = outline.webhookRoute();
        if (route != null && route.indexOf('/') > 0) {
            int slash = route.indexOf('/');
            hash = ProviderResourceAdapter.fingerprint(urls.originUri(
                            OutlineWebhookController.PATH_PREFIX + "{keyId}/{routeId}",
                            route.substring(0, slash),
                            route.substring(slash + 1))
                    .toString());
        }
        return List.of(new Resource(
                kind(),
                ScmOrigin.of(outline.serverUrl()).orElseThrow(),
                ResourceType.SUBSCRIPTION,
                "",
                outline.webhookSubscriptionId(),
                hash));
    }

    @Override
    public Removal remove(Resource resource, @Nullable CredentialBundle credential, BooleanSupplier beforeDispatch) {
        OutlineWebhookClient client = clients.getIfAvailable();
        if (client == null || !(credential instanceof BearerToken bearer)) return Removal.NEEDS_AUTHORIZATION;
        var target = client.listWebhookSubscriptions(resource.origin(), bearer.token()).stream()
                .filter(subscription -> resource.resourceId().equals(subscription.getId()))
                .findFirst();
        if (target.isEmpty()) return Removal.VERIFIED_ABSENT;
        if (resource.ownershipHash() == null || target.get().getUrl() == null) return Removal.NEEDS_AUTHORIZATION;
        if (!resource.ownershipHash()
                .equals(ProviderResourceAdapter.fingerprint(target.get().getUrl()))) return Removal.DIFFERENT_OWNER;
        if (!beforeDispatch.getAsBoolean()) return Removal.CANCELLED;
        boolean completed = client.deleteWebhookSubscription(resource.origin(), bearer.token(), resource.resourceId());
        return completed ? Removal.TERMINAL_SUCCESS : Removal.AWAITING_CONFIRMATION;
    }

    @Override
    public List<Resource> discoverRegistration(Resource registration, @Nullable CredentialBundle credential) {
        OutlineWebhookClient client = clients.getIfAvailable();
        if (client == null || !(credential instanceof BearerToken bearer)) return List.of();
        return client.listWebhookSubscriptions(registration.origin(), bearer.token()).stream()
                .filter(subscription -> ("Hephaestus documentation sync [" + registration.resourceId() + "]")
                                .equals(subscription.getName())
                        && subscription.getUrl() != null
                        && registration.ownershipHash() != null
                        && registration
                                .ownershipHash()
                                .equals(ProviderResourceAdapter.fingerprint(subscription.getUrl())))
                .map(subscription -> new Resource(
                        kind(),
                        registration.origin(),
                        ResourceType.SUBSCRIPTION,
                        "",
                        Objects.requireNonNull(subscription.getId()),
                        registration.ownershipHash()))
                .toList();
    }
}
