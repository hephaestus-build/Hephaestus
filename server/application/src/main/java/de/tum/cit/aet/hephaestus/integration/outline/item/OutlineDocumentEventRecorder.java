package de.tum.cit.aet.hephaestus.integration.outline.item;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentEvents;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentSnapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutlineDocumentEventRecorder {
    private final ItemIndexService items;

    public OutlineDocumentEventRecorder(ItemIndexService items) {
        this.items = items;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> record(
            long workspaceId,
            @Nullable OutlineDocumentSnapshot document,
            @Nullable String outlineEventName,
            Instant occurredAt,
            FoundBy foundBy) {
        if (foundBy != FoundBy.WEBHOOK && foundBy != FoundBy.SYNC)
            throw new IllegalArgumentException("Provider events require webhook or sync discovery");
        if (document == null || document.isDeleted()) return Optional.empty();
        String hash = document.contentHash();
        if (hash == null || hash.isBlank()) return Optional.empty();
        ItemEventName event = switch (outlineEventName == null ? "" : outlineEventName) {
            case "documents.publish" -> DocumentEvents.PUBLISHED;
            case "documents.update" -> DocumentEvents.UPDATED;
            case "documents.archive" -> DocumentEvents.ARCHIVED;
            default -> null;
        };
        if (event == null) return Optional.empty();
        var item = items.upsert(new ItemRegistration(workspaceId, DocumentEvents.DOCUMENT, document.id(), null, null));
        var version = DocumentEvents.version(
                document.title(), hash, document.archivedAt() != null, document.createdBySubject());
        return Optional.of(items.record(new ItemEventInput(
                workspaceId, item.itemId(), event, version.value(), occurredAt, foundBy, null, null)));
    }
}
