package de.tum.cit.aet.hephaestus.integration.outline.documentation;

import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.outline.identity.OutlineIdentityResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outline's implementation of the agent {@link DocumentProjection} SPI — the sole agent-facing reader of the
 * mirror; the agent consumes the projected payload, never the schema.
 *
 * <p>A document removed upstream or size-cap evicted projects as a marker rather than being dropped, so a link to
 * a vanished document still resolves to a placeholder. Reads are pure — the {@code readOnly} transaction enforces
 * that this projector never stamps {@code last_materialized_at}.
 *
 * <p>Authorship projects as-is; the workspace member id resolves lazily per batch (memoised per subject) through
 * {@link OutlineIdentityResolver}. An unlinked author degrades to name-only — no member id is ever stamped on the row.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
@Transactional(readOnly = true)
public class OutlineDocumentProjector implements DocumentProjection {

    private final OutlineDocumentRepository documentRepository;
    private final OutlineCollectionRepository collectionRepository;
    private final ConnectionService connectionService;
    private final OutlineIdentityResolver identityResolver;
    private final OutlineDocumentSelector documentSelector;
    private final OutlineOriginPolicy originPolicy;

    public OutlineDocumentProjector(
            OutlineDocumentRepository documentRepository,
            OutlineCollectionRepository collectionRepository,
            ConnectionService connectionService,
            OutlineIdentityResolver identityResolver,
            OutlineDocumentSelector documentSelector,
            OutlineOriginPolicy originPolicy) {
        this.documentRepository = documentRepository;
        this.collectionRepository = collectionRepository;
        this.connectionService = connectionService;
        this.identityResolver = identityResolver;
        this.documentSelector = documentSelector;
        this.originPolicy = originPolicy;
    }

    @Override
    public boolean workspaceReadable(long workspaceId) {
        return isOriginApproved(workspaceId);
    }

    @Override
    public List<ProjectedDocument> documentsForWorkspace(long workspaceId) {
        if (!isOriginApproved(workspaceId)) return List.of();
        AuthorContext authors = authorContext(workspaceId);
        Map<String, String> collectionNames = collectionNames(workspaceId);
        return documentRepository.findForProjection(workspaceId, Pageable.unpaged()).stream()
                .map(doc -> project(doc, authors, collectionNames))
                .toList();
    }

    @Override
    public Optional<ProjectedDocument> documentById(long workspaceId, long documentId) {
        if (!isOriginApproved(workspaceId)) return Optional.empty();
        return documentRepository
                .findById(documentId)
                .filter(doc -> doc.getWorkspaceId() != null && doc.getWorkspaceId() == workspaceId)
                .map(doc -> project(doc, authorContext(workspaceId), collectionNames(workspaceId)));
    }

    @Override
    public List<ProjectedDocument> documentsByReference(long workspaceId, Collection<String> documentRefs) {
        if (!isOriginApproved(workspaceId)) return List.of();
        if (documentRefs == null || documentRefs.isEmpty()) {
            return List.of();
        }
        // A reference is either a raw Outline document id or a document URL; match on the id and slug columns,
        // adding the last URL path segment as a candidate token so a link resolves to the mirrored row.
        Set<String> tokens = new LinkedHashSet<>();
        for (String ref : documentRefs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            String trimmed = ref.trim();
            tokens.add(trimmed);
            int lastSlash = trimmed.lastIndexOf('/');
            String lastSegment =
                    lastSlash >= 0 && lastSlash < trimmed.length() - 1 ? trimmed.substring(lastSlash + 1) : trimmed;
            if (lastSlash >= 0 && lastSlash < trimmed.length() - 1) {
                tokens.add(lastSegment);
            }
            // Defense-in-depth: a mirrored row may carry only the short Outline urlId (e.g. "psUl8qCles")
            // as its slug; the trailing "-<urlId>" segment of a full-URL-shaped reference
            // (e.g. "setup-guide-psUl8qCles") lets such a row still resolve.
            int lastDash = lastSegment.lastIndexOf('-');
            if (lastDash >= 0 && lastDash < lastSegment.length() - 1) {
                String urlIdCandidate = lastSegment.substring(lastDash + 1);
                if (urlIdCandidate.length() > 8) {
                    tokens.add(urlIdCandidate);
                }
            }
        }
        if (tokens.isEmpty()) {
            return List.of();
        }
        AuthorContext authors = authorContext(workspaceId);
        Map<String, String> collectionNames = collectionNames(workspaceId);
        return documentRepository.findByWorkspaceIdAndReferenceIn(workspaceId, tokens).stream()
                .map(doc -> project(doc, authors, collectionNames))
                .toList();
    }

    @Override
    public List<ProjectedDocument> searchDocuments(long workspaceId, String queryText, int limit) {
        if (!isOriginApproved(workspaceId)) return List.of();
        List<OutlineDocument> hits = documentSelector.select(workspaceId, queryText, limit);
        if (hits.isEmpty()) {
            return List.of();
        }
        AuthorContext authors = authorContext(workspaceId);
        Map<String, String> collectionNames = collectionNames(workspaceId);
        return hits.stream().map(doc -> project(doc, authors, collectionNames)).toList();
    }

