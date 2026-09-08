package de.tum.cit.aet.hephaestus.integration.core.conformance;

import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessManifest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import java.util.List;

class GitHubAccessManifestContractTest extends IntegrationManifestContractTest {
    @Override
    protected IntegrationManifest manifest() {
        return new GitHubAccessManifest();
    }

    @Override
    protected List<ArtifactDescriptor> descriptors() {
        return List.of();
    }
}
