package de.tum.cit.aet.hephaestus.integration.core.framework;

import de.tum.cit.aet.hephaestus.core.UnknownVocabulary;
import de.tum.cit.aet.hephaestus.integration.core.spi.Capability;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationNames;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Per-kind manifest registry. Built from constructor-injected
 * {@link IntegrationManifest} beans; duplicate-kind declarations fail-fast.
 */
@Component
public class IntegrationManifestRegistry implements IntegrationNames {

    private final Map<IntegrationKind, IntegrationManifest> byKind;

    public IntegrationManifestRegistry(List<IntegrationManifest> manifests) {
        this.byKind = manifests.stream()
                .collect(Collectors.toUnmodifiableMap(IntegrationManifest::kind, m -> m, (a, b) -> {
                    throw new IllegalStateException("Duplicate IntegrationManifest for kind=" + a.kind()
                            + ": "
                            + a.getClass()
                            + " vs "
                            + b.getClass());
                }));
    }

    public Set<IntegrationKind> registeredKinds() {
        return byKind.keySet();
    }

    public Optional<IntegrationManifest> manifestFor(IntegrationKind kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    public Set<Capability> capabilitiesFor(IntegrationKind kind) {
        return manifestFor(kind).map(IntegrationManifest::declaredCapabilities).orElse(Set.of());
    }

    @Override
    public String displayName(IntegrationKind kind) {
        return manifestFor(kind)
                .map(IntegrationManifest::displayName)
                .orElseGet(() -> UnknownVocabulary.label("integration", kind.name(), "another integration"));
    }
}
