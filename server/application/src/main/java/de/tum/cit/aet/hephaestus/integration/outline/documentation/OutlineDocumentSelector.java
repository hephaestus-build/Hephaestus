package de.tum.cit.aet.hephaestus.integration.outline.documentation;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relevance-based document selection over the mirrored {@code outline_document} rows: Postgres full-text
 * search (websearch syntax, {@code simple} config) ranked by {@code ts_rank}. Consumed by
 * {@link OutlineDocumentProjector} to serve the agent SPI's {@code searchDocuments}; never exposed
 * outside this module.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineDocumentSelector {

    /** Hard ceiling on one search's breadth regardless of what a caller asks for. */
    static final int MAX_LIMIT = 50;

    private final OutlineDocumentRepository documentRepository;

    public OutlineDocumentSelector(OutlineDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /** Live documents ranked by relevance to {@code queryText}; empty for a blank query or non-positive limit. */
    @Transactional(readOnly = true)
    public List<OutlineDocument> select(long workspaceId, @Nullable String queryText, int limit) {
        if (queryText == null || queryText.isBlank() || limit <= 0) {
            return List.of();
        }
        return documentRepository.searchByRelevance(workspaceId, queryText.trim(), Math.min(limit, MAX_LIMIT));
    }
}
