package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemChecksSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemUrlResolver;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ProductItemQuery {
    // Repository/file current sources can fetch Git and do not describe the frozen scan context.
    // A submitted review also embeds mutable parent context; its own events retain currentness authority.
    private static final Set<String> WORK_KINDS = Set.of("scm.issue", "scm.pull_request");
    private final ProductItemRepository rows;
    private final List<ItemSource> sources;
    private final List<ItemUrlResolver> urlResolvers;
    private final List<ItemChecksSource> checks;

    public Optional<ItemRef> find(long workspaceId, long itemId) {
        return rows.activeItem(workspaceId, itemId).map(row -> row.ref(workspaceId));
    }

    /** {@link #find} for many items, keyed by item id; an item that is gone or elsewhere is absent. */
    public Map<Long, ItemRef> findAll(long workspaceId, Collection<Long> itemIds) {
        if (itemIds.isEmpty()) return Map.of();
        Map<Long, ItemRef> refs = new HashMap<>();
        rows.items(workspaceId, itemIds).stream()
                .filter(row -> row.getRemovedAt() == null)
                .forEach(row -> refs.put(row.getId(), row.ref(workspaceId)));
        return refs;
    }

    public ItemRef require(long workspaceId, long itemId) {
        return find(workspaceId, itemId).orElseThrow(() -> new EntityNotFoundException("Item", Long.toString(itemId)));
    }

    /** The item kind's checks source and what it says for this review, or empty when the kind has no checks. */
    public Optional<ItemChecksSource> checks(ItemKind kind) {
        return checks.stream().filter(source -> source.kind().equals(kind)).findFirst();
    }

    public ItemSnapshot pin(ItemRef item, @Nullable ItemVersion version) {
        return source(item).pin(item, version);
    }

    /** The version, state and roles the occasion {@code eventId} recorded, as its source retained them. */
    public ItemSnapshot pinOccasion(ItemRef item, ItemVersion version, UUID eventId) {
        return source(item).pinOccasion(item, version, eventId);
    }

    public Optional<ItemSnapshot> current(ItemRef item) {
        return source(item).current(item);
    }

    private ItemSource source(ItemRef item) {
        var matched = sources.stream().filter(s -> s.supports(item)).toList();
        if (matched.size() != 1) throw new IllegalArgumentException("The item has no unambiguous source.");
        return matched.getFirst();
    }

    @Transactional(readOnly = true)
    public Optional<ItemRef> atUrl(long workspaceId, String url) {
        var matched = urlResolvers.stream()
                .flatMap(resolver -> resolver.resolve(workspaceId, url).stream())
                .limit(2)
                .toList();
        return matched.size() == 1 && current(matched.getFirst()).isPresent()
                ? Optional.of(matched.getFirst())
                : Optional.empty();
    }

    /**
     * The version of an issue's or a pull request's work as Hephaestus stores it now, read within the caller's
     * transaction. Any other kind is unknown without a read of its source. A capture failure propagates.
     */
    public CurrentWorkVersion currentWorkVersion(long workspaceId, long itemId) {
        var row = rows.items(workspaceId, List.of(itemId)).stream().findFirst().orElseThrow();
        if (!WORK_KINDS.contains(row.getKind())) return CurrentWorkVersion.Unknown.INSTANCE;
        if (row.getRemovedAt() != null) return CurrentWorkVersion.Removed.INSTANCE;
        var item = row.ref(workspaceId);
        try {
            return source(item)
                    .currentVersion(item)
                    .<CurrentWorkVersion>map(version -> new CurrentWorkVersion.Known(version.value()))
                    .orElse(CurrentWorkVersion.Unknown.INSTANCE);
        } catch (ItemCaptureException unavailable) {
            return switch (unavailable.code()) {
                case SOURCE_NOT_READY, VERSION_UNAVAILABLE, ITEM_NOT_VISIBLE, ITEM_REMOVED, CONSENT_WITHHELD ->
                    CurrentWorkVersion.Unknown.INSTANCE;
                case CAPTURE_FAILED -> throw unavailable;
                // Only an event review reads an occasion; the current work never does.
                case OCCASION_EVIDENCE_UNAVAILABLE ->
                    throw new IllegalStateException("The current work does not read an occasion", unavailable);
            };
        }
    }

    public sealed interface CurrentWorkVersion
            permits CurrentWorkVersion.Known, CurrentWorkVersion.Unknown, CurrentWorkVersion.Removed {
        record Known(String value) implements CurrentWorkVersion {}

        enum Unknown implements CurrentWorkVersion {
            INSTANCE
        }

        enum Removed implements CurrentWorkVersion {
            INSTANCE
        }
    }
}
