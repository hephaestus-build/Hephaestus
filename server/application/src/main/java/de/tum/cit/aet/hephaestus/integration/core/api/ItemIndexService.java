package de.tum.cit.aet.hephaestus.integration.core.api;

import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ItemIndexService {
    private final ItemRepository repository;
    private final ItemEventService events;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<ItemRef> findSource(long workspaceId, String kind, long sourceId) {
        return repository.findSource(workspaceId, kind, sourceId).map(ItemIndexService::ref);
    }

    /** The workspace's items among {@code itemIds}, keyed by id; an unknown id is absent. */
    @Transactional(readOnly = true)
    public Map<Long, ItemRef> findAll(long workspaceId, Collection<Long> itemIds) {
        var found = new HashMap<Long, ItemRef>();
        repository
                .findByWorkspaceIdAndIdIn(workspaceId, List.copyOf(itemIds))
                .forEach(item -> found.put(item.getId(), ref(item)));
        return Map.copyOf(found);
    }

    /** The workspace's item, removed or not. */
    @Transactional(readOnly = true)
    public Optional<IndexedItem> find(long workspaceId, long itemId) {
        return repository
                .findByIdAndWorkspaceId(itemId, workspaceId)
                .map(item -> new IndexedItem(ref(item), item.getRemovedAt() != null));
    }

    public record IndexedItem(ItemRef ref, boolean removed) {}

    public ItemRef upsert(ItemRegistration registration) {
        Long parentId = registration.parentId();
        if (parentId != null
                && repository
                        .findByIdAndWorkspaceId(parentId, registration.workspaceId())
                        .isEmpty()) throw new IllegalArgumentException("Parent item does not belong to the workspace");
        repository.insertIfAbsent(
                registration.workspaceId(),
                registration.kind().value(),
                registration.sourceId(),
                parentId,
                registration.path(),
                clock.instant());
        Long sourceId = registration.sourceId();
        Item item = sourceId != null
                ? repository
                        .findSource(
                                registration.workspaceId(), registration.kind().value(), sourceId)
                        .orElseThrow()
                : repository
                        .findByWorkspaceIdAndParentIdAndPath(
                                registration.workspaceId(),
                                Objects.requireNonNull(parentId),
                                Objects.requireNonNull(registration.path()))
                        .orElseThrow();
        if (!item.getKind().equals(registration.kind()) || !Objects.equals(item.getParentId(), parentId))
            throw new IllegalArgumentException("Item identity and parent are immutable");
        return ref(item);
    }

    /** Holds the workspace item lock until the caller transaction ends. Requires an active transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long workspaceId, long itemId) {
        if (repository.lockByIdAndWorkspaceId(itemId, workspaceId).isEmpty()) {
            throw new IllegalArgumentException("Item does not belong to the workspace");
        }
    }

    public UUID record(ItemEventInput event) {
        return events.record(event);
    }

    public boolean remove(long workspaceId, long itemId, Instant at) {
        boolean removed = repository.remove(workspaceId, itemId, at) == 1;
        // A removed item keeps its occasions' audit facts, not the words and people they retained.
        if (removed) events.forgetEvidence(workspaceId, itemId);
        return removed;
    }

    public boolean restore(long workspaceId, long itemId) {
        return repository.restore(workspaceId, itemId) == 1;
    }

    public static ItemRef ref(Item item) {
        return new ItemRef(
                item.getWorkspaceId(),
                item.getId(),
                item.getKind(),
                item.getSourceId(),
                item.getParentId(),
                item.getPath());
    }
}
