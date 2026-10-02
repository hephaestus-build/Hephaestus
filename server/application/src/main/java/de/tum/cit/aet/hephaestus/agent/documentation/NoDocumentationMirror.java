package de.tum.cit.aet.hephaestus.agent.documentation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The documentation projection for a deployment that mirrors no documentation.
 *
 * <p>Every method answers "nothing", which is the truth rather than a placeholder: with no
 * documentation integration switched on there are no mirrored documents, so a linked-document lookup
 * finds none and a document review finds no subject. The consumers already handle that — the review
 * context reports its subject unavailable with a reason, and the linked-document evidence is optional.
 *
 * <p>{@link DocumentProjection} is agent-owned but implemented only by vendor modules, each behind a
 * master switch that is off by default; without this floor bean, the application fails to start wherever
 * no vendor is switched on.
 */
final class NoDocumentationMirror implements DocumentProjection {
    @Override
    public boolean workspaceReadable(long workspaceId) {
        return false;
    }

    @Override
    public List<ProjectedDocument> documentsForWorkspace(long workspaceId) {
        return List.of();
    }

    @Override
    public List<ProjectedDocument> documentsByReference(long workspaceId, Collection<String> documentRefs) {
        return List.of();
    }

    @Override
    public Optional<ProjectedDocument> documentById(long workspaceId, long documentId) {
        return Optional.empty();
    }

    @Override
    public List<ProjectedDocument> searchDocuments(long workspaceId, String queryText, int limit) {
        return List.of();
    }
}
