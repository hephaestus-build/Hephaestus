package de.tum.cit.aet.hephaestus.integration.scm.github.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
public class GitHubResourceAdapter implements ProviderResourceAdapter {
    private final GitHubAppTokenService app;

    public GitHubResourceAdapter(GitHubAppTokenService app) {
        this.app = app;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public List<Resource> resources(
            IntegrationRef ref, ConnectionConfig config, @Nullable CredentialBundle credential) {
        if (!(config instanceof ConnectionConfig.GitHubAppConfig installation) || installation.installationId() == null)
            return List.of();
        return List.of(new Resource(
                kind(),
                ScmOrigin.of(installation.serverUrl()).orElseThrow(),
                ResourceType.INSTALLATION,
                installation.appId() == null
                                || (ref.instanceKey() != null
                                        && !ref.instanceKey()
                                                .equals(installation
                                                        .installationId()
                                                        .toString()))
                        ? "*"
                        : installation.appId().toString(),
                installation.installationId().toString(),
                null));
    }

    @Override
    public Removal remove(Resource resource, @Nullable CredentialBundle credential, BooleanSupplier beforeDispatch) {
        if (!"https://github.com".equals(resource.origin())
                || !app.isConfigured()
                || !resource.parentId().equals(Long.toString(app.getConfiguredAppId())))
            return Removal.NEEDS_AUTHORIZATION;
        // App JWT scopes this lookup to this App. The dispatcher uses dispatch history to decide whether absence
        // resolves the removal or an earlier uncertain attempt still needs confirmation.
        try {
            app.installation(Long.parseLong(resource.resourceId()));
        } catch (InstallationNotFoundException absentFromApp) {
            return Removal.VERIFIED_ABSENT;
        }
        if (!beforeDispatch.getAsBoolean()) return Removal.CANCELLED;
        app.deleteInstallation(Long.parseLong(resource.resourceId()));
        // GitHub returns 202. It has accepted work, not supplied a terminal removal receipt.
        return Removal.AWAITING_CONFIRMATION;
    }
}
