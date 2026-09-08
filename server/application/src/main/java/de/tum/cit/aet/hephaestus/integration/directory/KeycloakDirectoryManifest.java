package de.tum.cit.aet.hephaestus.integration.directory;

import de.tum.cit.aet.hephaestus.integration.core.spi.Capability;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class KeycloakDirectoryManifest implements IntegrationManifest {
    @Override
    public IntegrationKind kind() {
        return IntegrationKind.KEYCLOAK_DIRECTORY;
    }

    @Override
    public String displayName() {
        return "Keycloak directory";
    }

    @Override
    public Set<Capability> declaredCapabilities() {
        return Set.of();
    }

    @Override
    public ReviewContribution reviewContribution() {
        return ReviewContribution.none();
    }
}
