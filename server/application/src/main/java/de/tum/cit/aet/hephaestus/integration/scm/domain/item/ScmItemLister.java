package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemExclusionReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemListRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLister;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemPage;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ListedItem;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Enumerates a complete immutable tree before applying page boundaries. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmItemLister implements ItemLister {
    private final ScmItemService items;
    private final ScmGitTree tree;
    private final ScmFileVersions versions;

    @Override
    public ItemKind containerKind() {
        return ScmItemEvents.REPOSITORY;
    }

    @Override
    public Set<ItemKind> memberKinds() {
        return Set.of(ScmItemEvents.FILE);
    }

    @Override
    public boolean supports(ItemRef container) {
        return container.kind().equals(containerKind());
    }

    @Override
    public ItemPage list(ItemListRequest request) {
        if (!supports(request.container()) || !memberKinds().contains(request.memberKind()))
            throw new IllegalArgumentException("Unsupported SCM listing");
        // The pin authorized the repository and synced its mirror, outside any transaction.
        ItemSnapshot pinned = items.pin(request.container(), request.version());
        var key = new RepositoryKey(
                request.container().workspaceId(),
                Objects.requireNonNull(request.container().sourceId()));
        var inventory = tree.inventory(key, pinned.version().value());
        String identity = request.container().workspaceId()
                + "/"
                + request.container().itemId()
                + "/"
                + pinned.version().value()
                + "/";
        int offset = 0;
        if (request.cursor() != null) {
            try {
                String value = new String(Base64.getUrlDecoder().decode(request.cursor()), StandardCharsets.UTF_8);
                if (!value.startsWith(identity))
                    throw new IllegalArgumentException("Cursor belongs to another pinned listing");
                offset = Integer.parseInt(value.substring(identity.length()));
                if (offset < 0 || offset > inventory.size())
                    throw new IllegalArgumentException("Cursor is outside the pinned listing");
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid pinned listing cursor", e);
            }
        }
        int end = (int) Math.min((long) inventory.size(), (long) offset + Math.min(1000, request.limit()));
        List<ListedItem> page = inventory.subList(offset, end).stream()
                .map(entry -> new ListedItem(
                        new ItemRegistration(
                                request.container().workspaceId(),
                                ScmItemEvents.FILE,
                                null,
                                request.container().itemId(),
                                entry.path()),
                        versions.version(
                                entry.path(),
                                entry.objectId(),
                                entry.mode(),
                                List.of(),
                                List.of(),
                                pinned.version().value()),
                        entry.sizeBytes() > ScmGitTree.MAX_FILE_BYTES
                                ? ItemExclusionReason.FILE_TOO_LARGE
                                : entry.binary() ? ItemExclusionReason.BINARY_FILE : null,
                        entry.sizeBytes()))
                .toList();
        boolean complete = end == inventory.size();
        String cursor = complete
                ? null
                : Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString((identity + end).getBytes(StandardCharsets.UTF_8));
        return new ItemPage(page, cursor, complete, null);
    }
}
