package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemChecksSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemFingerprintReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemUrlResolver;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
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
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ProductItemQuery {
    // Repository/file current sources can fetch Git and do not describe the frozen scan context.
    // A submitted review also embeds mutable parent context; its own events retain currentness authority.
    private static final Set<String> WORK_KINDS = Set.of("scm.issue", "scm.pull_request");
    private final ProductItemRepository rows;
    private final List<ItemSource> sources;
    private final List<ItemUrlResolver> urlResolvers;
    private final List<ItemChecksSource> checks;
    private final ObjectMapper mapper;
    private final List<ItemFingerprintReader> fingerprintReaders;

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
                case CAPTURE_FAILED, CHECKOUT_UNVERIFIED -> throw unavailable;
                // Only an event review reads an occasion; the current work never does.
                case OCCASION_EVIDENCE_UNAVAILABLE ->
                    throw new IllegalStateException("The current work does not read an occasion", unavailable);
            };
        }
    }

    /**
     * Fingerprints of the currently readable source records, without a sandbox or a provider write.
     *
     * @param occasion the occasion that the capture being compared read: {@link ReviewSnapshot#occasion()}
     */
    public Map<SourceKind, String> fingerprints(ItemRef item, Set<SourceKind> kinds, @Nullable UUID occasion) {
        if (kinds.isEmpty()) return Map.of();
        var matching =
                sources.stream().filter(candidate -> candidate.supports(item)).toList();
        if (matching.size() != 1) return Map.of();
        try {
            var readers = fingerprintReaders.stream()
                    .filter(reader -> reader.supports(item))
                    .toList();
            if (readers.size() > 1) return Map.of();
            if (!readers.isEmpty()) return readers.getFirst().fingerprints(item, kinds, occasion, mapper);
            var source = matching.getFirst();
            var current = source.current(item);
            return current.isEmpty()
                    ? Map.of()
                    // A source without its own reader captures only rows that this server stores.
                    : ItemSource.recordFingerprints(source::capture, current.orElseThrow(), kinds, occasion, mapper);
        } catch (ItemCaptureException unavailable) {
            return Map.of();
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
