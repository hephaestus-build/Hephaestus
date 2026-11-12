package de.tum.cit.aet.hephaestus.integration.outline.documentation;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentProjection;
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
 * Missing or evicted documents project as absence markers; reads must not alter materialization state.
 * Resolve authors through {@link OutlineIdentityResolver}; unlinked authors retain names without account ids.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
@Transactional(readOnly = true)
public class OutlineDocumentProjector implements DocumentProjection {

    private final OutlineDocumentRepository documentRepository;
    private final OutlineCollectionRepository collectionRepository;
    private final ConnectionService connectionService;
    private final OutlineIdentityResolver identityResolver;
    private final OutlineDocumentSelector documentSelector;
    private final PersonDataCopyRecorder personCopies;
    private final OutlineDocumentPermission permission;

    public OutlineDocumentProjector(
            OutlineDocumentRepository documentRepository,
            OutlineCollectionRepository collectionRepository,
            ConnectionService connectionService,
            OutlineIdentityResolver identityResolver,
            OutlineDocumentSelector documentSelector,
            PersonDataCopyRecorder personCopies,
            OutlineDocumentPermission permission) {
        this.documentRepository = documentRepository;
        this.collectionRepository = collectionRepository;
        this.connectionService = connectionService;
        this.identityResolver = identityResolver;
        this.documentSelector = documentSelector;
        this.personCopies = personCopies;
        this.permission = permission;
    }

    @Override
    public boolean workspaceReadable(long workspaceId) {
        return permission.workspaceReadable(workspaceId);
    }

    @Override
    public List<ProjectedDocument> documentsForWorkspace(long workspaceId) {
        if (!permission.workspaceReadable(workspaceId)) return List.of();
        Map<Long, AuthorContext> authors = new HashMap<>();
        Map<CollectionKey, String> collectionNames = collectionNames(workspaceId);
        return documentRepository.findForProjection(workspaceId, Pageable.unpaged()).stream()
                .filter(doc -> permission.permits(workspaceId, doc.getId()))
                .map(doc -> project(doc, authorContext(doc, authors), collectionNames))
                .toList();
    }

    @Override
    public Optional<ProjectedDocument> documentById(long workspaceId, long documentId) {
        if (!permission.permits(workspaceId, documentId)) return Optional.empty();
        return documentRepository
                .findByWorkspaceIdAndIdForProjection(workspaceId, documentId)
                .map(doc -> project(doc, authorContext(doc, new HashMap<>()), collectionNames(workspaceId)));
    }

    @Override
    public List<ProjectedDocument> documentsByReference(long workspaceId, Collection<String> documentRefs) {
        if (!permission.workspaceReadable(workspaceId)) return List.of();
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
        Map<Long, AuthorContext> authors = new HashMap<>();
        Map<CollectionKey, String> collectionNames = collectionNames(workspaceId);
        return documentRepository.findByWorkspaceIdAndReferenceIn(workspaceId, tokens).stream()
                .filter(doc -> permission.permits(workspaceId, doc.getId()))
                .map(doc -> project(doc, authorContext(doc, authors), collectionNames))
                .toList();
    }

    @Override
    public List<ProjectedDocument> searchDocuments(long workspaceId, String queryText, int limit) {
        if (!permission.workspaceReadable(workspaceId)) return List.of();
        List<OutlineDocument> hits = documentSelector.select(workspaceId, queryText, limit);
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<Long, AuthorContext> authors = new HashMap<>();
        Map<CollectionKey, String> collectionNames = collectionNames(workspaceId);
        return hits.stream()
                .filter(doc -> permission.permits(workspaceId, doc.getId()))
                .map(doc -> project(doc, authorContext(doc, authors), collectionNames))
                .toList();
    }

