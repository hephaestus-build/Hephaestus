package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScanItemVersions;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmFileVersionComposer implements ScanItemVersions {
    private static final int ITEM_BATCH_SIZE = 1000;
    private final ScmItemAccess items;
    private final ScmRepositoryAccess access;
    private final RepositoryRepository repositories;
    private final ScmGitTree tree;
    private final ScmFileVersions versions;

    @Override
    public Set<ItemKind> kinds() {
        return Set.of(ScmItemEvents.FILE);
    }

    @Transactional(readOnly = true)
    @Override
    public Map<Long, ItemVersion> compose(List<ItemRef> selected, ItemVersion containerVersion, List<String> context) {
        if (selected.isEmpty()) return Map.of();
        var first = selected.getFirst();
        for (int offset = 0; offset < selected.size(); offset += ITEM_BATCH_SIZE)
            items.requireAll(
                    first.workspaceId(), selected.subList(offset, Math.min(offset + ITEM_BATCH_SIZE, selected.size())));
        for (var item : selected)
            if (!kinds().contains(item.kind()) || !Objects.equals(first.parentId(), item.parentId()))
                throw new IllegalArgumentException("Selected files must belong to one pinned repository");
        var pinned = inventory(first, containerVersion.value());
        var composed = new LinkedHashMap<Long, ItemVersion>();
        for (var item : selected)
            composed.put(
                    item.itemId(),
                    pinned.version(versions, Objects.requireNonNull(item.path()), context, containerVersion.value()));
        return Map.copyOf(composed);
    }

    @Transactional(readOnly = true)
    @Override
    public List<String> contextPaths(ItemRef item, ItemVersion assignedVersion, List<String> context) {
        String commit = assignedVersion.baseVersion();
        if (commit == null
                || !commit.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                || ScmFileVersions.parse(assignedVersion).isEmpty())
            throw new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "Assigned file version is unavailable");
        var pinned = inventory(item, commit);
        String path = Objects.requireNonNull(item.path());
        var actual = pinned.version(versions, path, context, commit);
        if (!actual.equals(assignedVersion))
            throw new ItemCaptureException(
                    ItemCaptureCode.VERSION_UNAVAILABLE,
                    "The selected context does not match the assigned file version");
        return pinned.contextPaths(path, context);
    }

    private ScmGitTree.Inventory inventory(ItemRef item, String commit) {
        items.require(item);
        if (!kinds().contains(item.kind())) throw new IllegalArgumentException("Unsupported composed item kind");
        var holder = items.parent(item, ScmItemEvents.REPOSITORY);
        var repository =
                repositories.findById(Objects.requireNonNull(holder.sourceId())).orElseThrow();
        var key = access.prepare(item.workspaceId(), repository);
        var inventory = tree.index(key, commit);
        inventory.assigned(Objects.requireNonNull(item.path()));
        return inventory;
    }
}
