package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemListRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLister;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemPage;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ListedItem;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** A dated-work listing retains the membership and versions established by its first read. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmWorkItemLister implements ItemLister {
    private static final int MAX_CURSOR_BYTES = 16 * 1024 * 1024;

    public record Cursor(
            long workspaceId,
            long holderId,
            String holderVersion,
            ItemKind memberKind,
            int offset,
            List<ListedItem> items) {
        public Cursor {
            if (workspaceId <= 0 || holderId <= 0 || holderVersion.isBlank() || offset < 0 || offset > items.size())
                throw new IllegalArgumentException("Invalid work cursor");
            items = List.copyOf(items);
        }
    }

    private final ScmItemService sources;
    private final ScmWorkItemIndex work;
    private final ObjectMapper mapper;
    private final ScmItemQueryRepository queries;
    private final PlatformTransactionManager transactions;

    @Override
    public ItemKind containerKind() {
        return ScmItemEvents.REPOSITORY;
    }

    @Override
    public Set<ItemKind> memberKinds() {
        return Set.of(ScmItemEvents.ISSUE, ScmItemEvents.PULL_REQUEST, ScmItemEvents.PULL_REQUEST_REVIEW);
    }

    @Override
    public boolean supports(ItemRef container) {
        return container.kind().equals(containerKind());
    }

    /** Pins the repository first, since its pin syncs the mirror outside any transaction, then lists in one snapshot. */
    @Override
    public ItemPage list(ItemListRequest request) {
        if (!supports(request.container()) || !memberKinds().contains(request.memberKind()))
            throw new IllegalArgumentException("Unsupported SCM work listing");
        sources.pin(request.container(), request.version());
        var snapshot = new TransactionTemplate(transactions);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return Objects.requireNonNull(snapshot.execute(status -> page(request)));
    }

    private ItemPage page(ItemListRequest request) {
        long repositoryId = Objects.requireNonNull(request.container().sourceId());
        Cursor cursor;
        if (request.cursor() == null) {
            List<ListedItem> items = new ArrayList<>();
            var rows = request.memberKind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)
                    ? queries.listReviews(repositoryId)
                    : queries.listWork(
                            repositoryId, request.memberKind().equals(ScmItemEvents.ISSUE) ? "ISSUE" : "PULL_REQUEST");
            for (var item : work.register(request.container(), request.memberKind(), rows)) {
                var registration =
                        new ItemRegistration(item.workspaceId(), item.kind(), item.sourceId(), item.parentId(), null);
                items.add(new ListedItem(registration, sources.pin(item, null).version()));
            }
            cursor = new Cursor(
                    request.container().workspaceId(),
                    request.container().itemId(),
                    request.version().value(),
                    request.memberKind(),
                    0,
                    items);
        } else {
            try {
                if (request.cursor().length() > MAX_CURSOR_BYTES * 2)
                    throw new IllegalArgumentException("Work cursor exceeds its size limit");
                byte[] bytes = Base64.getUrlDecoder().decode(request.cursor());
                if (bytes.length > MAX_CURSOR_BYTES)
                    throw new IllegalArgumentException("Work cursor exceeds its size limit");
                cursor = mapper.readValue(bytes, Cursor.class);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid work listing cursor", e);
            }
            if (cursor.workspaceId() != request.container().workspaceId()
                    || cursor.holderId() != request.container().itemId()
                    || !cursor.holderVersion().equals(request.version().value())
                    || !cursor.memberKind().equals(request.memberKind()))
                throw new IllegalArgumentException("Cursor belongs to another pinned listing");
            var sourceIds = cursor.items().stream()
                    .map(entry -> entry.item().sourceId())
                    .filter(Objects::nonNull)
                    .toList();
            var owners = sourceIds.isEmpty()
                    ? Map.<Long, ScmItemQueryRepository.WorkRow>of()
                    : queries.owners(sourceIds, request.memberKind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)).stream()
                            .collect(
                                    Collectors.toMap(ScmItemQueryRepository.WorkRow::getSourceId, Function.identity()));
            for (var entry : cursor.items()) {
                var registration = entry.item();
                if (registration.workspaceId() != cursor.workspaceId()
                        || !registration.kind().equals(cursor.memberKind())
                        || registration.sourceId() == null
                        || registration.path() != null)
                    throw new IllegalArgumentException("Work cursor contains a foreign item");
                if (!registration.kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)
                        && !Objects.equals(
                                registration.parentId(), request.container().itemId()))
                    throw new IllegalArgumentException("Work cursor contains a foreign parent");
                if (registration.kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)) {
                    var parents = queries.reviewParents(
                            cursor.workspaceId(),
                            ScmItemEvents.PULL_REQUEST.value(),
                            Objects.requireNonNull(registration.sourceId()));
                    if (parents.size() != 1 || !Objects.equals(parents.getFirst(), registration.parentId()))
                        throw new IllegalArgumentException("Work cursor contains a foreign review parent");
                }
                var owner = owners.get(registration.sourceId());
                if (owner == null || owner.getRepositoryId() != repositoryId)
                    throw new IllegalArgumentException("Work cursor contains an item from another repository");
            }
        }
        int end = (int) Math.min(cursor.items().size(), (long) cursor.offset() + Math.min(1000, request.limit()));
        boolean complete = end == cursor.items().size();
        String next = null;
        if (!complete) {
            byte[] bytes = mapper.writeValueAsBytes(new Cursor(
                    cursor.workspaceId(),
                    cursor.holderId(),
                    cursor.holderVersion(),
                    cursor.memberKind(),
                    end,
                    cursor.items()));
            if (bytes.length > MAX_CURSOR_BYTES)
                return new ItemPage(cursor.items().subList(cursor.offset(), end), null, false, "ENUMERATION_LIMIT");
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        return new ItemPage(cursor.items().subList(cursor.offset(), end), next, complete, null);
    }
}
