package de.tum.cit.aet.hephaestus.integration.core.conformance;

import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.directory.KeycloakDirectoryManifest;
import java.util.List;

class KeycloakDirectoryManifestContractTest extends IntegrationManifestContractTest {
    @Override
    protected IntegrationManifest manifest() {
        return new KeycloakDirectoryManifest();
    }

    @Override
    protected List<ArtifactDescriptor> descriptors() {
        return List.of();
    }
}