    private boolean isOriginApproved(long workspaceId) {
        return connectionService
                .findActiveOutlineConfig(workspaceId)
                .map(ConnectionConfig.OutlineConfig::serverUrl)
                .filter(url -> url != null && !url.isBlank())
                .filter(originPolicy::allows)
                .isPresent();
    }

    /** Maps a mirrored row to the agent view; a tombstoned/evicted document serves a null body. */
    private static ProjectedDocument project(
            OutlineDocument doc, AuthorContext authors, Map<String, String> collectionNames) {
        boolean deleted = doc.isDeleted();
        String body = deleted ? null : doc.getBodyMarkdown();
        return new ProjectedDocument(
                orEmpty(doc.getCollectionSlug()),
                orEmpty(doc.getSlug()),
                orEmpty(doc.getTitle()),
                body,
                deleted,
                doc.getOutlineCreatedAt(),
                doc.getOutlineUpdatedAt(),
                doc.getCreatedByName(),
                doc.getCreatedBySubject(),
                authors.memberIdFor(doc.getCreatedBySubject()),
                doc.getUpdatedByName(),
                doc.getUpdatedBySubject(),
                authors.memberIdFor(doc.getUpdatedBySubject()),
                collaborators(doc, authors),
                doc.isArchived(),
                collectionNames.get(doc.getCollectionId()),
                doc.getLastMaterializedAt(),
                doc.getDocumentId());
    }

    /**
     * The workspace's collection id → display name map, loaded once per projection call (mirrors
     * {@link #authorContext}'s per-batch resolution). A collection with no captured name is absent from
     * the map, so {@code Map#get} degrades to {@code null} — the graceful floor for {@link
     * de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection.ProjectedDocument#collectionName}.
     */
    private Map<String, String> collectionNames(long workspaceId) {
        Map<String, String> names = new HashMap<>();
        for (OutlineCollection collection : collectionRepository.findByWorkspaceIdOrderByCreatedAtAsc(workspaceId)) {
            if (collection.getName() != null) {
                names.put(collection.getCollectionId(), collection.getName());
            }
        }
        return names;
    }

    /**
     * The document's collaborator list, each distinct subject resolved through the shared per-batch memo.
     * A display name is only known when the collaborator is also the creator or last editor — the
     * {@code documents.list} payload carries no names for middle editors.
     */
    private static List<ProjectedDocument.Collaborator> collaborators(OutlineDocument doc, AuthorContext authors) {
        List<String> subjects = doc.getCollaboratorSubjects();
        if (subjects == null || subjects.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>(subjects);
        List<ProjectedDocument.Collaborator> result = new ArrayList<>(distinct.size());
        for (String subject : distinct) {
            if (subject == null || subject.isBlank()) {
                continue;
            }
            String name = null;
            if (subject.equals(doc.getCreatedBySubject())) {
                name = doc.getCreatedByName();
            } else if (subject.equals(doc.getUpdatedBySubject())) {
                name = doc.getUpdatedByName();
            }
            result.add(new ProjectedDocument.Collaborator(subject, name, authors.memberIdFor(subject)));
        }
        return List.copyOf(result);
    }

    /**
     * The per-batch author-resolution context: the ACTIVE Outline connection's server URL and instance
     * key (= Outline team UUID), resolved ONCE per projection call. Without an ACTIVE connection (or a
     * config missing its server URL) authors degrade to name-only rather than failing the projection.
     */
    private AuthorContext authorContext(long workspaceId) {
        Optional<Connection> active = connectionService.findActive(workspaceId, IntegrationKind.OUTLINE);
        if (active.isEmpty() || !(active.get().getConfig() instanceof ConnectionConfig.OutlineConfig config)) {
            return AuthorContext.unresolvable();
        }
        String serverUrl = config.serverUrl();
        if (serverUrl == null || serverUrl.isBlank()) {
            return AuthorContext.unresolvable();
        }
        return new AuthorContext(
                identityResolver, workspaceId, serverUrl, active.get().getInstanceKey());
    }

    /** Memoises resolver lookups so each distinct author subject resolves at most once per batch. */
    private static final class AuthorContext {

        private final @Nullable OutlineIdentityResolver resolver;
        private final long workspaceId;
        private final @Nullable String serverUrl;
        private final @Nullable String teamId;
        private final Map<String, Optional<Long>> memberIdBySubject = new HashMap<>();

        private AuthorContext(
                @Nullable OutlineIdentityResolver resolver,
                long workspaceId,
                @Nullable String serverUrl,
                @Nullable String teamId) {
            this.resolver = resolver;
            this.workspaceId = workspaceId;
            this.serverUrl = serverUrl;
            this.teamId = teamId;
        }

        static AuthorContext unresolvable() {
            return new AuthorContext(null, 0L, null, null);
        }

        @Nullable
        Long memberIdFor(@Nullable String subject) {
            if (resolver == null || serverUrl == null || subject == null || subject.isBlank()) {
                return null;
            }
            return memberIdBySubject
                    .computeIfAbsent(subject, s -> resolver.resolveMemberId(workspaceId, serverUrl, teamId, s))
                    .orElse(null);
        }
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
