package de.tum.cit.aet.hephaestus.integration.core.framework;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactCatalog;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentityResolver;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Dispatches labelling to the resolver that owns the kind, and answers for the kinds nobody resolves.
 *
 * <p>Two resolvers for one kind is fatal: which title a trace shows would otherwise depend on bean
 * ordering.
 */
@Component
public class RegisteredArtifactIdentities implements ArtifactIdentities {

    private final Map<ArtifactKind, ArtifactIdentityResolver> byKind;
    private final ArtifactCatalog artifacts;

    public RegisteredArtifactIdentities(List<ArtifactIdentityResolver> resolvers, ArtifactCatalog artifacts) {
        this.artifacts = artifacts;
        this.byKind = resolvers.stream()
                .collect(Collectors.toUnmodifiableMap(ArtifactIdentityResolver::kind, resolver -> resolver, (a, b) -> {
                    throw new IllegalStateException("Duplicate ArtifactIdentityResolver for kind=" + a.kind()
                            + ": "
                            + a.getClass().getName()
                            + " vs "
                            + b.getClass().getName());
                }));
    }

    @Override
    public Map<Long, ArtifactIdentity> resolve(long workspaceId, ArtifactKind kind, Collection<Long> artifactIds) {
        Collection<Long> ids = new LinkedHashSet<>(artifactIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        ArtifactIdentityResolver resolver = byKind.get(kind);
        Map<Long, ArtifactIdentity> resolved = resolver == null ? Map.of() : resolver.resolve(workspaceId, ids);
        String fallbackLabel = artifacts.kindDisplayName(kind);
        Map<Long, ArtifactIdentity> answer = new HashMap<>(ids.size());
        for (Long id : ids) {
            ArtifactIdentity identity = resolved.get(id);
            answer.put(id, identity != null ? identity : ArtifactIdentity.unresolved(kind, id, fallbackLabel));
        }
        return Map.copyOf(answer);
    }
}
