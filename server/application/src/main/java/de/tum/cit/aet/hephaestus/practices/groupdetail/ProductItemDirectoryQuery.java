package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductItemDirectoryQuery {
    private final ProductItemRepository items;
    private final ProductReferenceQuery references;

    public Page<ItemRefDTO> list(long workspaceId, @Nullable String kind, Pageable pageable) {
        var selected = kind == null ? null : new ItemKind(kind).value();
        var page = items.directory(workspaceId, selected, pageable);
        var refs = references.items(workspaceId, page.getContent());
        return page.map(id -> Objects.requireNonNull(refs.get(id)));
    }
}
