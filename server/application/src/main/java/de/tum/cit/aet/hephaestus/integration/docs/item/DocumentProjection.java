package de.tum.cit.aet.hephaestus.integration.docs.item;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Provider-owned document records with exact native identities and account attribution. */
public interface DocumentProjection {
    /** Reuses the mirror owner's active-connection and approved-origin check. */
    boolean workspaceReadable(long workspaceId);

    /** Every permitted record in the workspace, including tombstones and evicted-body markers. */
    List<ProjectedDocument> documentsForWorkspace(long workspaceId);

    /** Each reference may be a document id or URL. */
    List<ProjectedDocument> documentsByReference(long workspaceId, Collection<String> documentRefs);

    /** Resolve the workspace mirror's local source id; absent rows return empty. */
    Optional<ProjectedDocument> documentById(long workspaceId, long documentId);

    /**
     * Ranked by full-text relevance to {@code queryText}. Tombstoned/evicted documents are excluded; a
     * blank query or no match yields an empty list, the caller's cue to fall back to
     * {@link #documentsForWorkspace}.
     *
     * @param queryText free text describing what is relevant (websearch syntax; {@code OR}-joined terms)
     */
    List<ProjectedDocument> searchDocuments(long workspaceId, String queryText, int limit);

    /**
     * The agent-facing view of one mirrored document. {@code bodyMarkdown} is {@code null} when the
     * document was removed upstream or its body was evicted under the size cap.
     *
     * <p>{@code createdByName}/{@code updatedByName} are untrusted third-party text and must ride inside
     * quarantined content, never as trusted metadata. {@code *AccountId} resolves lazily through the
     * linked-account chain and is {@code null} until the author links an identity. {@code collaborators}
     * covers editors the creator/last-editor pair misses; a name is known only when that collaborator is
     * also creator or last editor.
     *
     * <p>{@code archived} is Outline's recoverable "archived in the wiki" state, distinct from
     * {@code deleted}: an archived document keeps its body. {@code collectionName} is the display name;
     * {@code collectionSlug} is the stable identity. {@code url} is the link of the document on its server, when
     * the connection names the server and the document is not deleted.
     */
    record ProjectedDocument(
            String collectionSlug,
            String slug,
            String title,
            @Nullable String bodyMarkdown,
            boolean deleted,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt,
            @Nullable String createdByName,
            @Nullable String createdBySubject,
            @Nullable Long createdByAccountId,
            @Nullable String updatedByName,
            @Nullable String updatedBySubject,
            @Nullable Long updatedByAccountId,
            List<Collaborator> collaborators,
            boolean archived,
            @Nullable String collectionName,
            @Nullable Instant syncedAt,
            @Nullable String sourceId,
            @Nullable String url) {
        public ProjectedDocument {
            collaborators = List.copyOf(collaborators);
        }
        /** One document editor: provider-native subject, display name if known, resolved account id if linked. */
        public record Collaborator(
                String subject,
                @Nullable String name,
                @Nullable Long accountId) {}
    }
}
