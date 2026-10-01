package de.tum.cit.aet.hephaestus.agent.documentation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Agent-owned SPI projecting a workspace's mirrored documentation into the agent-facing view; implemented by
 * the integration module that owns the schema, so the agent never touches it directly. A tombstoned or
 * size-cap-evicted document is returned as a marker rather than dropped, so a link to it still resolves.
 */
public interface DocumentProjection {
    /** Reuses the mirror owner's active-connection and approved-origin check. */
    boolean workspaceReadable(long workspaceId);

    /** Every permitted record in the workspace, including tombstones and evicted-body markers. */
    List<ProjectedDocument> documentsForWorkspace(long workspaceId);

    /** Each reference may be a document id or URL. */
    List<ProjectedDocument> documentsByReference(long workspaceId, Collection<String> documentRefs);

    /**
     * Keyed on the local id because that is what the signal ledger records as the artifact — a review is
     * occasioned by {@code docs.document.published} against a row in this workspace's mirror. Empty when
     * the row is gone, which the caller must report as an unavailable subject, not a document that said
     * nothing.
     */
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
     * Pulls documentation references — ids, slugs, links — out of free text. What counts as a reference is
     * the implementation's vendor knowledge; the consumer stays vendor-blind and feeds the result into
     * {@link #documentsByReference}.
     */
    Set<String> extractReferences(@Nullable String text);

    /**
     * The agent-facing view of one mirrored document. {@code bodyMarkdown} is {@code null} when the
     * document was removed upstream or its body was evicted under the size cap.
     *
     * <p>{@code createdByName}/{@code updatedByName} are untrusted third-party text and must ride inside
     * quarantined content, never as trusted metadata. {@code *MemberId} resolves lazily through the
     * linked-account chain and is {@code null} until the author links an identity. {@code collaborators}
     * covers editors the creator/last-editor pair misses; a name is known only when that collaborator is
     * also creator or last editor.
     *
     * <p>{@code archived} is Outline's recoverable "archived in the wiki" state, distinct from
     * {@code deleted}: an archived document keeps its body. {@code collectionName} is the display name;
     * {@code collectionSlug} is the stable identity.
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
            @Nullable Long createdByMemberId,
            @Nullable String updatedByName,
            @Nullable String updatedBySubject,
            @Nullable Long updatedByMemberId,
            List<Collaborator> collaborators,
            boolean archived,
            @Nullable String collectionName,
            @Nullable Instant syncedAt,
            @Nullable String sourceId) {
        /** One document editor: provider-native subject, display name if known, resolved member id if linked. */
        public record Collaborator(
                String subject,
                @Nullable String name,
                @Nullable Long memberId) {}

        /** No substrate captured, or tombstoned — every author/collection field {@code null}. */
        public static ProjectedDocument withoutAuthors(
                String collectionSlug, String slug, String title, @Nullable String bodyMarkdown, boolean deleted) {
            return new ProjectedDocument(
                    collectionSlug,
                    slug,
                    title,
                    bodyMarkdown,
                    deleted,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    false,
                    null,
                    null,
                    null);
        }
    }
}
