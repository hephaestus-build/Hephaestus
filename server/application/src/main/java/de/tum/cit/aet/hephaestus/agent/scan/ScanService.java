package de.tum.cit.aet.hephaestus.agent.scan;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemListRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLister;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.ListedItem;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScanItemVersions;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ScanExcludedItem;
import de.tum.cit.aet.hephaestus.practices.spi.ScanListedItem;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

/** Establishes the complete pinned inventory before creating any practice membership. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScanService {
    private final List<ItemLister> listers;
    private final List<ScanItemVersions> versions;
    private final ItemIndexService writer;
    private final ReviewScanStore store;
    private final ScanProperties limits;
    private final Clock clock;

    public static final int PAGE_SIZE = 1000;

    /**
     * The first page registers the listed items, so a concurrent sync write can fail it with a serialization or lock
     * conflict. Each attempt is a new transaction; a conflict that persists still ends the scan.
     */
    static final RetryPolicy LISTING_CONFLICTS = RetryPolicy.builder()
            .includes(ConcurrencyFailureException.class)
            .maxRetries(2)
            .delay(Duration.ofMillis(100))
            .build();

    private final RetryTemplate listingRetries = new RetryTemplate(LISTING_CONFLICTS);

    public PreparedInventory prepare(ReviewSnapshot review) {
        var kinds = review.practices().stream()
                .map(p -> Objects.requireNonNull(p.definition().scan()).itemKind())
                .distinct()
                .toList();
        if (kinds.isEmpty()) throw new EnumerationFailure(ReviewStopReason.NO_SCAN_PRACTICES);
        if (kinds.size() != 1) throw new EnumerationFailure(ReviewStopReason.ENUMERATION_FAILED);
        var matching = listers.stream()
                .filter(l -> l.supports(review.item()) && l.memberKinds().contains(kinds.getFirst()))
                .toList();
        if (matching.size() != 1) throw new EnumerationFailure(ReviewStopReason.ENUMERATION_FAILED);
        var composer = versions.stream()
                .filter(v -> v.kinds().contains(kinds.getFirst()))
                .toList();
        if (composer.size() != 1) throw new EnumerationFailure(ReviewStopReason.ENUMERATION_FAILED);
        var pinned = Objects.requireNonNull(review.version());
        var deadline = clock.instant().plus(limits.enumerationDeadline());
        List<ListedItem> inventory = new ArrayList<>();
        var paths = new HashSet<String>();
        var cursors = new HashSet<String>();
        long bytes = 0;
        @Nullable String cursor = null;
        do {
            if (!clock.instant().isBefore(deadline)) throw new EnumerationFailure(ReviewStopReason.ENUMERATION_LIMIT);
            var request = new ItemListRequest(review.item(), pinned, kinds.getFirst(), cursor, PAGE_SIZE);
            var page = listingRetries.invoke(() -> matching.getFirst().list(request));
            if (page.reason() != null)
                throw new EnumerationFailure(
                        "ENUMERATION_LIMIT".equals(page.reason())
                                ? ReviewStopReason.ENUMERATION_LIMIT
                                : ReviewStopReason.ENUMERATION_FAILED);
            for (var item : page.items()) {
                var registration = item.item();
                var path = registration.path();
                if (registration.workspaceId() != review.workspaceId()
                        || !Long.valueOf(review.item().itemId()).equals(registration.parentId())
                        || !registration.kind().equals(kinds.getFirst())
                        || path == null
                        || !paths.add(path)) throw new EnumerationFailure(ReviewStopReason.ENUMERATION_FAILED);
                bytes = Math.addExact(
                        bytes,
                        path.getBytes(StandardCharsets.UTF_8).length
                                + item.version().value().getBytes(StandardCharsets.UTF_8).length
                                + 128L);
                inventory.add(item);
                if (inventory.size() > limits.treeEntries() || bytes > limits.inventoryBytes())
                    throw new EnumerationFailure(ReviewStopReason.ENUMERATION_LIMIT);
            }
            if (page.complete()) {
                if (page.nextCursor() != null) throw new EnumerationFailure(ReviewStopReason.ENUMERATION_FAILED);
                break;
            }
            cursor = page.nextCursor();
            if (cursor == null || !cursors.add(cursor))
                throw new EnumerationFailure(ReviewStopReason.ENUMERATION_FAILED);
        } while (true);
        var files = new ArrayList<ItemRef>();
        var eligible = new ArrayList<ItemRef>();
        var exclusions = new ArrayList<ScanExcludedItem>();
        for (var entry : inventory) {
            var file = writer.upsert(entry.item());
            files.add(file);
            if (entry.exclusion() == null) eligible.add(file);
            else exclusions.add(new ScanExcludedItem(file, entry.exclusion(), entry.sizeBytes()));
        }
        var selections = new LinkedHashMap<Long, List<ScanListedItem>>();
        var matcher = new AntPathMatcher("/");
        for (var practice : review.practices()) {
            var definition = Objects.requireNonNull(practice.definition().scan());
            var selected = new ArrayList<ItemRef>();
            for (var file : eligible) {
                if (!clock.instant().isBefore(deadline))
                    throw new EnumerationFailure(ReviewStopReason.ENUMERATION_LIMIT);
                String path = Objects.requireNonNull(file.path());
                if (definition.include().stream().anyMatch(glob -> matcher.match(glob, path))
                        && definition.exclude().stream().noneMatch(glob -> matcher.match(glob, path))) {
                    selected.add(file);
                }
            }
            var composed = selected.isEmpty()
                    ? Map.<Long, ItemVersion>of()
                    : composer.getFirst().compose(selected, pinned, definition.context());
            if (!clock.instant().isBefore(deadline)) throw new EnumerationFailure(ReviewStopReason.ENUMERATION_LIMIT);
            selections.put(
                    practice.practiceId(),
                    selected.stream()
                            .map(file -> new ScanListedItem(file, Objects.requireNonNull(composed.get(file.itemId()))))
                            .toList());
        }
        return new PreparedInventory(files, selections, exclusions);
    }

    public void record(ReviewSnapshot review, PreparedInventory prepared) {
        store.inventory(
                review.workspaceId(),
                review.id(),
                prepared.files().stream().map(ItemRef::itemId).toList(),
                true);
        store.exclusions(review.workspaceId(), review.id(), prepared.exclusions());
        for (var selection : prepared.selections().entrySet()) {
            var selected = selection.getValue();
            if (selected.isEmpty()) store.list(review.workspaceId(), review.id(), selection.getKey(), List.of(), true);
            else
                for (int offset = 0; offset < selected.size(); offset += PAGE_SIZE) {
                    int end = Math.min(offset + PAGE_SIZE, selected.size());
                    store.list(
                            review.workspaceId(),
                            review.id(),
                            selection.getKey(),
                            selected.subList(offset, end),
                            end == selected.size());
                }
        }
    }

    public record PreparedInventory(
            List<ItemRef> files, Map<Long, List<ScanListedItem>> selections, List<ScanExcludedItem> exclusions) {
        public PreparedInventory {
            files = List.copyOf(files);
            selections = Map.copyOf(selections);
            exclusions = List.copyOf(exclusions);
        }
    }

    public static final class EnumerationFailure extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final ReviewStopReason reason;

        public EnumerationFailure(ReviewStopReason reason) {
            super("The pinned scan inventory could not be established");
            this.reason = reason;
        }

        public ReviewStopReason reason() {
            return reason;
        }
    }
}