    /** Maps a mirrored row to the agent view; a tombstoned/evicted document serves a null body. */
    private ProjectedDocument project(
            OutlineDocument doc, AuthorContext authors, Map<CollectionKey, String> collectionNames) {
        Optional<Connection> source = connectionService.findInWorkspace(doc.getWorkspaceId(), doc.getConnectionId());
        String url = source.map(connection -> documentUrl(connection, doc)).orElse(null);
        source.ifPresent(connection -> {
            if (connection.getConfig() instanceof ConnectionConfig.OutlineConfig config && config.serverUrl() != null) {
                var subjects = new LinkedHashSet<String>();
                if (doc.getCreatedBySubject() != null) subjects.add(doc.getCreatedBySubject());
                if (doc.getUpdatedBySubject() != null) subjects.add(doc.getUpdatedBySubject());
                if (doc.getCollaboratorSubjects() != null) subjects.addAll(doc.getCollaboratorSubjects());
                subjects.stream()
                        .filter(subject -> !subject.isBlank())
                        .forEach(subject -> personCopies.recordIdentity(new PersonCopyIdentity(
                                "OUTLINE", config.serverUrl(), subject, connection.getInstanceKey())));
            }
        });
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
                authors.accountIdFor(doc.getCreatedBySubject()),
                doc.getUpdatedByName(),
                doc.getUpdatedBySubject(),
                authors.accountIdFor(doc.getUpdatedBySubject()),
                collaborators(doc, authors),
                doc.isArchived(),
                collectionNames.get(new CollectionKey(doc.getConnectionId(), doc.getCollectionId())),
                doc.getLastMaterializedAt(),
                doc.getDocumentId(),
                url);
    }

    /** Outline links a document as {@code <server>/doc/<slug>}. The mirrored slug is that last path segment. */
    private static @Nullable String documentUrl(Connection connection, OutlineDocument doc) {
        if (!(connection.getConfig() instanceof ConnectionConfig.OutlineConfig config)
                || config.serverUrl() == null
                || config.serverUrl().isBlank()
                || doc.getSlug() == null
                || doc.getSlug().isBlank()
                || doc.isDeleted()) {
            return null;
        }
        return config.serverUrl().replaceAll("/+$", "") + "/doc/" + doc.getSlug();
    }

    private Map<CollectionKey, String> collectionNames(long workspaceId) {
        Map<CollectionKey, String> names = new HashMap<>();
        for (OutlineCollection collection : collectionRepository.findByWorkspaceIdOrderByCreatedAtAsc(workspaceId)) {
            if (collection.getName() != null) {
                names.put(
                        new CollectionKey(collection.getConnectionId(), collection.getCollectionId()),
                        collection.getName());
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
            result.add(new ProjectedDocument.Collaborator(subject, name, authors.accountIdFor(subject)));
        }
        return List.copyOf(result);
    }

    // Cache author resolution per connection because native identities are tenant-specific.
    private AuthorContext authorContext(OutlineDocument document, Map<Long, AuthorContext> contexts) {
        return contexts.computeIfAbsent(document.getConnectionId(), ignored -> {
            var connection = connectionService.findInWorkspace(document.getWorkspaceId(), document.getConnectionId());
            if (connection.isEmpty()
                    || !(connection.get().getConfig() instanceof ConnectionConfig.OutlineConfig config)
                    || config.serverUrl() == null
                    || config.serverUrl().isBlank()) return AuthorContext.unresolvable();
            return new AuthorContext(
                    identityResolver,
                    document.getWorkspaceId(),
                    config.serverUrl(),
                    connection.get().getInstanceKey());
        });
    }

    private record CollectionKey(long connectionId, String collectionId) {}

    /** Memoises resolver lookups so each distinct author subject resolves at most once per batch. */
    private static final class AuthorContext {

        private final @Nullable OutlineIdentityResolver resolver;
        private final long workspaceId;
        private final @Nullable String serverUrl;
        private final @Nullable String teamId;
        private final Map<String, Optional<Long>> accountIdBySubject = new HashMap<>();

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
        Long accountIdFor(@Nullable String subject) {
            if (resolver == null || serverUrl == null || subject == null || subject.isBlank()) {
                return null;
            }
            return accountIdBySubject
                    .computeIfAbsent(subject, s -> resolver.resolveAccountId(workspaceId, serverUrl, teamId, s))
                    .orElse(null);
        }
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
