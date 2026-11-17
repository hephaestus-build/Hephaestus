package de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
@ConditionalOnServerRole
public class GitLabResourceAdapter implements ProviderResourceAdapter {
    private final ObjectProvider<GitLabWebhookClient> clients;

    public GitLabResourceAdapter(ObjectProvider<GitLabWebhookClient> clients) {
        this.clients = clients;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public List<Resource> resources(
            IntegrationRef ref, ConnectionConfig config, @Nullable CredentialBundle credential) {
        if (!(config instanceof ConnectionConfig.GitLabConfig gitlab)) return List.of();
        String origin = ScmOrigin.of(gitlab.serverUrl()).orElseThrow();
        if (gitlab.gitlabWebhookId() == null) {
            return gitlab.projectHooks().stream()
                    .map(hook -> new Resource(
                            kind(),
                            origin,
                            ResourceType.PROJECT_HOOK,
                            projectParent(gitlab.gitlabGroupId(), hook.projectId()),
                            Long.toString(hook.hookId()),
                            gitlab.webhookOwnershipHash()))
                    .toList();
        }
        return List.of(new Resource(
                kind(),
                origin,
                ResourceType.GROUP_HOOK,
                gitlab.gitlabGroupId().toString(),
                gitlab.gitlabWebhookId().toString(),
                gitlab.webhookOwnershipHash()));
    }

    /** The parent of a project hook: the group whose credential removes it, then the project that holds it. */
    public static String projectParent(long groupId, long projectId) {
        return groupId + "/" + projectId;
    }

    /** The project of a parent that {@link #projectParent} wrote, or empty for a group parent. */
    private static Optional<Long> projectOf(String parentId) {
        int slash = parentId.indexOf('/');
        return slash < 0 ? Optional.empty() : Optional.of(Long.parseLong(parentId.substring(slash + 1)));
    }

    @Override
    public Removal remove(Resource resource, @Nullable CredentialBundle credential, BooleanSupplier beforeDispatch) {
        GitLabWebhookClient client = clients.getIfAvailable();
        if (client == null || !(credential instanceof BearerToken bearer)) return Removal.NEEDS_AUTHORIZATION;
        Optional<Long> project = projectOf(resource.parentId());
        List<WebhookInfo> hooks;
        try {
            hooks = project.isPresent()
                    ? client.listProjectWebhooksWithCredentials(resource.origin(), bearer.token(), project.get())
                    : client.listGroupWebhooksWithCredentials(
                            resource.origin(), bearer.token(), Long.parseLong(resource.parentId()));
        } catch (WebClientResponseException.NotFound hidden) {
            // GitLab deletes a project's hooks with the project. A 404 alone can also hide a project the token lost,
            // so only a group the same token still reads proves the project gone. A project moved out of the group
            // keeps its hook, but that hook posts to a route that admits only the group's projects.
            if (project.isPresent()) {
                long group = Long.parseLong(
                        resource.parentId().substring(0, resource.parentId().indexOf('/')));
                if (client.groupReadableWithCredentials(resource.origin(), bearer.token(), group)
                        && !client.projectReadableWithCredentials(resource.origin(), bearer.token(), project.get())) {
                    return Removal.VERIFIED_ABSENT;
                }
            }
            throw hidden;
        }
        var target = hooks.stream()
                .filter(hook -> Long.toString(hook.id()).equals(resource.resourceId()))
                .findFirst();
        if (target.isEmpty()) return Removal.VERIFIED_ABSENT;
        if (resource.ownershipHash() == null) return Removal.NEEDS_AUTHORIZATION;
        boolean owned = resource.ownershipHash()
                .equals(ProviderResourceAdapter.fingerprint(target.get().url()));
        if (!owned) return Removal.DIFFERENT_OWNER;
        if (!beforeDispatch.getAsBoolean()) return Removal.CANCELLED;
        boolean completed = project.isPresent()
                ? client.deregisterProjectWebhookWithCredentials(
                        resource.origin(),
                        bearer.token(),
                        project.get(),
                        target.get().id())
                : client.deregisterGroupWebhookWithCredentials(
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
        Optional<Long> project = projectOf(registration.parentId());
        var hooks = project.isPresent()
                ? client.listProjectWebhooksWithCredentials(registration.origin(), bearer.token(), project.get())
                : client.listGroupWebhooksWithCredentials(
                        registration.origin(), bearer.token(), Long.parseLong(registration.parentId()));
        return hooks.stream()
                .filter(hook -> registration.ownershipHash() != null
                        && registration.ownershipHash().equals(ProviderResourceAdapter.fingerprint(hook.url()))
                        && hook.url().endsWith("?hephaestus_resource=" + registration.resourceId()))
                .map(hook -> new Resource(
                        kind(),
                        registration.origin(),
                        project.isPresent() ? ResourceType.PROJECT_HOOK : ResourceType.GROUP_HOOK,
                        registration.parentId(),
                        Long.toString(hook.id()),
                        registration.ownershipHash()))
                .toList();
    }
}
