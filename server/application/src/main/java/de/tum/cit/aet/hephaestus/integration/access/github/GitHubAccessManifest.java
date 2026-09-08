package de.tum.cit.aet.hephaestus.integration.access.github;

import de.tum.cit.aet.hephaestus.integration.core.spi.Capability;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class GitHubAccessManifest implements IntegrationManifest {
    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB_ACCESS;
    }

    @Override
    public String displayName() {
        return "Hephaestus Access";
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
