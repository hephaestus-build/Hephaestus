package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScmItemAccess {
    private final ItemIndexService items;
    private static final ScmItemQueryRepository.KindNames KINDS = new ScmItemQueryRepository.KindNames(
            ScmItemEvents.REPOSITORY.value(),
            ScmItemEvents.FILE.value(),
            ScmItemEvents.PULL_REQUEST_REVIEW.value(),
            ScmItemEvents.PULL_REQUEST.value(),
            ScmItemEvents.ISSUE.value());
    private final ScmItemQueryRepository queries;

    public void requireAll(long workspaceId, Collection<ItemRef> refs) {
        if (refs.isEmpty()) return;
        var ids = refs.stream().map(ItemRef::itemId).distinct().toList();
        var actual = items.findAll(workspaceId, ids);
        for (var ref : refs)
            if (ref.workspaceId() != workspaceId || !ref.equals(actual.get(ref.itemId())))
                throw new ItemCaptureException(
                        ItemCaptureCode.ITEM_NOT_VISIBLE, "Item reference does not match its workspace identity");
    }

    /**
     * The stored facts of an issue or a pull request of {@code kind} that its version and source relation read,
     * without loading the work; empty for another kind or a row of another type.
     */
    public Optional<ScmItemQueryRepository.WorkFacts> work(ItemKind kind, long sourceId) {
        return queries.work(kind.value(), sourceId);
    }

    public List<Issue> closingIssues(long pullRequestId) {
        return queries.closingIssues(pullRequestId);
    }

    public ItemRef require(ItemRef item) {
        ItemRef canonical = find(item.workspaceId(), item.itemId())
                .orElseThrow(() -> new ItemCaptureException(
                        ItemCaptureCode.ITEM_NOT_VISIBLE, "Item is unavailable in this workspace"));
        if (!canonical.equals(item))
            throw new ItemCaptureException(
                    ItemCaptureCode.ITEM_NOT_VISIBLE, "Item reference does not match its workspace identity");
        return canonical;
    }

    public Optional<ItemRef> find(long workspaceId, long itemId) {
        return items.find(workspaceId, itemId).map(item -> {
            if (item.removed()) throw new ItemCaptureException(ItemCaptureCode.ITEM_REMOVED, "Item has been removed");
            return item.ref();
        });
    }

    public ItemRef parent(ItemRef item, ItemKind kind) {
        ItemRef parent = find(item.workspaceId(), Objects.requireNonNull(item.parentId(), "Item has no parent"))
                .orElseThrow(
                        () -> new ItemCaptureException(ItemCaptureCode.ITEM_NOT_VISIBLE, "Parent item is unavailable"));
        if (!parent.kind().equals(kind))
            throw new ItemCaptureException(ItemCaptureCode.ITEM_NOT_VISIBLE, "Parent has the wrong kind");
        return parent;
    }

    public Map<Long, ItemLabel> labels(long workspaceId, Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, ItemLabel> labels = new HashMap<>();
        for (var row : queries.labels(workspaceId, List.copyOf(ids), KINDS)) {
            labels.put(
                    row.getId(),
                    new ItemLabel(
                            row.getId(),
                            nativeLabel(
                                    IntegrationKind.valueOf(row.getProviderType()),
                                    row.getNoun(),
                                    row.getNumber(),
                                    row.getTitle()),
                            row.getTitle(),
                            row.getNoun(),
                            row.getNumber(),
                            row.getContainer(),
                            row.getUrl(),
                            IntegrationKind.valueOf(row.getProviderType())));
        }
        return Map.copyOf(labels);
    }

    static String nativeLabel(IntegrationKind provider, String noun, @Nullable Integer number, String title) {
        if (number == null) return title;
        String prefix = provider == IntegrationKind.GITLAB && !noun.equals("issue") ? "!" : "#";
        return (noun.equals("review") ? "Review of " : "") + prefix + number;
    }
}
