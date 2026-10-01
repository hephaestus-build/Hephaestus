package de.tum.cit.aet.hephaestus.integration.outline.domain.signal;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Names a mirrored document.
 *
 * <p>No URL: the mirror stores only a slug, and the server it hangs off is connection state this
 * resolver cannot reach. A link built from half of it would be a broken one.
 */
@Component
public class DocumentIdentityResolver implements ArtifactIdentityResolver {

    private final OutlineDocumentRepository documents;

    public DocumentIdentityResolver(OutlineDocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public ArtifactKind kind() {
        return DocsSignals.DOCUMENT;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ArtifactIdentity> resolve(long workspaceId, Collection<Long> artifactIds) {
        if (artifactIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ArtifactIdentity> resolved = new HashMap<>();
        for (var label : documents.findLabels(workspaceId, artifactIds)) {
            String title = label.getTitle();
            resolved.put(
                    label.getId(),
                    new ArtifactIdentity(
                            kind(),
                            label.getId(),
                            IntegrationKind.OUTLINE,
                            null,
                            title == null || title.isBlank() ? "Document" : title,
                            label.getCollectionName(),
                            null));
        }
        return Map.copyOf(resolved);
    }
}
