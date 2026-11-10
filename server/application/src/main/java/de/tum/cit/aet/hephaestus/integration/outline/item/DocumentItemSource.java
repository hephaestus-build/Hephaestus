package de.tum.cit.aet.hephaestus.integration.outline.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSourceAccess;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.RoleHolder;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentContentSource;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentEvents;
import de.tum.cit.aet.hephaestus.integration.outline.documentation.OutlineDocumentPermission;
import de.tum.cit.aet.hephaestus.integration.outline.documentation.OutlineDocumentProjector;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true")
public class DocumentItemSource implements ItemSource, ItemSourceAccess {
    private final OutlineDocumentRepository documents;
    private final OutlineDocumentProjector projection;
    private final DocumentContentSource content;
    private final ObjectMapper mapper;
    private final ConnectionService connections;
    private final OutlineDocumentPermission permission;

    public DocumentItemSource(
            OutlineDocumentRepository documents,
            OutlineDocumentProjector projection,
            DocumentContentSource content,
            ObjectMapper mapper,
            ConnectionService connections,
            OutlineDocumentPermission permission) {
        this.documents = documents;
        this.projection = projection;
        this.content = content;
        this.mapper = mapper;
        this.connections = connections;
        this.permission = permission;
    }

    @Override
    public Set<ItemKind> kinds() {
        return Set.of(DocumentEvents.DOCUMENT);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean permits(ItemRef item, SourceUsePurpose purpose) {
        return permitted(item, SourceReadAudience.PROCESSING);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean permits(ItemRef item, SourceUsePurpose purpose, SourceReadAudience audience) {
        return permitted(item, Objects.requireNonNull(audience));
    }

    private boolean permitted(ItemRef item, SourceReadAudience audience) {
        if (!supports(item)) return false;
        Long sourceId = item.sourceId();
        if (sourceId == null) return false;
        // Processing keeps the check capture and context use; only the human read asks the typed one.
        return audience == SourceReadAudience.PROCESSING
                ? permission.permits(item.workspaceId(), sourceId)
                : permission.permits(item.workspaceId(), sourceId, audience);
    }

    @Override
    public boolean supports(ItemRef item) {
        return DocumentEvents.DOCUMENT.equals(item.kind()) && item.sourceId() != null;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ItemLabel> labels(long workspaceId, Collection<ItemRef> items) {
        Map<Long, ItemLabel> labels = new HashMap<>();
        for (ItemRef item : items) {
            if (item.workspaceId() != workspaceId || !supports(item)) continue;
            Long sourceId = item.sourceId();
            if (sourceId == null) continue;
            for (var label : documents.findLabels(workspaceId, List.of(sourceId))) {
                String title = label.getTitle();
                labels.put(
                        item.itemId(),
                        new ItemLabel(
                                item.itemId(),
                                title == null || title.isBlank() ? "Document" : title,
                                title == null || title.isBlank() ? "Document" : title,
                                "document",
                                null,
                                label.getCollectionName(),
                                null,
                                IntegrationKind.OUTLINE));
            }
        }
        return Map.copyOf(labels);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ItemSnapshot> current(ItemRef item) {
        return currentSnapshot(item);
    }

    private Optional<ItemSnapshot> currentSnapshot(ItemRef item) {
        if (!supports(item)) return Optional.empty();
        Long sourceId = item.sourceId();
        if (sourceId == null || !permission.permits(item.workspaceId(), sourceId)) return Optional.empty();
        return documents
                .findByWorkspaceIdAndIdForProjection(item.workspaceId(), sourceId)
                .filter(doc -> !doc.isDeleted() && doc.getContentHash() != null)
                .map(doc -> snapshot(item, doc));
    }

    private ItemSnapshot snapshot(ItemRef item, OutlineDocument doc) {
        String hash = doc.getContentHash();
        if (hash == null)
            throw new ItemCaptureException(ItemCaptureCode.SOURCE_NOT_READY, "Document content is unavailable");
        var connection = connections
                .findInWorkspace(item.workspaceId(), doc.getConnectionId())
                .filter(row -> row.getKind() == IntegrationKind.OUTLINE && row.getState() == IntegrationState.ACTIVE)
                .orElseThrow(() -> new ItemCaptureException(
                        ItemCaptureCode.ITEM_NOT_VISIBLE, "Outline is not connected for this document"));
        var roles = permission.identities(item.workspaceId(), doc.getId()).stream()
                .filter(identity -> identity.subject().equals(doc.getCreatedBySubject()))
                .map(identity -> new RoleHolder(AboutRole.AUTHOR, identity, true, false))
                .distinct()
                .toList();
        String title = doc.getTitle();
        return new ItemSnapshot(
                item,
                DocumentEvents.version(title, hash, doc.getArchivedAt() != null, doc.getCreatedBySubject()),
                new IntegrationRef(
                        IntegrationKind.OUTLINE,
                        item.workspaceId(),
                        connection.getInstanceKey(),
                        doc.getConnectionId()),
                Map.of("state", doc.getArchivedAt() == null ? "ACTIVE" : "ARCHIVED"),
                roles,
                new ItemLabel(
                        item.itemId(),
                        title == null || title.isBlank() ? "Document" : title,
                        title == null || title.isBlank() ? "Document" : title,
                        "document",
                        null,
                        documents.findLabels(item.workspaceId(), List.of(doc.getId())).stream()
                                .map(OutlineDocumentRepository.DocumentLabel::getCollectionName)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse(null),
                        null,
                        IntegrationKind.OUTLINE));
    }

    @Override
    @Transactional(readOnly = true)
    public ItemSnapshot pin(ItemRef item, @Nullable ItemVersion requested) {
        ItemSnapshot pinned = currentSnapshot(item)
                .orElseThrow(
                        () -> new ItemCaptureException(ItemCaptureCode.ITEM_NOT_VISIBLE, "Document is not available"));
        if (requested != null && !requested.equals(pinned.version()))
            throw new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "Document version changed");
        return pinned;
    }

    @Override
    public JsonNode record(ItemSnapshot pinned, Map<String, byte[]> capturedFiles) {
        if (!supports(pinned.item())
                || pinned.integration().kind() != IntegrationKind.OUTLINE
                || pinned.item().workspaceId() != pinned.integration().workspaceId())
            throw new ItemCaptureException(
                    ItemCaptureCode.ITEM_NOT_VISIBLE, "Captured document source does not match the pinned item");
        byte[] bytes = capturedFiles.get(DocumentContentSource.METADATA_KEY);
        if (bytes == null)
            throw new ItemCaptureException(ItemCaptureCode.SOURCE_NOT_READY, "Captured document record is unavailable");
        try {
            JsonNode record = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(bytes);
            if (!record.isObject())
                throw new ItemCaptureException(
                        ItemCaptureCode.SOURCE_NOT_READY, "Captured document record must be an object");
            return record;
        } catch (JacksonException failure) {
            throw new ItemCaptureException(
                    ItemCaptureCode.SOURCE_NOT_READY, "Captured document record is invalid", failure);
        }
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = ItemCaptureException.class)
    public CaptureContribution capture(ItemSnapshot pinned, CaptureRequest request) {
        if (!DocumentEvents.DOCUMENT.equals(pinned.item().kind())
                || pinned.integration().kind() != IntegrationKind.OUTLINE
                || pinned.item().workspaceId() != pinned.integration().workspaceId())
            throw new ItemCaptureException(
                    ItemCaptureCode.ITEM_NOT_VISIBLE, "Document source does not match the pinned item");
        if (!request.sources().contains(DocumentContentSource.SOURCE))
            return content.capture(request, pinned.version(), null, new byte[0], null, SourceAbsenceReason.NOT_FOUND);
        Long sourceId = pinned.item().sourceId();
        if (sourceId == null)
            throw new ItemCaptureException(ItemCaptureCode.SOURCE_NOT_READY, "Document source id is required");
        if (!permission.permits(pinned.item().workspaceId(), sourceId))
            return withheld(SourceAbsenceReason.ACCESS_NOT_PERMITTED);
        var readable =
                documents.findByWorkspaceIdAndIdForProjection(pinned.item().workspaceId(), sourceId);
        if (readable.isEmpty()) {
            return documents.existsByWorkspaceIdAndId(pinned.item().workspaceId(), sourceId)
                    ? withheld(SourceAbsenceReason.ACCESS_NOT_PERMITTED)
                    : CaptureContribution.unavailable(
                            Set.of(DocumentContentSource.SOURCE), SourceAbsenceReason.NOT_FOUND);
        }
        var doc = readable.get();
        if (doc.isDeleted())
            return CaptureContribution.unavailable(Set.of(DocumentContentSource.SOURCE), SourceAbsenceReason.NOT_FOUND);
        if (!snapshot(pinned.item(), doc).version().equals(pinned.version())
                || !snapshot(pinned.item(), doc).roles().equals(pinned.roles()))
            throw new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "Document version changed");
        var projected = projection
                .documentById(pinned.item().workspaceId(), sourceId)
                .orElseThrow(() ->
                        new ItemCaptureException(ItemCaptureCode.CONSENT_WITHHELD, "Document evidence is withheld"));
        var metadata = mapper.createObjectNode();
        metadata.put("id", Long.toString(pinned.item().itemId()));
        metadata.put("sourceId", doc.getId().toString());
        metadata.put("title", doc.getTitle());
        metadata.put("collection", projected.collectionName());
        metadata.put("collectionSlug", doc.getCollectionSlug());
        metadata.put("slug", doc.getSlug());
        metadata.put("createdBy", projected.createdByName());
        metadata.put(
                "createdByAccountId",
                projected.createdByAccountId() == null
                        ? null
                        : projected.createdByAccountId().toString());
        metadata.put("updatedBy", projected.updatedByName());
        metadata.put(
                "updatedByAccountId",
                projected.updatedByAccountId() == null
                        ? null
                        : projected.updatedByAccountId().toString());
        metadata.put(
                "createdAt",
                projected.createdAt() == null ? null : projected.createdAt().toString());
        metadata.put(
                "updatedAt",
                projected.updatedAt() == null ? null : projected.updatedAt().toString());
        metadata.put("archived", doc.getArchivedAt() != null);
        return content.capture(
                request,
                pinned.version(),
                projected.bodyMarkdown(),
                mapper.writeValueAsBytes(metadata),
                doc.getOutlineUpdatedAt(),
                SourceAbsenceReason.CONTENT_EVICTED);
    }

    private static CaptureContribution withheld(SourceAbsenceReason reason) {
        return new CaptureContribution(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(DocumentContentSource.SOURCE, new SourceCaptureState.Redacted(reason)),
                Map.of(),
                null,
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
