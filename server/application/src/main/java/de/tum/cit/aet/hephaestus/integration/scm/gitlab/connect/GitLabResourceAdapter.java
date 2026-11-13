package de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabConnectionWebhookController;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
public class GitLabResourceAdapter implements ProviderResourceAdapter {
    private final ObjectProvider<GitLabWebhookClient> clients;
    private final PublicApiUrls urls;

    public GitLabResourceAdapter(ObjectProvider<GitLabWebhookClient> clients, PublicApiUrls urls) {
        this.clients = clients;
        this.urls = urls;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public List<Resource> resources(
            IntegrationRef ref, ConnectionConfig config, @Nullable CredentialBundle credential) {
        if (!(config instanceof ConnectionConfig.GitLabConfig gitlab) || gitlab.gitlabWebhookId() == null)
            return List.of();
        String ownership = gitlab.webhookOwnershipHash();
        if (ownership == null && ref.connectionId() != null) {
            ownership = "legacy:"
                    + ProviderResourceAdapter.fingerprint(urls.originUri(
                                    GitLabConnectionWebhookController.PATH_PREFIX + "{connectionId}/",
                                    ref.connectionId())
                            .toString());
        }
        return List.of(new Resource(
                kind(),
                ScmOrigin.of(gitlab.serverUrl()).orElseThrow(),
                ResourceType.GROUP_HOOK,
                gitlab.gitlabGroupId().toString(),
                gitlab.gitlabWebhookId().toString(),
                ownership));
    }

    @Override
    public Removal remove(Resource resource, @Nullable CredentialBundle credential, BooleanSupplier beforeDispatch) {
        GitLabWebhookClient client = clients.getIfAvailable();
        if (client == null || !(credential instanceof BearerToken bearer)) return Removal.NEEDS_AUTHORIZATION;
        var hooks = client.listGroupWebhooksWithCredentials(
                resource.origin(), bearer.token(), Long.parseLong(resource.parentId()));
        var target = hooks.stream()
                .filter(hook -> Long.toString(hook.id()).equals(resource.resourceId()))
                .findFirst();
        if (target.isEmpty()) return Removal.VERIFIED_ABSENT;
        if (resource.ownershipHash() == null) return Removal.NEEDS_AUTHORIZATION;
        if (!owns(resource.ownershipHash(), target.get().url())) return Removal.DIFFERENT_OWNER;
        if (!beforeDispatch.getAsBoolean()) return Removal.CANCELLED;
        boolean completed = client.deregisterGroupWebhookWithCredentials(
                resource.origin(),
                bearer.token(),
                Long.parseLong(resource.parentId()),
                target.get().id());
        return completed ? Removal.TERMINAL_SUCCESS : Removal.AWAITING_CONFIRMATION;
    }

    @Override
    public List<Resource> discoverRegistration(Resource registration, @Nullable CredentialBundle credential) {
        GitLabWebhookClient client = clients.getIfAvailable();
        if (client == null || !(credential instanceof BearerToken bearer)) return List.of();
        return client
                .listGroupWebhooksWithCredentials(
                        registration.origin(), bearer.token(), Long.parseLong(registration.parentId()))
                .stream()
                .filter(hook -> registration.ownershipHash() != null
                        && registration.ownershipHash().equals(ProviderResourceAdapter.fingerprint(hook.url()))
                        && hook.url().endsWith("?hephaestus_resource=" + registration.resourceId()))
                .map(hook -> new Resource(
                        kind(),
                        registration.origin(),
                        ResourceType.GROUP_HOOK,
                        registration.parentId(),
                        Long.toString(hook.id()),
                        registration.ownershipHash()))
                .toList();
    }

    static boolean owns(String expected, String url) {
        if (!expected.startsWith("legacy:")) return expected.equals(ProviderResourceAdapter.fingerprint(url));
        // A legacy route has exactly two signed suffix segments and no generation marker. Never match a replacement.
        if (url.contains("?")) return false;
        int last = url.lastIndexOf('/');
        int previous = last < 0 ? -1 : url.lastIndexOf('/', last - 1);
        return previous >= 0
                && expected.substring(7).equals(ProviderResourceAdapter.fingerprint(url.substring(0, previous + 1)));
    }
}
